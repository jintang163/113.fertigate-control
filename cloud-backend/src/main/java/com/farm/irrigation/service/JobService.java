package com.farm.irrigation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.common.ApiException;
import com.farm.irrigation.domain.IrrigationJob;
import com.farm.irrigation.repo.IrrigationJobRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class JobService {

    private final IrrigationJobRepository repository;
    private final ObjectMapper objectMapper;

    public JobService(IrrigationJobRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Page<IrrigationJob> query(Long fieldId, String status, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.max(size, 1),
                Sort.by(Sort.Direction.DESC, "startTime"));
        if (fieldId != null && status != null && !status.isBlank()) {
            return repository.findByFieldIdAndStatus(fieldId, status, pr);
        }
        if (fieldId != null) {
            return repository.findByFieldId(fieldId, pr);
        }
        if (status != null && !status.isBlank()) {
            return repository.findByStatus(status, pr);
        }
        return repository.findAll(pr);
    }

    public IrrigationJob get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> ApiException.notFound("job not found: " + id));
    }

    public Map<String, Object> toView(IrrigationJob j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId());
        m.put("jobBizCode", j.getJobBizCode());
        m.put("fieldId", j.getFieldId());
        m.put("triggerType", j.getTriggerType());
        m.put("decision", parse(j.getDecision()));
        m.put("startTime", j.getStartTime());
        m.put("endTime", j.getEndTime());
        m.put("plannedM3", j.getPlannedM3());
        m.put("appliedM3", j.getAppliedM3());
        m.put("durationSec", j.getDurationSec());
        m.put("status", j.getStatus());
        m.put("stopReason", j.getStopReason());
        m.put("valveCode", j.getValveCode());
        return m;
    }

    private Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
