package com.farm.irrigation.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.common.ApiException;
import com.farm.irrigation.common.ApiResponse;
import com.farm.irrigation.domain.Alarm;
import com.farm.irrigation.service.AlarmService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

    private final AlarmService alarmService;
    private final ObjectMapper objectMapper;

    public AlarmController(AlarmService alarmService, ObjectMapper objectMapper) {
        this.alarmService = alarmService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Boolean ack,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<Alarm> result = alarmService.query(level, ack, page, size);
        List<Map<String, Object>> records = result.getContent().stream()
                .map(this::view).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", records);
        body.put("total", result.getTotalElements());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        return ApiResponse.ok(body);
    }

    @PostMapping("/{id}/ack")
    public ApiResponse<Map<String, Object>> ack(@PathVariable Long id) {
        try {
            return ApiResponse.ok(view(alarmService.acknowledge(id)));
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound(e.getMessage());
        }
    }

    private Map<String, Object> view(Alarm a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("level", a.getLevel());
        m.put("type", a.getType());
        m.put("deviceCode", a.getDeviceCode());
        m.put("fieldId", a.getFieldId());
        m.put("message", a.getMessage());
        m.put("context", parse(a.getContext()));
        m.put("acknowledged", a.isAcknowledged());
        m.put("createdAt", a.getCreatedAt());
        return m;
    }

    private Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
