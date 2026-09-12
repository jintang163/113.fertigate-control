package com.farm.irrigation.service;

import com.farm.irrigation.domain.FieldEntity;
import com.farm.irrigation.domain.SensorLatest;
import com.farm.irrigation.repo.FieldRepository;
import com.farm.irrigation.repo.FieldSensorRepository;
import com.farm.irrigation.repo.SensorLatestRepository;
import com.farm.irrigation.service.InfluxService.FluxResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TelemetryQueryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryQueryService.class);
    private static final List<String> ALL_MEASUREMENTS = List.of("soil", "weather", "flow", "valve");

    private final InfluxService influx;
    private final FieldRepository fieldRepository;
    private final FieldSensorRepository fieldSensorRepository;
    private final SensorLatestRepository sensorLatestRepository;
    private final ObjectMapper objectMapper;

    public TelemetryQueryService(InfluxService influx,
                                 FieldRepository fieldRepository,
                                 FieldSensorRepository fieldSensorRepository,
                                 SensorLatestRepository sensorLatestRepository,
                                 ObjectMapper objectMapper) {
        this.influx = influx;
        this.fieldRepository = fieldRepository;
        this.fieldSensorRepository = fieldSensorRepository;
        this.sensorLatestRepository = sensorLatestRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * GET /api/telemetry?fieldId=1&metrics=soilMoist,ec,ph&range=24h&agg=10m
     * range: "1h" / "24h" / "7d" (normalized to Flux duration); agg: Flux window ("10m").
     */
    public FluxResult query(Long fieldId, String metricsCsv, String range, String agg) {
        FieldEntity field = fieldRepository.findById(fieldId)
                .orElseThrow(() -> new IllegalArgumentException("field not found: " + fieldId));
        List<String> devices = fieldSensorRepository.findDeviceCodesByFieldId(fieldId);
        List<String> metrics = parseMetrics(metricsCsv);
        if (devices.isEmpty()) {
            List<String> columns = new ArrayList<>();
            columns.add("ts");
            columns.addAll(metrics);
            return new FluxResult(columns, List.of());
        }
        String fluxRange = normalizeRange(range);
        String aggWindow = normalizeAgg(agg);
        try {
            return influx.query(ALL_MEASUREMENTS, devices, metrics, fluxRange, aggWindow);
        } catch (Exception e) {
            // time-series store temporarily unavailable: degrade to an empty chart
            log.warn("Influx query failed, returning empty telemetry: {}", e.getMessage());
            List<String> columns = new ArrayList<>();
            columns.add("ts");
            columns.addAll(metrics);
            return new FluxResult(columns, List.of());
        }
    }

    /** GET /api/telemetry/latest?fieldId=1 — latest values of every attached device. */
    public Map<String, Object> latest(Long fieldId) {
        FieldEntity field = fieldRepository.findById(fieldId)
                .orElseThrow(() -> new IllegalArgumentException("field not found: " + fieldId));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fieldId", fieldId);
        List<Map<String, Object>> devices = new ArrayList<>();
        double moistSum = 0;
        int moistCount = 0;
        Double ec = null;
        Double ph = null;
        Instant newest = null;

        for (SensorLatest latest : sensorLatestRepository.findByFieldId(fieldId)) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("deviceCode", latest.getDeviceCode());
            view.put("state", latest.getState());
            view.put("ts", latest.getTs());
            Object parsed = null;
            try {
                parsed = objectMapper.readValue(latest.getPayload(), Object.class);
            } catch (Exception e) {
                parsed = latest.getPayload();
            }
            view.put("payload", parsed);
            devices.add(view);

            try {
                JsonNode node = objectMapper.readTree(latest.getPayload());
                JsonNode values = node.get("values");
                if ("soil".equals(node.path("kind").asText()) && values != null) {
                    JsonNode m = values.get("soilMoist");
                    if (m == null) {
                        m = values.get("soilMoisture");
                    }
                    if (m != null && m.isNumber()) {
                        moistSum += m.asDouble();
                        moistCount++;
                    }
                    if (values.has("ec") && values.get("ec").isNumber()) {
                        ec = values.get("ec").asDouble();
                    }
                    if (values.has("ph") && values.get("ph").isNumber()) {
                        ph = values.get("ph").asDouble();
                    }
                }
            } catch (Exception ignored) {
                // skip malformed payload in aggregation
            }
            if (newest == null || latest.getTs().isAfter(newest)) {
                newest = latest.getTs();
            }
        }
        out.put("devices", devices);
        out.put("moistureAvg", moistCount == 0 ? null : Math.round(moistSum / moistCount * 100d) / 100d);
        out.put("ec", ec);
        out.put("ph", ph);
        out.put("latestTs", newest);
        return out;
    }

    private List<String> parseMetrics(String csv) {
        List<String> metrics = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            metrics.addAll(List.of("soilMoist", "ec", "ph"));
        } else {
            for (String m : csv.split(",")) {
                String t = m.trim();
                if (!t.isEmpty()) {
                    metrics.add(t);
                }
            }
        }
        return metrics;
    }

    private String normalizeRange(String range) {
        if (range == null || range.isBlank()) {
            return "-24h";
        }
        String r = range.trim();
        if (r.startsWith("-")) {
            return r;
        }
        return "-" + r;
    }

    private String normalizeAgg(String agg) {
        if (agg == null || agg.isBlank()) {
            return null;
        }
        return agg.trim();
    }
}
