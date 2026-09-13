package com.farm.irrigation.web;

import com.farm.irrigation.common.ApiResponse;
import com.farm.irrigation.control.RotationService;
import com.farm.irrigation.domain.RotationPlan;
import com.farm.irrigation.domain.RotationPlanItem;
import com.farm.irrigation.dto.RotationPlanRequest;
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

/** 分区轮灌计划。 */
@RestController
@RequestMapping("/api/rotation/plans")
public class RotationController {

    private final RotationService rotationService;

    public RotationController(RotationService rotationService) {
        this.rotationService = rotationService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(rotationService.list().stream().map(this::planView).toList());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> generate(@Valid @RequestBody RotationPlanRequest req) {
        RotationPlan plan = rotationService.generate(req);
        return ApiResponse.ok(detailInternal(plan.getId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(detailInternal(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Map<String, Object>> cancel(@PathVariable Long id) {
        rotationService.cancel(id);
        return ApiResponse.ok(detailInternal(id));
    }

    private Map<String, Object> detailInternal(Long id) {
        RotationPlan plan = rotationService.get(id);
        Map<String, Object> m = planView(plan);
        m.put("items", rotationService.items(id).stream().map(this::itemView).toList());
        return m;
    }

    private Map<String, Object> planView(RotationPlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("status", p.getStatus());
        m.put("planDate", p.getPlanDate());
        m.put("generatedAt", p.getGeneratedAt());
        m.put("generatedBy", p.getGeneratedBy());
        m.put("note", p.getNote());
        m.put("totalPlannedM3", p.getTotalPlannedM3());
        m.put("totalAppliedM3", p.getTotalAppliedM3());
        return m;
    }

    private Map<String, Object> itemView(RotationPlanItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.getId());
        m.put("planId", i.getPlanId());
        m.put("fieldId", i.getFieldId());
        m.put("seq", i.getSeq());
        m.put("priority", i.getPriority());
        m.put("scheduledStart", i.getScheduledStart());
        m.put("plannedVolumeM3", i.getPlannedVolumeM3());
        m.put("jobId", i.getJobId());
        m.put("status", i.getStatus());
        m.put("skipReason", i.getSkipReason());
        return m;
    }
}
