package com.farm.irrigation.web;

import com.farm.irrigation.common.ApiResponse;
import com.farm.irrigation.control.ControlEngine;
import com.farm.irrigation.control.CycleReport;
import com.farm.irrigation.control.DecisionResult;
import com.farm.irrigation.domain.IrrigationJob;
import com.farm.irrigation.service.JobService;
import com.farm.irrigation.dto.ControlRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class ControlController {

    private final ControlEngine controlEngine;
    private final JobService jobService;

    public ControlController(ControlEngine controlEngine, JobService jobService) {
        this.controlEngine = controlEngine;
        this.jobService = jobService;
    }

    /** Call the decision service for advice only — no valve action. */
    @PostMapping("/api/fields/{id}/decision/evaluate")
    public ApiResponse<DecisionResult> evaluate(@PathVariable Long id) {
        return ApiResponse.ok(controlEngine.evaluateAdvisory(id));
    }

    /** Manual OPEN/CLOSE. MANUAL mode applies directly; AUTO needs force=true. */
    @PostMapping("/api/fields/{id}/control")
    public ApiResponse<Map<String, Object>> control(@PathVariable Long id,
                                                    @Valid @RequestBody ControlRequest req) {
        String action = req.getAction() == null ? "" : req.getAction().trim().toUpperCase();
        Map<String, Object> result = new LinkedHashMap<>();
        switch (action) {
            case "OPEN" -> {
                if (!req.isForce() && !controlEngine.isManualMode(id)) {
                    result.put("accepted", false);
                    result.put("message", "field is in AUTO mode; pass force=true to override");
                    return ApiResponse.ok(result);
                }
                IrrigationJob job = controlEngine.manualOpen(id, req.getVolumeM3());
                result.put("accepted", job != null);
                if (job != null) {
                    result.put("job", jobService.toView(job));
                }
            }
            case "CLOSE" -> {
                controlEngine.manualClose(id);
                result.put("accepted", true);
                result.put("message", "CLOSE command published");
            }
            default -> {
                result.put("accepted", false);
                result.put("message", "action must be OPEN or CLOSE");
            }
        }
        return ApiResponse.ok(result);
    }

    /** Trigger one full evaluation cycle immediately. */
    @PostMapping("/api/control/run-cycle")
    public ApiResponse<CycleReport> runCycle() {
        return ApiResponse.ok(controlEngine.runCycle());
    }
}
