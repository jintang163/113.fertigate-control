package com.farm.irrigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.domain.IrrigationJob;
import com.farm.irrigation.domain.SensorLatest;
import com.farm.irrigation.repo.IrrigationJobRepository;
import com.farm.irrigation.repo.SensorLatestRepository;
import com.farm.irrigation.repo.ValveCommandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles inbound {@code farm/{gw}/valve/status}:
 * updates valve latest state / telemetry, ACKs the matching command and
 * settles the RUNNING job when CLOSED is reported.
 */
@Service
public class ValveStatusService {

    private static final Logger log = LoggerFactory.getLogger(ValveStatusService.class);

    private final IrrigationJobRepository jobRepository;
    private final ValveCommandRepository commandRepository;
    private final SensorLatestRepository sensorLatestRepository;
    private final DeviceService deviceService;
    private final InfluxService influx;
    private final ObjectMapper objectMapper;

    public ValveStatusService(IrrigationJobRepository jobRepository,
                              ValveCommandRepository commandRepository,
                              SensorLatestRepository sensorLatestRepository,
                              DeviceService deviceService,
                              InfluxService influx,
                              ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.commandRepository = commandRepository;
        this.sensorLatestRepository = sensorLatestRepository;
        this.deviceService = deviceService;
        this.influx = influx;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void handle(String gatewaySn, JsonNode msg) {
        String valveCode = text(msg.get("valveCode"));
        String state = text(msg.get("state"));
        String jobIdStr = text(msg.get("jobId"));
        String commandId = text(msg.get("commandId"));
        String stopReason = text(msg.get("stopReason"));
        Instant ts = epochMillis(msg.get("ts"));
        if (ts == null) {
            ts = Instant.now();
        }
        if (valveCode == null || state == null) {
            log.warn("valve/status missing valveCode/state: {}", msg);
            return;
        }

        double instantFlow = doubleOr(msg.get("instantFlow"), 0d);
        double totalFlow = doubleOr(msg.get("totalFlow"), 0d);
        double appliedVolume = doubleOr(msg.get("appliedVolume"), 0d);

        // 1) Influx valve measurement (open 0/1 + flows)
        Map<String, Object> values = new HashMap<>();
        values.put("instantFlow", instantFlow);
        values.put("totalFlow", totalFlow);
        values.put("appliedVolume", appliedVolume);
        values.put("open", state.equals("OPEN") ? 1d : 0d);
        influx.writePoint(influx.telemetryPoint("valve", valveCode, gatewaySn, values, ts));

        // 2) sensor_latest + device online
        Long fieldId = deviceService.fieldIdOfDevice(valveCode);
        upsertLatest(valveCode, fieldId, gatewaySn, state, values, ts);
        deviceService.markHeartbeat(valveCode, ts);
        deviceService.markHeartbeat(gatewaySn, Instant.now());

        // 3) command ACK tracking
        if (commandId != null) {
            commandRepository.findById(commandId).ifPresent(cmd -> {
                cmd.setAckAt(Instant.now());
                if ("FAULT".equals(state)) {
                    cmd.setStatus("FAILED");
                } else if ("OPEN".equals(state) && "OPEN".equals(cmd.getCommand())) {
                    cmd.setStatus("OPENED");
                } else if ("CLOSED".equals(state) && "CLOSE".equals(cmd.getCommand())) {
                    cmd.setStatus("CLOSED");
                } else if ("REJECTED".equals(state)) {
                    cmd.setStatus("REJECTED");
                }
                commandRepository.save(cmd);
            });
        }

        // 4) job settlement / state transitions
        IrrigationJob job = resolveRunningJob(jobIdStr, valveCode, fieldId);
        if (job == null) {
            log.debug("valve/status for {} state={} with no RUNNING job", valveCode, state);
            if ("FAULT".equals(state)) {
                log.warn("VALVE FAULT reported by {} with no active job", valveCode);
            }
            return;
        }

        switch (state) {
            case "OPEN", "OPENING" -> {
                if (!"RUNNING".equals(job.getStatus())) {
                    job.setStatus("RUNNING");
                }
                jobRepository.save(job);
                log.info("Job {} valve {} OPEN acknowledged", job.getId(), valveCode);
            }
            case "CLOSING" -> log.debug("Job {} valve {} closing", job.getId(), valveCode);
            case "CLOSED" -> settleClosed(job, appliedVolume, totalFlow, stopReason, ts);
            case "FAULT" -> {
                job.setStatus("ABORTED");
                job.setStopReason(pickReason(stopReason, "INTERLOCK_VALVE_FAULT"));
                job.setEndTime(ts);
                fillDuration(job);
                applyVolume(job, appliedVolume, totalFlow);
                jobRepository.save(job);
                log.warn("Job {} aborted due to valve FAULT", job.getId());
            }
            default -> log.debug("unhandled valve state {} for job {}", state, job.getId());
        }
    }

    private void settleClosed(IrrigationJob job, double appliedVolume, double totalFlow,
                              String stopReason, Instant ts) {
        job.setStatus("DONE");
        job.setStopReason(pickReason(stopReason, "REMOTE_CLOSE"));
        job.setEndTime(ts);
        fillDuration(job);
        applyVolume(job, appliedVolume, totalFlow);
        jobRepository.save(job);
        log.info("Job {} DONE applied={}m3 reason={}", job.getId(), job.getAppliedM3(), job.getStopReason());
    }

    private void applyVolume(IrrigationJob job, double appliedVolume, double totalFlow) {
        double v = appliedVolume > 0 ? appliedVolume : totalFlow;
        if (v > 0) {
            job.setAppliedM3(BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP));
        }
    }

