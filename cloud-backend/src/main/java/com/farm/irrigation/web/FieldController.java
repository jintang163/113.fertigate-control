package com.farm.irrigation.web;

import com.farm.irrigation.common.ApiResponse;
import com.farm.irrigation.domain.FieldEntity;
import com.farm.irrigation.dto.FieldDto;
import com.farm.irrigation.dto.FieldStatusDto;
import com.farm.irrigation.service.FieldService;
import com.farm.irrigation.service.FieldStatusService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/fields")
public class FieldController {

    private final FieldService fieldService;
    private final FieldStatusService fieldStatusService;

    public FieldController(FieldService fieldService, FieldStatusService fieldStatusService) {
        this.fieldService = fieldService;
        this.fieldStatusService = fieldStatusService;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(fieldService.list().stream().map(fieldService::toView).toList());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody FieldDto dto) {
        FieldEntity f = fieldService.create(dto);
        return ApiResponse.ok(fieldService.toView(f));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id, @Valid @RequestBody FieldDto dto) {
        FieldEntity f = fieldService.update(id, dto);
        return ApiResponse.ok(fieldService.toView(f));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(fieldService.toView(fieldService.get(id)));
    }

    @GetMapping("/{id}/status")
    public ApiResponse<FieldStatusDto> status(@PathVariable Long id) {
        return ApiResponse.ok(fieldStatusService.status(id));
    }
}
