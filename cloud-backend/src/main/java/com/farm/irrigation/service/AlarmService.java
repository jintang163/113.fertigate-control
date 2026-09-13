package com.farm.irrigation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.domain.Alarm;
import com.farm.irrigation.repo.AlarmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AlarmService {

    private static final Logger log = LoggerFactory.getLogger(AlarmService.class);

    private final AlarmRepository alarmRepository;
    private final ObjectMapper objectMapper;

    public AlarmService(AlarmRepository alarmRepository,
                        ObjectMapper objectMapper) {
        this.alarmRepository = alarmRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Alarm raise(String level, String type, String deviceCode, Long fieldId,
                       String message, Object context) {
        Alarm alarm = new Alarm();
        alarm.setLevel(level == null ? "INFO" : level);
        alarm.setType(type == null ? "UNKNOWN" : type);
        alarm.setDeviceCode(deviceCode);
        alarm.setFieldId(fieldId);
        alarm.setMessage(message);
        alarm.setContext(toJson(context));
        alarm.setAcknowledged(false);
        Alarm saved = alarmRepository.save(alarm);
        if ("CRITICAL".equals(alarm.getLevel())) {
            log.error("CRITICAL ALARM [{}] {}: {}", type, deviceCode, message);
        } else if ("WARN".equals(alarm.getLevel())) {
            log.warn("ALARM [{}] {}: {}", type, deviceCode, message);
        } else {
            log.info("ALARM [{}] {}: {}", type, deviceCode, message);
        }
        return saved;
    }

    public boolean hasUnacknowledgedCritical(Long fieldId) {
        if (fieldId == null) {
            return false;
        }
        return alarmRepository.existsByFieldIdAndLevelAndAcknowledged(fieldId, "CRITICAL", false);
    }

    public boolean hasUnacknowledged(Long fieldId, String level, String type) {
        if (fieldId == null) {
            return false;
        }
        return alarmRepository.existsByFieldIdAndLevelAndTypeAndAcknowledged(
                fieldId, level, type, false);
    }

    public Page<Alarm> query(String level, Boolean ack, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.max(size, 1),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        if (level != null && !level.isBlank() && ack != null) {
            return alarmRepository.findByLevelAndAcknowledged(level, ack, pr);
        }
        if (level != null && !level.isBlank()) {
            return alarmRepository.findByLevel(level, pr);
        }
        if (ack != null) {
            return alarmRepository.findByAcknowledged(ack, pr);
        }
        return alarmRepository.findAll(pr);
    }

    @Transactional
    public Alarm acknowledge(Long id) {
        Alarm a = alarmRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("alarm not found: " + id));
        a.setAcknowledged(true);
        return alarmRepository.save(a);
    }

    private String toJson(Object context) {
        if (context == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(context);
        } catch (JsonProcessingException e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("raw", String.valueOf(context));
            try {
                return objectMapper.writeValueAsString(fallback);
            } catch (JsonProcessingException ignored) {
                return null;
            }
        }
    }
}
