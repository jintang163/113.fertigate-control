package com.farm.irrigation.web;

import com.farm.irrigation.common.ApiResponse;
import com.farm.irrigation.service.InfluxService.FluxResult;
import com.farm.irrigation.service.TelemetryQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

    private final TelemetryQueryService telemetryQueryService;

    public TelemetryController(TelemetryQueryService telemetryQueryService) {
        this.telemetryQueryService = telemetryQueryService;
    }

    @GetMapping
    public ApiResponse<FluxResult> query(
            @RequestParam Long fieldId,
            @RequestParam(required = false) String metrics,
            @RequestParam(required = false, defaultValue = "24h") String range,
            @RequestParam(required = false) String agg) {
        return ApiResponse.ok(telemetryQueryService.query(fieldId, metrics, range, agg));
    }

    @GetMapping("/latest")
    public ApiResponse<Map<String, Object>> latest(@RequestParam Long fieldId) {
        return ApiResponse.ok(telemetryQueryService.latest(fieldId));
    }
}
