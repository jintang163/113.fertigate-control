package com.farm.irrigation.service;

import com.farm.irrigation.domain.FieldEntity;
import com.farm.irrigation.domain.IrrigationJob;
import com.farm.irrigation.domain.IrrigationLedger;
import com.farm.irrigation.repo.FieldRepository;
import com.farm.irrigation.repo.IrrigationLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 灌肥台账：每次灌水/施肥记录起止时间、用量、执行方式。
 * WATER 行随灌溉作业开立/结算；若灌区配置了注肥泵与注肥比例，
 * 结算时按比例注肥原理补一行 FERTIGATION（肥液量 L = 水量m³ × 1000 × 比例%）。
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final IrrigationLedgerRepository ledgerRepository;
    private final FieldRepository fieldRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public LedgerService(IrrigationLedgerRepository ledgerRepository,
                         FieldRepository fieldRepository,
                         com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.ledgerRepository = ledgerRepository;
        this.fieldRepository = fieldRepository;
        this.objectMapper = objectMapper;
    }

    /** 作业开灌时开立 WATER 台账（幂等：已有未结算行则复用）。 */
    @Transactional
    public IrrigationLedger openForJob(IrrigationJob job) {
        Optional<IrrigationLedger> existing =
                ledgerRepository.findFirstByJobIdAndKindOrderByIdAsc(job.getId(), "WATER");
        if (existing.isPresent()) {
            return existing.get();
        }
        IrrigationLedger l = new IrrigationLedger();
        l.setFieldId(job.getFieldId());
        l.setJobId(job.getId());
        l.setKind("WATER");
        l.setStartTime(job.getStartTime() == null ? Instant.now() : job.getStartTime());
        l.setExecutionMode(modeOf(job.getTriggerType()));
        if (job.getPlannedM3() != null) {
            // 计划量仅用于开立时展示，实灌量结算时回填
            l.setWaterM3(BigDecimal.ZERO);
        }
        return ledgerRepository.save(l);
    }

    /** 作业终态（DONE/ABORTED）时结算，幂等。同时生成 FERTIGATION 行。 */
    @Transactional
    public void settleForJob(IrrigationJob job) {
        IrrigationLedger water = ledgerRepository
                .findFirstByJobIdAndKindOrderByIdAsc(job.getId(), "WATER").orElse(null);
        if (water == null) {
            // 异常路径（如 ACK 超时中止）未及开立，补开后立即结算
            water = openForJob(job);
        }
        if (water.getEndTime() != null) {
            return; // already settled
        }
        BigDecimal applied = job.getAppliedM3() == null ? BigDecimal.ZERO : job.getAppliedM3();
        water.setWaterM3(applied);
        water.setEndTime(job.getEndTime() == null ? Instant.now() : job.getEndTime());
        water.setStopReason(job.getStopReason());
        water.setExecutionMode(modeOf(job.getTriggerType()));
        ledgerRepository.save(water);

        // 注肥台账：有注肥泵且注肥比例 > 0（生长模型作业以决策 JSON 中的覆盖值为准）
        FieldEntity field = fieldRepository.findById(job.getFieldId()).orElse(null);
        double ratioPct = injectRatioForJob(job, field);
        boolean ferted = field != null && field.getFertPumpCode() != null
                && !field.getFertPumpCode().isBlank()
                && ratioPct > 0
                && applied.doubleValue() > 0;
        if (ferted) {
            boolean abortBeforeFert = "VALVE_ACK_TIMEOUT".equals(job.getStopReason());
            if (!abortBeforeFert) {
                BigDecimal fertL = applied.multiply(BigDecimal.valueOf(1000d))
                        .multiply(BigDecimal.valueOf(ratioPct / 100d))
                        .setScale(3, RoundingMode.HALF_UP);
                IrrigationLedger fert = new IrrigationLedger();
                fert.setFieldId(job.getFieldId());
                fert.setJobId(job.getId());
                fert.setKind("FERTIGATION");
                fert.setStartTime(water.getStartTime());
                fert.setEndTime(water.getEndTime());
                fert.setWaterM3(applied);
                fert.setFertilizerL(fertL);
                fert.setInjectRatioPct(BigDecimal.valueOf(ratioPct));
                fert.setExecutionMode(modeOf(job.getTriggerType()));
                fert.setStopReason(job.getStopReason());
                ledgerRepository.save(fert);
                log.info("灌肥台账: job={} water={}m3 fert={}L ratio={}%",
                        job.getId(), applied, fertL, ratioPct);
            }
        }
    }

    /** 注肥比：生长模型作业决策 JSON 中的覆盖值（fertInjectRatioPct）优先，否则灌区默认。 */
    private double injectRatioForJob(IrrigationJob job, FieldEntity field) {
        if (job.getDecision() != null) {
            try {
                var n = objectMapper.readTree(job.getDecision()).get("fertInjectRatioPct");
                if (n != null && n.isNumber() && n.asDouble() > 0) {
                    return n.asDouble();
                }
            } catch (Exception ignored) {
                // fall through：用灌区默认
            }
        }
        return field != null && field.getInjectRatioPct() != null
                ? field.getInjectRatioPct().doubleValue() : 0d;
    }

    /** 将作业台账关联到轮灌计划项。 */
    @Transactional
    public void linkJobToPlanItem(Long jobId, Long planItemId) {
        if (jobId == null || planItemId == null) {
            return;
        }
        for (IrrigationLedger l : ledgerRepository.findByJobId(jobId)) {
            l.setPlanItemId(planItemId);
            ledgerRepository.save(l);
        }
    }

    public Page<IrrigationLedger> query(Long fieldId, String kind, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 0), Math.max(size, 1),
                Sort.by(Sort.Direction.DESC, "startTime"));
        if (fieldId != null && kind != null && !kind.isBlank()) {
            return ledgerRepository.findByFieldIdAndKindOrderByStartTimeDesc(fieldId, kind, pr);
        }
        if (fieldId != null) {
            return ledgerRepository.findByFieldIdOrderByStartTimeDesc(fieldId, pr);
        }
        if (kind != null && !kind.isBlank()) {
            return ledgerRepository.findByKindOrderByStartTimeDesc(kind, pr);
        }
        return ledgerRepository.findAllByOrderByStartTimeDesc(pr);
    }

    /** 指定自然日（本地时区）的用量汇总。 */
    @Transactional(readOnly = true)
    public Map<String, Object> summarize(LocalDate day, ZoneId zone) {
        Instant from = day.atStartOfDay(zone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(zone).toInstant();
        Object[] row = ledgerRepository.summarize(from, to);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", day.toString());
        m.put("waterM3", ((Number) row[0]).doubleValue());
        m.put("fertilizerL", ((Number) row[1]).doubleValue());
        m.put("fertilizerKg", ((Number) row[2]).doubleValue());
        m.put("events", ((Number) row[3]).longValue());
        return m;
    }

    public Map<String, Object> toView(IrrigationLedger l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("fieldId", l.getFieldId());
        m.put("jobId", l.getJobId());
        m.put("planItemId", l.getPlanItemId());
        m.put("kind", l.getKind());
        m.put("startTime", l.getStartTime());
        m.put("endTime", l.getEndTime());
        m.put("waterM3", l.getWaterM3());
        m.put("fertilizerKg", l.getFertilizerKg());
        m.put("fertilizerL", l.getFertilizerL());
        m.put("fertilizerName", l.getFertilizerName());
        m.put("injectRatioPct", l.getInjectRatioPct());
        m.put("executionMode", l.getExecutionMode());
        m.put("stopReason", l.getStopReason());
        fieldRepository.findById(l.getFieldId()).ifPresent(f -> {
            m.put("fieldName", f.getName());
            m.put("cropVariety", f.getCropVariety());
        });
        return m;
    }

    /** trigger_type → 台账执行方式。 */
    private static String modeOf(String triggerType) {
        if (triggerType == null) {
            return "MANUAL";
        }
        return switch (triggerType.toUpperCase()) {
            case "AUTO" -> "AUTO";
            case "SCHEDULED" -> "SCHEDULED";
            case "MODEL" -> "MODEL";
            case "SAFETY_OFF" -> "SAFETY";
            default -> "MANUAL";
        };
    }
}
