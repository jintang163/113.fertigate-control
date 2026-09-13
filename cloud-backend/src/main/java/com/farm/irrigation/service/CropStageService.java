package com.farm.irrigation.service;

import com.farm.irrigation.common.ApiException;
import com.farm.irrigation.domain.CropStageRecord;
import com.farm.irrigation.domain.FieldEntity;
import com.farm.irrigation.dto.StageRecordDto;
import com.farm.irrigation.repo.CropStageRecordRepository;
import com.farm.irrigation.repo.FieldRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 作物生育期记录：人工登记各生育期起始日期，用于模型校正与追溯。 */
@Service
public class CropStageService {

    private final CropStageRecordRepository recordRepository;
    private final FieldRepository fieldRepository;

    public CropStageService(CropStageRecordRepository recordRepository,
                            FieldRepository fieldRepository) {
        this.recordRepository = recordRepository;
        this.fieldRepository = fieldRepository;
    }

    public List<CropStageRecord> list(Long fieldId) {
        ensureField(fieldId);
        return recordRepository.findByFieldIdOrderByRecordDateDescIdDesc(fieldId);
    }

    @Transactional
    public CropStageRecord add(Long fieldId, StageRecordDto dto) {
        ensureField(fieldId);
        CropStageRecord r = new CropStageRecord();
        r.setFieldId(fieldId);
        r.setStageCode(dto.getStageCode());
        r.setStageName(dto.getStageName());
        r.setRecordDate(dto.getRecordDate() == null ? LocalDate.now() : dto.getRecordDate());
        r.setNote(dto.getNote());
        r.setOperator(dto.getOperator());
        return recordRepository.save(r);
    }

    @Transactional
    public void delete(Long fieldId, Long recordId) {
        CropStageRecord r = recordRepository.findById(recordId)
                .orElseThrow(() -> ApiException.notFound("stage record not found: " + recordId));
        if (!r.getFieldId().equals(fieldId)) {
            throw ApiException.badRequest("record does not belong to field " + fieldId);
        }
        recordRepository.delete(r);
    }

    /** 当前生效生育期：record_date <= 今天 的最新一条；无记录时返回 null（由播期模型推导）。 */
    public CropStageRecord currentStage(Long fieldId) {
        return recordRepository.findFirstByFieldIdOrderByRecordDateDescIdDesc(fieldId)
                .filter(r -> !r.getRecordDate().isAfter(LocalDate.now()))
                .orElse(null);
    }

    public Map<String, Object> toView(CropStageRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("fieldId", r.getFieldId());
        m.put("stageCode", r.getStageCode());
        m.put("stageName", r.getStageName());
        m.put("recordDate", r.getRecordDate());
        m.put("note", r.getNote());
        m.put("operator", r.getOperator());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    private void ensureField(Long fieldId) {
        fieldRepository.findById(fieldId)
                .orElseThrow(() -> ApiException.notFound("field not found: " + fieldId));
    }
}