    private void fillDuration(IrrigationJob job) {
        if (job.getStartTime() != null && job.getEndTime() != null) {
            job.setDurationSec((int) Duration.between(job.getStartTime(), job.getEndTime()).getSeconds());
        }
    }

    private String pickReason(String reported, String fallback) {
        return (reported == null || reported.isBlank()) ? fallback : reported;
    }

    private IrrigationJob resolveRunningJob(String jobIdStr, String valveCode, Long fieldId) {
        if (jobIdStr != null && !jobIdStr.isBlank()) {
            // numeric: PK; otherwise business code J20260912A
            if (jobIdStr.matches("\\d+")) {
                IrrigationJob j = jobRepository.findById(Long.parseLong(jobIdStr)).orElse(null);
                if (j != null && "RUNNING".equals(j.getStatus())) {
                    return j;
                }
            }
            List<IrrigationJob> running = jobRepository.findByStatusOrderByStartTimeDesc("RUNNING");
            for (IrrigationJob j : running) {
                if (jobIdStr.equals(j.getJobBizCode())) {
                    return j;
                }
            }
        }
        // fallback: latest RUNNING job for the valve (across its field)
        List<IrrigationJob> running = jobRepository.findByStatusOrderByStartTimeDesc("RUNNING");
        for (IrrigationJob j : running) {
            if (valveCode.equals(j.getValveCode())) {
                return j;
            }
        }
        if (fieldId != null) {
            return jobRepository.findFirstByFieldIdAndStatusOrderByStartTimeDesc(fieldId, "RUNNING")
                    .orElse(null);
        }
        return null;
    }

    private void upsertLatest(String valveCode, Long fieldId, String gatewaySn, String state,
                              Map<String, Object> values, Instant ts) {
        SensorLatest latest = sensorLatestRepository.findById(valveCode).orElseGet(SensorLatest::new);
        latest.setDeviceCode(valveCode);
        latest.setFieldId(fieldId);
        latest.setState(state);
        latest.setTs(ts);
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("deviceCode", valveCode);
            payload.put("gatewaySn", gatewaySn);
            payload.put("kind", "valve");
            payload.put("state", state);
            payload.put("values", values);
            payload.put("ts", ts.toEpochMilli());
            latest.setPayload(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.warn("serialize valve latest failed: {}", e.getMessage());
        }
        sensorLatestRepository.save(latest);
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    private static double doubleOr(JsonNode n, double dflt) {
        if (n == null || n.isNull() || !n.isNumber()) {
            return dflt;
        }
        return n.asDouble();
    }

    private static Instant epochMillis(JsonNode node) {
        if (node == null || node.isNull() || !node.canConvertToLong()) {
            return null;
        }
        return Instant.ofEpochMilli(node.asLong());
    }
}
