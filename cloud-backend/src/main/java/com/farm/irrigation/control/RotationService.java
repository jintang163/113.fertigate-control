package com.farm.irrigation.control;

import com.farm.irrigation.common.ApiException;
import com.farm.irrigation.config.ControlProperties;
import com.farm.irrigation.domain.FieldConfig;
import com.farm.irrigation.domain.FieldEntity;
import com.farm.irrigation.domain.IrrigationJob;
import com.farm.irrigation.domain.RotationPlan;
import com.farm.irrigation.domain.RotationPlanItem;
import com.farm.irrigation.dto.RotationPlanRequest;
import com.farm.irrigation.repo.FieldConfigRepository;
import com.farm.irrigation.repo.FieldRepository;
import com.farm.irrigation.repo.IrrigationJobRepository;
import com.farm.irrigation.repo.RotationPlanItemRepository;
import com.farm.irrigation.repo.RotationPlanRepository;
import com.farm.irrigation.service.AlarmService;
import com.farm.irrigation.service.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 分区轮灌调度：
 * 生成 —— 按灌区优先级 + 墒情（越旱越优先）排序，依据决策灌量与系统总流量
 *         顺序排定各灌区的计划开始时间（自动落入各自灌溉时窗）；
 * 执行 —— 定时扫描到期 PENDING 项，系统中无运行中作业时调用引擎 SCHEDULED 开阀；
 *         作业完成后回填 DONE/实灌量，全部完成则计划 DONE。
 */
@Service
public class RotationService {

    private static final Logger log = LoggerFactory.getLogger(RotationService.class);

    private final RotationPlanRepository planRepository;
    private final RotationPlanItemRepository itemRepository;
    private final FieldRepository fieldRepository;
    private final FieldConfigRepository configRepository;
    private final IrrigationJobRepository jobRepository;
    private final ControlEngine engine;
    private final IrrigationWindow window;
    private final AlarmService alarmService;
    private final LedgerService ledgerService;
    private final ControlProperties props;
    private final RotationService self;

    public RotationService(RotationPlanRepository planRepository,
                           RotationPlanItemRepository itemRepository,
                           FieldRepository fieldRepository,
                           FieldConfigRepository configRepository,
                           IrrigationJobRepository jobRepository,
                           ControlEngine engine,
                           IrrigationWindow window,
                           AlarmService alarmService,
                           LedgerService ledgerService,
                           ControlProperties props,
                           @Lazy RotationService self) {
        this.planRepository = planRepository;
        this.itemRepository = itemRepository;
        this.fieldRepository = fieldRepository;
        this.configRepository = configRepository;
        this.jobRepository = jobRepository;
        this.engine = engine;
        this.window = window;
        this.alarmService = alarmService;
        this.ledgerService = ledgerService;
        this.props = props;
        this.self = self;
    }

    // ------------------------------------------------------------------
    // 计划生成
    // ------------------------------------------------------------------

    @Transactional
    public RotationPlan generate(RotationPlanRequest req) {
        ZoneId zone = window.zoneId();
        LocalDate planDate = req.getPlanDate() == null ? LocalDate.now(zone) : req.getPlanDate();

        List<FieldEntity> fields = selectFields(req.getFieldIds());
        if (fields.isEmpty()) {
            throw ApiException.badRequest("没有可纳入轮灌计划的灌区（enabled 且有主阀）");
        }

        // 优先级升序 + 墒情升序（越旱越优先）
        List<FieldCandidate> candidates = new ArrayList<>();
        for (FieldEntity f : fields) {
            FieldConfig cfg = configRepository.findById(f.getId()).orElse(null);
            Double volume = adviseVolume(f);
            double moisture = adviseMoisture(f);
            candidates.add(new FieldCandidate(f, cfg, volume, moisture));
        }
        candidates.sort(Comparator
                .comparingInt((FieldCandidate c) -> c.field.getPriority() == null ? 100 : c.field.getPriority())
                .thenComparingDouble(c -> Double.isNaN(c.moisture) ? Double.MAX_VALUE : c.moisture)
                .thenComparing(c -> c.field.getId()));

        RotationPlan plan = new RotationPlan();
        plan.setName(req.getName() == null || req.getName().isBlank()
                ? "轮灌计划 " + planDate : req.getName());
        plan.setPlanDate(planDate);
        plan.setGeneratedBy(req.getGeneratedBy() == null ? "MANUAL" : req.getGeneratedBy());
        plan.setNote(req.getNote());
        plan.setStatus("SCHEDULED");
        plan.setGeneratedAt(Instant.now());
        plan = planRepository.save(plan);

        Instant cursor;
        if (req.getStartHour() != null) {
            int minute = req.getStartMinute() == null ? 0 : req.getStartMinute();
            cursor = LocalDateTime.of(planDate,
                    java.time.LocalTime.of(req.getStartHour(), minute)).atZone(zone).toInstant();
        } else {
            cursor = Instant.now();
        }

        int seq = 1;
        BigDecimal totalPlanned = BigDecimal.ZERO;
        for (FieldCandidate c : candidates) {
            // 计划开始时刻必须落入该灌区允许时窗
            if (cursor.isBefore(Instant.now())) {
                cursor = Instant.now();
            }
            if (!window.isAllowed(c.field, cursor)) {
                cursor = window.nextAllowedAt(c.field);
            }

            RotationPlanItem item = new RotationPlanItem();
            item.setPlanId(plan.getId());
            item.setFieldId(c.field.getId());
            item.setSeq(seq++);
            item.setPriority(c.field.getPriority() == null ? 100 : c.field.getPriority());
            item.setScheduledStart(cursor);
            if (c.volume != null && c.volume > 0) {
                BigDecimal v = BigDecimal.valueOf(c.volume).setScale(3, RoundingMode.HALF_UP);
                item.setPlannedVolumeM3(v);
                totalPlanned = totalPlanned.add(v);
            }
            item.setStatus("PENDING");
            itemRepository.save(item);

            cursor = cursor.plusSeconds(
                    estimatedDurationSec(c.field, c.volume) + props.getRotationGapSec());
        }

        plan.setTotalPlannedM3(totalPlanned);
        return planRepository.save(plan);
    }

