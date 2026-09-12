package com.farm.irrigation.service;

import com.farm.irrigation.control.DecisionClient;
import com.farm.irrigation.control.DecisionResult;
import com.farm.irrigation.control.FieldSnapshot;
import com.farm.irrigation.control.LocalDecisionCalculator;
import com.farm.irrigation.control.SnapshotService;
import com.farm.irrigation.domain.Alarm;
import com.farm.irrigation.domain.CropModel;
import com.farm.irrigation.domain.FieldConfig;
import com.farm.irrigation.domain.FieldEntity;
import com.farm.irrigation.dto.FieldStatusDto;
import com.farm.irrigation.repo.AlarmRepository;
import com.farm.irrigation.repo.CropModelRepository;
import com.farm.irrigation.repo.FieldConfigRepository;
import com.farm.irrigation.repo.FieldRepository;
import com.farm.irrigation.repo.IrrigationJobRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FieldStatusService {

    private final FieldRepository fieldRepository;
    private final FieldConfigRepository configRepository;
    private final CropModelRepository cropModelRepository;
    private final IrrigationJobRepository jobRepository;
    private final AlarmRepository alarmRepository;
    private final SnapshotService snapshotService;
    private final LocalDecisionCalculator localCalculator;
    private final DecisionClient decisionClient;

    public FieldStatusService(FieldRepository fieldRepository,
                              FieldConfigRepository configRepository,
                              CropModelRepository cropModelRepository,
                              IrrigationJobRepository jobRepository,
                              AlarmRepository alarmRepository,
                              SnapshotService snapshotService,
                              LocalDecisionCalculator localCalculator,
                              DecisionClient decisionClient) {
        this.fieldRepository = fieldRepository;
        this.configRepository = configRepository;
        this.cropModelRepository = cropModelRepository;
        this.jobRepository = jobRepository;
        this.alarmRepository = alarmRepository;
        this.snapshotService = snapshotService;
        this.localCalculator = localCalculator;
        this.decisionClient = decisionClient;
    }

    /** Cheap status: local thetaStart (no remote call). */
    public FieldStatusDto status(Long fieldId) {
        return build(fieldId, false);
    }

    /** Full advisory: calls the decision service (falls back locally). */
    public FieldStatusDto statusWithDecision(Long fieldId) {
        return build(fieldId, true);
    }

    private FieldStatusDto build(Long fieldId, boolean callDecision) {
        FieldEntity field = fieldRepository.findById(fieldId)
                .orElseThrow(() -> new IllegalArgumentException("field not found: " + fieldId));
        FieldConfig cfg = configRepository.findById(fieldId).orElse(null);
        FieldStatusDto dto = new FieldStatusDto();
        dto.setFieldId(field.getId());
        dto.setName(field.getName());
        dto.setMode(cfg == null ? "AUTO" : cfg.getMode());

        FieldSnapshot snap = snapshotService.build(field, cfg);
        dto.setValveState(snap.getValveState() == null ? "CLOSED" : snap.getValveState());
        dto.setValveOnline(snap.isValveOnline());
        dto.setGatewayOnline(snap.isGatewayOnline());
        dto.setMoisture(nanToNull(snap.getMoistureAvg()));
        dto.setEc(snap.getEc());
        dto.setPh(snap.getPh());
        if (cfg != null) {
            dto.setThetaFc(cfg.getThetaFc() == null ? null : cfg.getThetaFc().doubleValue());
            dto.setThetaWp(cfg.getThetaWp() == null ? null : cfg.getThetaWp().doubleValue());
            dto.setHardMax(cfg.getThetaFc() == null ? null
                    : cfg.getThetaFc().doubleValue()
                        + (cfg.getHardMaxOffsetPct() == null ? 3d : cfg.getHardMaxOffsetPct().doubleValue()));
        }

        CropModel crop = cropModelRepository.findById(field.getCropCode()).orElse(null);
        long days = DecisionClient.daysAfterSowing(field);
        if (crop != null && cfg != null) {
            dto.setStage(localCalculator.stageName(crop, days));
            dto.setThetaStart(localCalculator.thetaStart(cfg, crop, days));
        }

        if (callDecision && crop != null && cfg != null) {
            try {
                DecisionResult r = decisionClient.decide(field, cfg, crop, snap);
                if (r.getThetaStart() != null) {
                    dto.setThetaStart(r.getThetaStart());
                }
                if (r.getStage() != null) {
                    dto.setStage(r.getStage());
                }
                dto.setDecisionFallback(r.isFallback());
                dto.setReasons(r.getReasons());
            } catch (Exception ignored) {
                // local values retained
            }
        }

        jobRepository.findFirstByFieldIdAndStatusOrderByStartTimeDesc(fieldId, "RUNNING")
                .ifPresentOrElse(
                        job -> {
                            dto.setJobId(job.getId());
                            dto.setJobBizCode(job.getJobBizCode());
                            dto.setJobStatus(job.getStatus());
                        },
                        () -> jobRepository.findFirstByFieldIdOrderByStartTimeDesc(fieldId)
                                .ifPresent(job -> {
                                    dto.setJobId(job.getId());
                                    dto.setJobBizCode(job.getJobBizCode());
                                    dto.setJobStatus(job.getStatus());
                                    dto.setLastIrrigTime(
                                            (job.getEndTime() != null ? job.getEndTime() : job.getStartTime()).toString());
                                }));

        List<Alarm> alarms = alarmRepository
                .findByFieldId(fieldId, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();
        dto.setAlarms(alarms.stream().map(this::alarmView).toList());
        return dto;
    }

    private Map<String, Object> alarmView(Alarm a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("level", a.getLevel());
        m.put("type", a.getType());
        m.put("message", a.getMessage());
        m.put("acknowledged", a.isAcknowledged());
        m.put("createdAt", a.getCreatedAt());
        return m;
    }

    private static Double nanToNull(double v) {
        return Double.isNaN(v) ? null : v;
    }
}
