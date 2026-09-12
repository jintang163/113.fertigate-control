package com.farm.irrigation.web;

import com.farm.irrigation.common.ApiResponse;
import com.farm.irrigation.mqtt.MqttGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final MqttGateway mqttGateway;

    public HealthController(MqttGateway mqttGateway) {
        this.mqttGateway = mqttGateway;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("time", Instant.now().toString());
        m.put("mqttConnected", mqttGateway.isConnected());
        return ApiResponse.ok(m);
    }
}
