package com.farm.irrigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.control.DeviceCommandService;
import com.farm.irrigation.control.ValveCommandService;
import com.farm.irrigation.domain.FieldEntity;
import com.farm.irrigation.domain.IrrigationJob;
import com.farm.irrigation.repo.FieldRepository;
import com.farm.irrigation.repo.IrrigationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Handles inbound {@code farm/{gw}/events}: persists alarms and triggers
 * the linked valve CLOSE for CRITICAL interlock events on running jobs.
 */
@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final AlarmService alarmService;
    private final FieldRepository fieldRepository;
    private final IrrigationJobRepository jobRepository;
    private final DeviceService deviceService;
    private final ObjectMapper objectMapper;
    private final ValveCommandService valveCommandService;
    private final DeviceCommandService deviceCommandService;

    public EventService(AlarmService alarmService,
                        FieldRepository fieldRepository,
                        IrrigationJobRepository jobRepository,
                        DeviceService deviceService,
                        ObjectMapper objectMapper,
                        @Lazy ValveCommandService valveCommandService,
                        @Lazy DeviceCommandService deviceCommandService) {
        this.alarmService = alarmService;
        this.fieldRepository = fieldRepository;
        this.jobRepository = jobRepository;
        this.deviceService = deviceService;
        this.objectMapper = objectMapper;
        this.valveCommandService = valveCommandService;
        this.deviceCommandService = deviceCommandService;
    }

    @Transactional
    public void handle(String gatewaySn, JsonNode msg) {
        String level = textOr(msg.get("level"), "INFO");
        String code = textOr(msg.get("code"), "UNKNOWN");
        String valveCode = text(msg.get("valveCode"));
        String message = textOr(msg.get("message"), code);
        JsonNode context = msg.get("context");
        Instant ts = epochMillis(msg.get("ts"));
        if (ts == null) {
            ts = Instant.now();
        }

        Long fieldId = resolveField(valveCode);

        Object ctxObj = null;
        if (context != null && context.isObject()) {
            try {
                ctxObj = objectMapper.treeToValue(context, Object.class);
            } catch (Exception e) {
                ctxObj = context.toString();
            }
        }
        alarmService.raise(level, code, valveCode, fieldId, message, ctxObj);
        deviceService.markHeartbeat(gatewaySn, Instant.now());

        // CRITICAL interlock involving a valve of a running job -> force CLOSE
        if ("CRITICAL".equals(level) && valveCode != null) {
            triggerSafetyClose(valveCode, code);
        }
    }

    private void triggerSafetyClose(String valveCode, String reason) {
        List<IrrigationJob> running = jobRepository.findByStatusOrderByStartTimeDesc("RUNNING");
        for (IrrigationJob job : running) {
            if (valveCode.equals(job.getValveCode())) {
                log.warn("CRITICAL event {} on valve {} of running job {} -> safety CLOSE + pump STOP",
                        reason, valveCode, job.getId());
                FieldEntity field = fieldRepository.findById(job.getFieldId()).orElse(null);
                if (field != null && field.getFertPumpCode() != null) {
                    deviceCommandService.stopPump(field, "SAFETY_" + reason);
                }
                valveCommandService.sendClose(job, "SAFETY_" + reason);
                return;
            }
        }
    }

    private Long resolveField(String valveCode) {
        if (valveCode == null) {
            return null;
        }
        List<FieldEntity> fields = fieldRepository.findAll();
        for (FieldEntity f : fields) {
            if (valveCode.equals(f.getValveCode())) {
                return f.getId();
            }
        }
        return deviceService.fieldIdOfDevice(valveCode);
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String textOr(JsonNode n, String dflt) {
        String s = text(n);
        return s == null ? dflt : s;
    }

    private static Instant epochMillis(JsonNode node) {
        if (node == null || node.isNull() || !node.canConvertToLong()) {
            return null;
        }
        return Instant.ofEpochMilli(node.asLong());
    }
}
