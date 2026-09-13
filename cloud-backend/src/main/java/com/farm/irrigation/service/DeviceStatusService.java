package com.farm.irrigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.control.DeviceCommandService;
import com.farm.irrigation.domain.Device;
import com.farm.irrigation.domain.SensorLatest;
import com.farm.irrigation.repo.DeviceRepository;
import com.farm.irrigation.repo.SensorLatestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles inbound {@code farm/{gw}/device/status}: generic actuator feedback
 * (fertilizer pumps, modulating valves) — running state, opening 0-100,
 * motor current, pressure. Persists time-series values like telemetry and
 * updates the device table; FAULT raises an alarm.
 *
 * <pre>
 * {"type":"deviceStatus","gatewaySn":"GW001","ts":...,"deviceCode":"FP-01",
 *  "state":"RUNNING","opening":60,"motorCurrent":3.2,"overload":false}
 * </pre>
 */
@Service
public class DeviceStatusService {

    private static final Logger log = LoggerFactory.getLogger(DeviceStatusService.class);

    private final DeviceRepository deviceRepository;
    private final SensorLatestRepository sensorLatestRepository;
    private final DeviceService deviceService;
    private final InfluxService influx;
    private final AlarmService alarmService;
    private final DeviceCommandService deviceCommandService;
    private final ObjectMapper objectMapper;

    public DeviceStatusService(DeviceRepository deviceRepository,
                               SensorLatestRepository sensorLatestRepository,
                               DeviceService deviceService,
                               InfluxService influx,
                               AlarmService alarmService,
                               DeviceCommandService deviceCommandService,
                               ObjectMapper objectMapper) {
        this.deviceRepository = deviceRepository;
        this.sensorLatestRepository = sensorLatestRepository;
        this.deviceService = deviceService;
        this.influx = influx;
        this.alarmService = alarmService;
        this.deviceCommandService = deviceCommandService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void handle(String gatewaySn, JsonNode msg) {
        String deviceCode = text(msg.get("deviceCode"));
        if (deviceCode == null) {
            log.warn("device/status without deviceCode: {}", msg);
            return;
        }
        Instant ts = epochMillis(msg.get("ts"));
        if (ts == null) {
            ts = Instant.now();
        }
        String state = text(msg.get("state"));
        Integer opening = intOr(msg.get("opening"), null);
        boolean overload = msg.path("overload").asBoolean(false);
        String commandId = text(msg.get("commandId"));

        Map<String, Object> values = new HashMap<>();
        putNumber(values, "opening", msg.get("opening"));
        putNumber(values, "motorCurrent", msg.get("motorCurrent"));
        putNumber(values, "pressure", msg.get("pressure"));
        putNumber(values, "instantFlow", msg.get("instantFlow"));
        putNumber(values, "totalFlow", msg.get("totalFlow"));
        values.put("running", "RUNNING".equalsIgnoreCase(state) || "OPEN".equalsIgnoreCase(state) ? 1d : 0d);
        values.put("overload", overload ? 1d : 0d);

        // 1) time series
        influx.writePoint(influx.telemetryPoint("device", deviceCode, gatewaySn, values, ts));

        // 2) sensor_latest (kind=device)
        Long fieldId = deviceService.fieldIdOfDevice(deviceCode);
        SensorLatest latest = sensorLatestRepository.findById(deviceCode).orElseGet(SensorLatest::new);
        latest.setDeviceCode(deviceCode);
        if (fieldId != null) {
            latest.setFieldId(fieldId);
        }
        latest.setState(state);
        latest.setTs(ts);
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("deviceCode", deviceCode);
            payload.put("gatewaySn", gatewaySn);
            payload.put("kind", "device");
            payload.put("state", state);
            payload.put("values", values);
            payload.put("ts", ts.toEpochMilli());
            latest.setPayload(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("serialize device latest failed: {}", e.getMessage());
        }
        sensorLatestRepository.save(latest);

        // 3) device table + heartbeat
        Device d = deviceRepository.findByCode(deviceCode).orElse(null);
        if (d != null) {
            if (opening != null) {
                d.setOpening(opening);
            }
            if (overload || "FAULT".equalsIgnoreCase(state)) {
                d.setStatus("FAULT");
            } else if ("DISABLED".equals(d.getStatus())) {
                // keep administrative state
            } else {
                d.setStatus("ENABLED");
            }
            deviceRepository.save(d);
        }
        deviceService.markHeartbeat(deviceCode, ts);
        deviceService.markHeartbeat(gatewaySn, Instant.now());

        // 3b) ACK the originating device command
        if (commandId != null) {
            String ack = overload || "FAULT".equalsIgnoreCase(state) ? "FAILED"
                    : "REJECTED".equalsIgnoreCase(state) ? "REJECTED"
                    : ("STOPPED".equalsIgnoreCase(state) || "CLOSED".equalsIgnoreCase(state)) ? "DONE"
                    : "ACKED";
            deviceCommandService.markAck(commandId, ack);
        }

        // 4) alarms
        if (overload) {
            alarmService.raise("CRITICAL", "INTERLOCK_PUMP_OVERLOAD", deviceCode, fieldId,
                    "施肥泵 " + deviceCode + " 电机过载（电流 "
                            + msg.path("motorCurrent").asDouble(-1) + "A），已联锁停止",
                    values);
        } else if ("FAULT".equalsIgnoreCase(state)) {
            alarmService.raise("WARN", "DEVICE_FAULT", deviceCode, fieldId,
                    "设备 " + deviceCode + " 上报故障状态 FAULT", values);
        }
    }

    private static void putNumber(Map<String, Object> values, String key, JsonNode n) {
        if (n != null && n.isNumber()) {
            values.put(key, n.numberValue());
        }
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    private static Integer intOr(JsonNode n, Integer dflt) {
        if (n != null && n.isNumber()) {
            return n.asInt();
        }
        return dflt;
    }

    private static Instant epochMillis(JsonNode node) {
        if (node == null || node.isNull() || !node.canConvertToLong()) {
            return null;
        }
        return Instant.ofEpochMilli(node.asLong());
    }
}
