package com.farm.irrigation.web;

import com.farm.irrigation.common.ApiResponse;
import com.farm.irrigation.domain.CropModel;
import com.farm.irrigation.dto.CropModelDto;
import com.farm.irrigation.service.CropModelService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/crop-models")
public class CropModelController {

    private final CropModelService service;

    public CropModelController(CropModelService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(service.list().stream().map(service::toView).toList());
    }

    @GetMapping("/{code}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String code) {
        return ApiResponse.ok(service.toView(service.get(code)));
    }

    @PutMapping("/{code}")
    public ApiResponse<Map<String, Object>> upsert(@PathVariable String code,
                                                   @Valid @RequestBody CropModelDto dto) {
        CropModel c = service.upsert(code, dto);
        return ApiResponse.ok(service.toView(c));
    }
}
