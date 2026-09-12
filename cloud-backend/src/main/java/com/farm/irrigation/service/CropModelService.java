package com.farm.irrigation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.common.ApiException;
import com.farm.irrigation.domain.CropModel;
import com.farm.irrigation.dto.CropModelDto;
import com.farm.irrigation.repo.CropModelRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CropModelService {

    private final CropModelRepository repository;
    private final ObjectMapper objectMapper;

    public CropModelService(CropModelRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public List<CropModel> list() {
        return repository.findAll();
    }

    public CropModel get(String code) {
        return repository.findById(code)
                .orElseThrow(() -> ApiException.notFound("crop model not found: " + code));
    }

    public Map<String, Object> toView(CropModel c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", c.getCode());
        m.put("name", c.getName());
        m.put("stages", parseStages(c.getStages()));
        return m;
    }

    private Object parseStages(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    @Transactional
    public CropModel upsert(String code, CropModelDto dto) {
        if (dto.getName() == null || dto.getName().isBlank()) {
            throw ApiException.badRequest("crop name required");
        }
        CropModel c = repository.findById(code).orElseGet(CropModel::new);
        c.setCode(code);
        c.setName(dto.getName());
        try {
            c.setStages(objectMapper.writeValueAsString(
                    dto.getStages() == null ? List.of() : dto.getStages()));
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("invalid stages json: " + e.getMessage());
        }
        return repository.save(c);
    }
}
