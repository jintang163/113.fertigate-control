package com.farm.irrigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.domain.SensorLatest;
import com.farm.irrigation.mqtt.TelemetryArrivedEvent;
import com.farm.irrigation.repo.SensorLatestRepository;
import com.influxdb.client.write.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Handles inbound {@code farm/{gw}/telemetry} envelopes:
 * parse records (batch back-fill supported), persist to InfluxDB,
 * upsert sensor_latest in PostgreSQL and refresh device heartbeat.
 */
@Service
public class TelemetryIngestService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestService.class);

    private final InfluxService influx;
    private final DeviceService deviceService;
    private final SensorLatestRepository sensorLatestRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public TelemetryIngestService(InfluxService influx,
                                  DeviceService deviceService,
                                  SensorLatestRepository sensorLatestRepository,
                                  ObjectMapper objectMapper,
                                  ApplicationEventPublisher eventPublisher) {
        this.influx = influx;
        this.deviceService = deviceService;
        this.sensorLatestRepository = sensorLatestRepository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void handleTelemetry(String gatewaySn, JsonNode envelope) {
        if (envelope == null || !envelope.has("records")) {
            log.warn("telemetry envelope without records from {}", gatewaySn);
            return;
        }
        String envGateway = textOr(envelope.get("gatewaySn"), gatewaySn);
        Instant envelopeTs = epochMillis(envelope.get("ts"));

        JsonNode records = envelope.get("records");
        if (!records.isArray()) {
            log.warn("records is not an array from {}", envGateway);
            return;
        }

        List<Point> batch = new ArrayList<>();
        Iterator<JsonNode> it = records.elements();
        while (it.hasNext()) {
            JsonNode rec = it.next();
            ingestRecord(envGateway, envelopeTs, rec, batch);
        }

        // PG state (sensor_latest/heartbeat) is persisted above; the time-series
        // write is best-effort and fully async so it can never block the MQTT thread.
        influx.writePoints(batch);

        // the gateway itself is alive whenever it sends telemetry
        deviceService.markHeartbeat(envGateway, Instant.now());
        deviceService.markGatewayDevicesOnline(envGateway, Instant.now());
    }

    private void ingestRecord(String gatewaySn, Instant envelopeTs, JsonNode rec, List<Point> batch) {
        String deviceCode = textOr(rec.get("deviceCode"), null);
        String kind = textOr(rec.get("kind"), "telemetry");
        Instant ts = epochMillis(rec.get("ts"));
        if (ts == null) {
            ts = envelopeTs != null ? envelopeTs : Instant.now();
        }
        if (deviceCode == null) {
            log.warn("telemetry record without deviceCode, ts={}", ts);
            return;
        }

        Map<String, Object> values = new HashMap<>();
        JsonNode valuesNode = rec.get("values");
        if (valuesNode != null && valuesNode.isObject()) {
            valuesNode.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                if (v == null || v.isNull()) {
                    return;
                }
                if (v.isNumber()) {
                    values.put(e.getKey(), v.numberValue());
                } else if (v.isBoolean()) {
                    values.put(e.getKey(), v.booleanValue());
                } else {
                    values.put(e.getKey(), v.asText());
                }
            });
        }
        String quality = textOr(rec.get("quality"), "GOOD");

        // 1) InfluxDB (same timestamp + tags overwrite duplicates from QoS1 redelivery / back-fill)
        Point point = influx.telemetryPoint(kind, deviceCode, gatewaySn, values, ts);
        point.addTag("quality", quality);
        batch.add(point);

        // 2) sensor_latest upsert (PG)
        Long fieldId = deviceService.fieldIdOfDevice(deviceCode);
        upsertLatest(deviceCode, fieldId, gatewaySn, kind, values, quality, ts);

        // 3) device online + heartbeat
        deviceService.markHeartbeat(deviceCode, ts);

        // 4) notify control engine for running-job soft-stop checks
        eventPublisher.publishEvent(new TelemetryArrivedEvent(
                deviceCode, fieldId, kind, values, ts, gatewaySn));
    }

    private void upsertLatest(String deviceCode, Long fieldId, String gatewaySn, String kind,
                              Map<String, Object> values, String quality, Instant ts) {
        SensorLatest latest = sensorLatestRepository.findById(deviceCode).orElseGet(SensorLatest::new);
        latest.setDeviceCode(deviceCode);
        if (fieldId != null) {
            latest.setFieldId(fieldId);
        }
        latest.setTs(ts);
        if ("valve".equals(kind)) {
            Object open = values.get("open");
            if (open instanceof Number n) {
                latest.setState(n.doubleValue() >= 1d ? "OPEN" : "CLOSED");
            }
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("deviceCode", deviceCode);
            payload.put("gatewaySn", gatewaySn);
            payload.put("kind", kind);
            payload.put("values", values);
            payload.put("quality", quality);
            payload.put("ts", ts.toEpochMilli());
            latest.setPayload(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("serialize latest payload failed: {}", e.getMessage());
        }
        sensorLatestRepository.save(latest);
    }

    private static Instant epochMillis(JsonNode node) {
        if (node == null || node.isNull() || !node.canConvertToLong()) {
            return null;
        }
        return Instant.ofEpochMilli(node.asLong());
    }

    private static String textOr(JsonNode node, String fallback) {
        if (node == null || node.isNull()) {
            return fallback;
        }
        String s = node.asText();
        return (s == null || s.isBlank()) ? fallback : s;
    }
}
