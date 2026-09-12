package com.farm.irrigation.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.common.ApiResponse;
import com.farm.irrigation.domain.Device;
import com.farm.irrigation.repo.SensorLatestRepository;
import com.farm.irrigation.service.DeviceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    private Map<String, Object> deviceView(Device d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("code", d.getCode());
        m.put("type", d.getType());
        m.put("gatewaySn", d.getGatewaySn());
        m.put("modbusAddr", d.getModbusAddr());
        m.put("online", d.isOnline());
        m.put("queueDepth", d.getQueueDepth());
        m.put("lastHeartbeat", d.getLastHeartbeat());
        Object pc = null;
        if (d.getProtocolConfig() != null) {
            try {
                pc = objectMapper.readValue(d.getProtocolConfig(), Object.class);
            } catch (Exception ignored) {
                pc = d.getProtocolConfig();
            }
        }
        m.put("protocolConfig", pc);
        m.put("fieldId", deviceService.fieldIdOfDevice(d.getCode()));
        return m;
    }
}
