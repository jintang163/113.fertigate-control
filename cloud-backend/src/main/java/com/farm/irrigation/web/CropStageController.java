package com.farm.irrigation.web;

import com.farm.irrigation.common.ApiResponse;
import com.farm.irrigation.domain.CropStageRecord;
import com.farm.irrigation.dto.StageRecordDto;
import com.farm.irrigation.service.CropStageService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 灌区作物生育期记录。 */
@RestController
@RequestMapping("/api/fields/{fieldId}/stages")
public class CropStageController {

    private final CropStageService cropStageService;

    public CropStageController(CropStageService cropStageService) {
        this.cropStageService = cropStageService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(@PathVariable Long fieldId) {
        return ApiResponse.ok(cropStageService.list(fieldId).stream()
                .map(cropStageService::toView).toList());
    }

    @GetMapping("/current")
    public ApiResponse<Map<String, Object>> current(@PathVariable Long fieldId) {
        CropStageRecord r = cropStageService.currentStage(fieldId);
        return ApiResponse.ok(r == null ? null : cropStageService.toView(r));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> add(@PathVariable Long fieldId,
                                                @Valid @RequestBody StageRecordDto dto) {
        return ApiResponse.ok(cropStageService.toView(cropStageService.add(fieldId, dto)));
    }

    @DeleteMapping("/{recordId}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long fieldId,
                                                   @PathVariable Long recordId) {
        cropStageService.delete(fieldId, recordId);
        return ApiResponse.ok(Map.of("deleted", recordId));
    }
}
