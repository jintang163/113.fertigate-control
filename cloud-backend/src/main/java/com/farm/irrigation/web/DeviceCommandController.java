package com.farm.irrigation.web;

import com.farm.irrigation.common.ApiResponse;
import com.farm.irrigation.control.DeviceCommandService;
import com.farm.irrigation.domain.Device;
import com.farm.irrigation.domain.DeviceCommand;
import com.farm.irrigation.service.DeviceService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 手动/自动控制指令下发：施肥泵启停与开度、调节阀开度。
 * body: {"action":"START"|"STOP"|"SET_OPENING","opening":0-100,"jobId":null}
 */
@RestController
@RequestMapping("/api/devices/{code}/command")
public class DeviceCommandController {

    private final DeviceCommandService deviceCommandService;
    private final DeviceService deviceService;

    public DeviceCommandController(DeviceCommandService deviceCommandService,
                                   DeviceService deviceService) {
        this.deviceCommandService = deviceCommandService;
        this.deviceService = deviceService;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> command(@PathVariable String code,
                                                    @RequestBody Map<String, Object> body) {
        Device device = deviceService.getByCode(code);
        String action = String.valueOf(body.getOrDefault("action", "")).toString().trim().toUpperCase();
        Integer opening = toInt(body.get("opening"));
        Long jobId = body.get("jobId") == null ? null : toLong(body.get("jobId"));

        Map<String, Object> result = new LinkedHashMap<>();
        DeviceCommand cmd;
        switch (action) {
            case "START" -> cmd = deviceCommandService.send(code, "START",
                    opening == null ? 100 : opening, jobId, Map.of());
            case "STOP" -> cmd = deviceCommandService.send(code, "STOP", 0, jobId, Map.of());
            case "SET_OPENING" -> cmd = deviceCommandService.setOpening(
                    code, opening == null ? 0 : opening, jobId);
            default -> {
                result.put("accepted", false);
                result.put("message", "action must be START / STOP / SET_OPENING");
                return ApiResponse.ok(result);
            }
        }
        result.put("accepted", cmd != null);
        if (cmd != null) {
            result.put("commandId", cmd.getCommandId());
            result.put("status", cmd.getStatus());
        } else {
            result.put("message", "指令未下发：设备未注册或无所属网关（" + device.getType() + "）");
        }
        return ApiResponse.ok(result);
    }

    private static Integer toInt(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(o.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private static Long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(o.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