    private List<FieldEntity> selectFields(List<Long> fieldIds) {
        List<FieldEntity> out = new ArrayList<>();
        for (FieldEntity f : fieldRepository.findAll()) {
            if (fieldIds != null && !fieldIds.isEmpty() && !fieldIds.contains(f.getId())) {
                continue;
            }
            FieldConfig cfg = configRepository.findById(f.getId()).orElse(null);
            if (cfg == null || !cfg.isEnabled() || f.getValveCode() == null) {
                continue;
            }
            out.add(f);
        }
        return out;
    }

    /** 决策建议灌量；服务不可用/非 IRRIGATE 时返回 null（释放时再实时决策）。 */
    private Double adviseVolume(FieldEntity f) {
        try {
            DecisionResult d = engine.evaluateAdvisory(f.getId());
            if ("IRRIGATE".equals(d.getDecision()) && d.getVolumeM3() != null && d.getVolumeM3() > 0) {
                return d.getVolumeM3();
            }
        } catch (Exception e) {
            log.debug("advise volume failed for field {}: {}", f.getId(), e.getMessage());
        }
        return null;
    }

    private double adviseMoisture(FieldEntity f) {
        try {
            DecisionResult d = engine.evaluateAdvisory(f.getId());
            return d.getMoisture() == null ? Double.NaN : d.getMoisture();
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private long estimatedDurationSec(FieldEntity f, Double volumeM3) {
        double lph = f.getEmitterTotalLph() == null ? 0 : f.getEmitterTotalLph().doubleValue();
        if (volumeM3 != null && volumeM3 > 0 && lph > 0) {
            return Math.round(volumeM3 * 1000d / lph * 3600d);
        }
        return 900; // 缺省 15 分钟
    }

    // ------------------------------------------------------------------
    // 到点释放（定时）
    // ------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${app.control.rotation-tick-sec:30}000", initialDelay = 20_000)
    public void releaseDueItems() {
        try {
            syncReleasedItems();
            doRelease();
        } catch (Exception e) {
            log.error("rotation tick failed: {}", e.getMessage(), e);
        }
    }

    private void doRelease() {
        // 全系统串行：同一主管道同时只灌一个灌区
        if (!jobRepository.findByStatusOrderByStartTimeDesc("RUNNING").isEmpty()) {
            return;
        }
        List<RotationPlanItem> due = itemRepository
                .findByStatusAndScheduledStartLessThanEqualOrderByPriorityAscScheduledStartAsc(
                        "PENDING", Instant.now());
        for (RotationPlanItem item : due) {
            RotationPlan plan = planRepository.findById(item.getPlanId()).orElse(null);
            if (plan == null || "CANCELLED".equals(plan.getStatus()) || "DONE".equals(plan.getStatus())) {
                continue;
            }
            FieldEntity field = fieldRepository.findById(item.getFieldId()).orElse(null);
            if (field == null) {
                markSkipped(item, "灌区不存在");
                continue;
            }
            if (!window.isAllowedNow(field)) {
                // 顺延到下一个允许时刻（独立事务）
                self.postponeItem(item.getId(), window.nextAllowedAt(field));
                continue;
            }
            // 每项独立事务：安全拦截导致的回滚不污染整个 tick
            boolean released = self.releaseOne(item.getId());
            if (released) {
                break; // 一次 tick 只释放一个（串行）
            }
        }
    }

    /** 在独立事务中释放一个计划项；安全拦截返回 false。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean releaseOne(Long itemId) {
        RotationPlanItem item = itemRepository.findById(itemId).orElse(null);
        if (item == null || !"PENDING".equals(item.getStatus())) {
            return false;
        }
        FieldEntity field = fieldRepository.findById(item.getFieldId()).orElse(null);
        if (field == null) {
            markSkipped(item, "灌区不存在");
            return false;
        }
        IrrigationJob job;
        try {
            Double volume = item.getPlannedVolumeM3() == null
                    ? null : item.getPlannedVolumeM3().doubleValue();
            // 开阀跑在独立事务里：被安全前置拦截时回滚只影响该子事务，不污染本方法
            job = self.attemptScheduledOpen(field.getId(), volume);
        } catch (SafetyBlockedException e) {
            self.markBlocked(itemId, truncate(e.getMessage(), 120), field.getValveCode(), field.getId());
            return false;
        }
        item.setStatus("RELEASED");
        item.setJobId(job.getId());
        item.setSkipReason(null);
        itemRepository.save(item);
        ledgerService.linkJobToPlanItem(job.getId(), item.getId());
        RotationPlan plan = planRepository.findById(item.getPlanId()).orElse(null);
        if (plan != null && !"RUNNING".equals(plan.getStatus())) {
            plan.setStatus("RUNNING");
            planRepository.save(plan);
        }
        log.info("轮灌计划 {} 项 {} 释放：field={} job={}",
                item.getPlanId(), item.getId(), field.getId(), job.getId());
        return true;
    }

    /** 独立事务执行 SCHEDULED 开阀；SafetyBlockedException 正常向上抛（该子事务随之回滚）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IrrigationJob attemptScheduledOpen(Long fieldId, Double volumeM3) {
        return engine.scheduledOpen(fieldId, volumeM3);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markBlocked(Long itemId, String reason, String valveCode, Long fieldId) {
        itemRepository.findById(itemId).ifPresent(i -> {
            i.setStatus("BLOCKED");
            i.setSkipReason(reason);
            itemRepository.save(i);
        });
        alarmService.raise("WARN", "ROTATION_ITEM_BLOCKED", valveCode, fieldId,
                "轮灌灌区 " + fieldId + " 释放被安全前置拦截: " + reason, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void postponeItem(Long itemId, Instant at) {
        itemRepository.findById(itemId).ifPresent(i -> {
            i.setScheduledStart(at);
            itemRepository.save(i);
        });
    }

    /** 已释放项与作业状态同步。 */
    private void syncReleasedItems() {
        for (RotationPlanItem item : itemRepository.findByStatusOrderByScheduledStartAsc("RELEASED")) {
            if (item.getJobId() == null) {
                continue;
            }
            IrrigationJob job = jobRepository.findById(item.getJobId()).orElse(null);
            if (job == null) {
                continue;
            }
            switch (job.getStatus()) {
                case "DONE" -> {
                    item.setStatus("DONE");
                    itemRepository.save(item);
                    accumulatePlan(item.getPlanId());
                }
                case "ABORTED" -> {
                    item.setStatus("SKIPPED");
                    item.setSkipReason(job.getStopReason());
                    itemRepository.save(item);
                }
                default -> { }
            }
        }
        for (RotationPlan plan : planRepository.findByStatusOrderByPlanDateDescIdDesc("RUNNING")) {
            List<RotationPlanItem> items = itemRepository.findByPlanIdOrderBySeqAsc(plan.getId());
            boolean anyOpen = items.stream().anyMatch(i ->
                    "RELEASED".equals(i.getStatus()) || "PENDING".equals(i.getStatus()));
            if (!anyOpen) {
                plan.setStatus("DONE");
                planRepository.save(plan);
            }
        }
    }

    private void accumulatePlan(Long planId) {
        List<RotationPlanItem> items = itemRepository.findByPlanIdOrderBySeqAsc(planId);
        BigDecimal applied = BigDecimal.ZERO;
        for (RotationPlanItem item : items) {
            if (item.getJobId() == null) {
                continue;
            }
            IrrigationJob job = jobRepository.findById(item.getJobId()).orElse(null);
            if (job != null && job.getAppliedM3() != null) {
                applied = applied.add(job.getAppliedM3());
            }
        }
        RotationPlan plan = planRepository.findById(planId).orElse(null);
        if (plan != null) {
            plan.setTotalAppliedM3(applied);
            planRepository.save(plan);
        }
    }

    private void markSkipped(RotationPlanItem item, String reason) {
        item.setStatus("SKIPPED");
        item.setSkipReason(truncate(reason, 120));
        itemRepository.save(item);
    }

    // ------------------------------------------------------------------
    // 查询 / 取消
    // ------------------------------------------------------------------

    public List<RotationPlan> list() {
        return planRepository.findAllByOrderByIdDesc();
    }

    @Transactional
    public RotationPlan get(Long id) {
        return planRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("rotation plan not found: " + id));
    }

    @Transactional
    public List<RotationPlanItem> items(Long planId) {
        get(planId);
        return itemRepository.findByPlanIdOrderBySeqAsc(planId);
    }

    @Transactional
    public RotationPlan cancel(Long id) {
        RotationPlan plan = get(id);
        plan.setStatus("CANCELLED");
        for (RotationPlanItem item : itemRepository.findByPlanIdOrderBySeqAsc(id)) {
            if ("PENDING".equals(item.getStatus())) {
                item.setStatus("SKIPPED");
                item.setSkipReason("PLAN_CANCELLED");
                itemRepository.save(item);
            }
        }
        return planRepository.save(plan);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private record FieldCandidate(FieldEntity field, FieldConfig config, Double volume, double moisture) {}
}
