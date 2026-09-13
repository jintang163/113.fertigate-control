package com.farm.irrigation.web;

import com.farm.irrigation.common.ApiResponse;
import com.farm.irrigation.control.ControlEngine;
import com.farm.irrigation.dto.GrowthCallbackRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Python 生长模型服务回调：融合决策（灌溉开/关/时长 + 施肥量）经安全前置后执行。
 */
@RestController
@RequestMapping("/api/growth")
public class GrowthCallbackController {

    private final ControlEngine controlEngine;

    public GrowthCallbackController(ControlEngine controlEngine) {
        this.controlEngine = controlEngine;
    }

    /** 生长模型决策回调（幂等 decisionId；triggerType=MODEL）。 */
    @PostMapping("/callback")
    public ApiResponse<Map<String, Object>> callback(@RequestBody GrowthCallbackRequest req) {
        return ApiResponse.ok(controlEngine.applyGrowthCallback(req));
    }
}
