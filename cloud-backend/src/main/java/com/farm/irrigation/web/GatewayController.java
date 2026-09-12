package com.farm.irrigation.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.common.ApiResponse;
import com.farm.irrigation.domain.Device;
import com.farm.irrigation.dto.GatewayConfigRequest;
import com.farm.irrigation.service.GatewayConfigService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/gateways")
public class GatewayController {

    private final GatewayConfigService gatewayConfigService;
    private final ObjectMapper objectMapper;

    public GatewayController(GatewayConfigService gatewayConfigService, ObjectMapper objectMapper) {
        this.gatewayConfigService = gatewayConfigService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(gatewayConfigService.listGateways().stream()
                .map(this::view).toList());
    }

    @PostMapping("/{sn}/config")
    public ApiResponse<Map<String, Object>> pushConfig(@PathVariable String sn,
                                                       @Valid @RequestBody GatewayConfigRequest req) {
        return ApiResponse.ok(gatewayConfigService.pushConfig(sn, req));
    }

    private Map<String, Object> view(Device gw) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", gw.getCode());
        m.put("gatewaySn", gw.getGatewaySn());
        m.put("online", gw.isOnline());
        m.put("queueDepth", gw.getQueueDepth());
        m.put("lastHeartbeat", gw.getLastHeartbeat());
        Object pc = null;
        if (gw.getProtocolConfig() != null) {
            try {
                pc = objectMapper.readValue(gw.getProtocolConfig(), Object.class);
            } catch (Exception ignored) {
                pc = gw.getProtocolConfig();
            }
        }
        m.put("protocolConfig", pc);
        return m;
    }
}
