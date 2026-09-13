package com.farm.irrigation.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.common.ApiResponse;
import com.farm.irrigation.domain.Device;
import com.farm.irrigation.dto.DeviceDto;
import com.farm.irrigation.repo.SensorLatestRepository;
import com.farm.irrigation.service.DeviceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;
    private final SensorLatestRepository sensorLatestRepository;
    private final ObjectMapper objectMapper;

    public DeviceController(DeviceService deviceService,
                            SensorLatestRepository sensorLatestRepository,
                            ObjectMapper objectMapper) {
        this.deviceService = deviceService;
        this.sensorLatestRepository = sensorLatestRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(required = false) String gatewaySn,
            @RequestParam(required = false) String type) {
        List<Map<String, Object>> views = deviceService.list(gatewaySn, type).stream()
                .filter(d -> !"GATEWAY".equals(d.getType()))
                .map(this::deviceView).toList();
        return ApiResponse.ok(views);
    }

    @GetMapping("/{code}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String code) {
        Device d = deviceService.getByCode(code);
        Map<String, Object> view = deviceView(d);
        sensorLatestRepository.findById(code).ifPresent(latest -> {
            try {
                view.put("latest", objectMapper.readValue(latest.getPayload(), Object.class));
            } catch (Exception e) {
                view.put("latest", latest.getPayload());
            }
            view.put("state", latest.getState());
        });
        return ApiResponse.ok(view);
    }

    /** 注册设备：传感器 / 电磁阀 / 施肥泵 / 压力变送器 / 流量计。 */
    @PostMapping
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody DeviceDto dto) {
        Device d = deviceService.register(dto);
        return ApiResponse.ok(deviceView(d));
    }

    @PutMapping("/{code}")
    public ApiResponse<Map<String, Object>> update(@PathVariable String code,
                                                   @RequestBody DeviceDto dto) {
        Device d = deviceService.update(code, dto);
        return ApiResponse.ok(deviceView(d));
    }

    /** 投运状态切换：ENABLED / DISABLED。 */
    @PostMapping("/{code}/status")
    public ApiResponse<Map<String, Object>> setStatus(@PathVariable String code,
                                                      @RequestBody Map<String, String> body) {
        Device d = deviceService.setStatus(code,
                body.getOrDefault("status", "ENABLED").toUpperCase());
        return ApiResponse.ok(deviceView(d));
    }

    @DeleteMapping("/{code}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String code) {
        deviceService.delete(code);
        return ApiResponse.ok(Map.of("deleted", code));
    }

    private Map<String, Object> deviceView(Device d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("code", d.getCode());
        m.put("name", d.getName());
        m.put("type", d.getType());
        m.put("gatewaySn", d.getGatewaySn());
        m.put("modbusAddr", d.getModbusAddr());
        m.put("online", d.isOnline());
        m.put("status", d.getStatus());
        m.put("opening", d.getOpening());
        m.put("queueDepth", d.getQueueDepth());
        m.put("linkedField", d.getLinkedField());
        m.put("lastHeartbeat", d.getLastHeartbeat());
        m.put("registeredAt", d.getRegisteredAt());
        m.put("protocolConfig", parseOrRaw(d.getProtocolConfig()));
        m.put("params", parseOrRaw(d.getParams()));
        m.put("fieldId", deviceService.fieldIdOfDevice(d.getCode()));
        return m;
    }

    private Object parseOrRaw(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ignored) {
            return json;
        }
    }
}
