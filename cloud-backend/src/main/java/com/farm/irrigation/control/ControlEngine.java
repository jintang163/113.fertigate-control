package com.farm.irrigation.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.config.ControlProperties;
import com.farm.irrigation.domain.CropModel;
import com.farm.irrigation.domain.FieldConfig;
import com.farm.irrigation.domain.FieldEntity;
import com.farm.irrigation.domain.IrrigationJob;
import com.farm.irrigation.domain.ValveCommand;
import com.farm.irrigation.dto.GrowthCallbackRequest;
import com.farm.irrigation.mqtt.TelemetryArrivedEvent;
import com.farm.irrigation.repo.CropModelRepository;
import com.farm.irrigation.repo.FieldConfigRepository;
import com.farm.irrigation.repo.FieldRepository;
import com.farm.irrigation.repo.IrrigationJobRepository;
import com.farm.irrigation.repo.ValveCommandRepository;
import com.farm.irrigation.service.AlarmService;
import com.farm.irrigation.service.LedgerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Automatic control engine — the state machine from control-logic.md section 4.
 *
 * IDLE --(IRRIGATE + all safety preconditions)--> STARTING --(OPEN ACK)--> RUNNING
 * RUNNING --(volume / duration / moisture target / hard interlock / manual)--> CLOSED + settle --> DONE
 */
@Service
public class ControlEngine {

    private static final Logger log = LoggerFactory.getLogger(ControlEngine.class);
    private static final DateTimeFormatter BIZ_FMT = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.systemDefault());

    /** 运行中作业的缺水/低流量首次发生时间（联锁延时防抖），key=jobId。 */
    private final Map<Long, Instant> pressureLowSince = new ConcurrentHashMap<>();
    private final Map<Long, Instant> flowLowSince = new ConcurrentHashMap<>();
    /** 作业当前注肥阶段（PRE_WATER/MID_RUN/FLUSH），仅在状态迁移时下发泵指令。 */
    private final Map<Long, String> fertPhaseByJob = new ConcurrentHashMap<>();
    /** 生长模型回调幂等键集合（单机有界：超 10000 清空重建）。 */
    private final java.util.Set<String> processedDecisionIds = ConcurrentHashMap.newKeySet();
    /** 开阀后流量建立宽限（s），避免启动瞬间误判缺水。 */
    private static final long FLOW_GRACE_SEC = 30;

    private final FieldRepository fieldRepository;
    private final FieldConfigRepository configRepository;
    private final CropModelRepository cropModelRepository;
    private final IrrigationJobRepository jobRepository;
    private final ValveCommandRepository commandRepository;
    private final SnapshotService snapshotService;
    private final DecisionClient decisionClient;
    private final ValveCommandService valveCommandService;
    private final DeviceCommandService deviceCommandService;
    private final AlarmService alarmService;
    private final LedgerService ledgerService;
    private final ControlProperties props;
    private final ObjectMapper objectMapper;
    private final WeatherLinkageService weatherLinkage;
    private final IrrigationWindow irrigationWindow;

    public ControlEngine(FieldRepository fieldRepository,
                         FieldConfigRepository configRepository,
                         CropModelRepository cropModelRepository,
                         IrrigationJobRepository jobRepository,
                         ValveCommandRepository commandRepository,
                         SnapshotService snapshotService,
                         DecisionClient decisionClient,
                         ValveCommandService valveCommandService,
                         DeviceCommandService deviceCommandService,
                         AlarmService alarmService,
                         LedgerService ledgerService,
                         ControlProperties props,
                         ObjectMapper objectMapper,
                         WeatherLinkageService weatherLinkage,
                         IrrigationWindow irrigationWindow) {
        this.fieldRepository = fieldRepository;
        this.configRepository = configRepository;
        this.cropModelRepository = cropModelRepository;
        this.jobRepository = jobRepository;
        this.commandRepository = commandRepository;
        this.snapshotService = snapshotService;
        this.decisionClient = decisionClient;
        this.valveCommandService = valveCommandService;
        this.deviceCommandService = deviceCommandService;
        this.alarmService = alarmService;
        this.ledgerService = ledgerService;
        this.props = props;
        this.objectMapper = objectMapper;
        this.weatherLinkage = weatherLinkage;
        this.irrigationWindow = irrigationWindow;
    }

    // ------------------------------------------------------------------
    // Scheduled full evaluation (default every 5 minutes, configurable)
    // ------------------------------------------------------------------

    /**
     * Scheduled entry point. The actual trigger is registered by
     * {@link com.farm.irrigation.config.ControlScheduler} (cron or fixedDelay);
     * the method is also invokable manually via POST /api/control/run-cycle.
     */
    @Transactional
    public synchronized CycleReport runCycle() {
        CycleReport report = new CycleReport();
        List<FieldEntity> fields;
        try {
            fields = fieldRepository.findAll();
        } catch (Exception e) {
            log.error("runCycle: cannot load fields: {}", e.getMessage(), e);
            report.addNote("field load failed: " + e.getMessage());
            return report;
        }

        // 1) soft-stop / safety supervision for every RUNNING job (AUTO and MANUAL)
        for (IrrigationJob job : jobRepository.findByStatusOrderByStartTimeDesc("RUNNING")) {
            try {
                superviseRunning(job, report);
            } catch (Exception e) {
                log.error("supervise job {} failed: {}", job.getId(), e.getMessage(), e);
            }
        }

        // 2) evaluate each enabled AUTO field for new irrigations
        for (FieldEntity field : fields) {
            try {
                FieldConfig cfg = configRepository.findById(field.getId()).orElse(null);
                if (cfg == null || !cfg.isEnabled()) {
                    continue;
                }
                if ("AUTO".equalsIgnoreCase(cfg.getMode())) {
                    evaluateAutoField(field, cfg, report);
                } else {
                    // MANUAL: engine only watches, never starts/stops on its own
                    // (hard interlocks are enforced in superviseRunning + CRITICAL events)
                    log.debug("field {} in MANUAL mode, skip auto evaluation", field.getId());
                }
            } catch (Exception e) {
                // decision service failure or anything else must never break the loop
                log.error("evaluate field {} failed: {}", field.getId(), e.getMessage(), e);
                report.addNote("field " + field.getId() + " error: " + e.getMessage());
            }
        }
        log.info("Control cycle finished: {}", report);
        return report;
    }

    /** STARTING ACK timeout tracking: retry once after 30s, abort after 60s. */
    @Scheduled(fixedDelay = 5000, initialDelay = 20000)
    @Transactional
    public void checkStartingTimeouts() {
        try {
            List<ValveCommand> pending = commandRepository.findByStatus("SENT");
            for (ValveCommand vc : pending) {
                handleStartingTimeout(vc);
            }
        } catch (Exception e) {
            log.error("starting-timeout check failed: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public void handleStartingTimeout(ValveCommand vc) {
        // only OPEN commands drive the STARTING -> ABORTED timeout; unacknowledged
        // CLOSE commands are settled by the subsequent CLOSED valve/status
        if (!"OPEN".equals(vc.getCommand())) {
            return;
        }
        long ageMs = Duration.between(vc.getCreatedAt(), Instant.now()).toMillis();
        if (ageMs < props.getCommandAckTimeoutMs()) {
            return;
        }
        if (vc.getRetryCount() == 0 && ageMs < props.getCommandFinalTimeoutMs()) {
            // first retry: republish original idempotent payload
            IrrigationJob job = vc.getJobId() == null ? null : jobRepository.findById(vc.getJobId()).orElse(null);
            String gw = job == null ? null : gatewayOf(job);
            if (gw != null && "RUNNING".equals(job == null ? null : job.getStatus())) {
                log.warn("Valve command {} not ACKed after {}ms, republishing once", vc.getCommandId(), ageMs);
                valveCommandService.republish(vc, gw);
            }
            return;
        }
        if (ageMs >= props.getCommandFinalTimeoutMs()) {
            vc.setStatus("TIMEOUT");
            commandRepository.save(vc);
            if (vc.getJobId() != null) {
                jobRepository.findById(vc.getJobId()).ifPresent(job -> {
                    if ("RUNNING".equals(job.getStatus())) {
                        job.setStatus("ABORTED");
                        job.setStopReason("VALVE_ACK_TIMEOUT");
                        job.setEndTime(Instant.now());
                        if (job.getStartTime() != null) {
                            job.setDurationSec((int) Duration.between(job.getStartTime(), Instant.now()).getSeconds());
                        }
                        jobRepository.save(job);
                        alarmService.raise("WARN", "VALVE_ACK_TIMEOUT", job.getValveCode(),
                                job.getFieldId(),
                                "阀门 " + job.getValveCode() + " OPEN 命令 " + vc.getCommandId()
                                        + " 在 " + (props.getCommandFinalTimeoutMs() / 1000) + "s 内未确认，作业中止",
                                Map.of("commandId", vc.getCommandId(), "jobId", job.getId()));
                        ledgerService.settleForJob(job);
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------------
    // AUTO field evaluation
    // ------------------------------------------------------------------

    private void evaluateAutoField(FieldEntity field, FieldConfig cfg, CycleReport report) {
        // 生长模型驱动的田块由 Python 生长模型服务回调触发，云端定时评估跳过，避免双重决策
        if (cfg.isGrowthModelEnabled()) {
            report.addNote("field " + field.getId() + ": 生长模型驱动，跳过云端定时评估");
            return;
        }
        Optional<IrrigationJob> running =
                jobRepository.findFirstByFieldIdAndStatusOrderByStartTimeDesc(field.getId(), "RUNNING");
        if (running.isPresent()) {
            report.addNote("field " + field.getId() + " already irrigating (job " + running.get().getId() + ")");
            return;
        }

        // 灌溉时间窗：窗外只等待不告警（轮灌排程会把作业推到下一窗内）
        if (!irrigationWindow.isAllowedNow(field)) {
            report.addNote("field " + field.getId() + ": 当前不在灌溉时间窗内");
            return;
        }

        CropModel crop = cropModelRepository.findById(field.getCropCode()).orElse(null);
        if (crop == null) {
            log.warn("field {} references missing crop model {}", field.getId(), field.getCropCode());
            report.addNote("field " + field.getId() + ": crop model missing");
            return;
        }

        FieldSnapshot snap = snapshotService.build(field, cfg);
        snap.setLatitude(props.getLatitude());

        // 无有效墒情（传感器未上线/数据缺失）不能做灌溉决策，直接跳过等待数据
        if (Double.isNaN(snap.getMoistureAvg())) {
            report.addNote("field " + field.getId() + ": 无有效墒情数据，跳过评估");
            return;
        }

        // 土壤湿度低于策略下限 → 墒情告急（不阻止灌溉，提醒尽快灌水/排查传感器）
        if (cfg.getMoistureLowerPct() != null
                && snap.getMoistureAvg() <= cfg.getMoistureLowerPct().doubleValue()
                && !alarmService.hasUnacknowledged(field.getId(), "WARN", "SOIL_MOISTURE_LOW")) {
            alarmService.raise("WARN", "SOIL_MOISTURE_LOW", field.getValveCode(), field.getId(),
                    "土壤湿度 " + round(snap.getMoistureAvg()) + "% 低于策略下限 "
                            + cfg.getMoistureLowerPct() + "%，存在旱情风险",
                    Map.of("moisture", snap.getMoistureAvg(),
                            "lowerPct", cfg.getMoistureLowerPct().doubleValue()));
        }

        DecisionResult decision = decisionClient.decide(field, cfg, crop, snap);
        report.addDecision(field.getId(), decision);

        switch (decision.getDecision() == null ? "HOLD" : decision.getDecision()) {
            case "IRRIGATE" -> startIrrigation(field, cfg, crop, snap, decision, "AUTO", report);
            case "SKIP" -> log.info("field {} SKIP (rain): {}", field.getId(), decision.getReasons());
            case "FORBID" -> {
                log.warn("field {} FORBID: {}", field.getId(), decision.getReasons());
                alarmService.raise("WARN", "IRRIGATION_FORBIDDEN", field.getValveCode(), field.getId(),
                        "决策禁止开阀: " + String.join("; ", decision.getReasons()), null);
            }
            default -> log.debug("field {} HOLD", field.getId());
        }
    }

    /**
     * Safety preconditions before OPEN (control-logic.md section 4, full set for AUTO):
     * valve online, gateway online, fresh data (<10min), moisture &lt; thetaStart,
     * hardMax not tripped, EC/pH inside window, no unacknowledged CRITICAL alarm,
     * min interval satisfied, no concurrent job on the same field.
     *
     * @param manualHardInterlocksOnly for MANUAL OPEN cloud only blocks
     *                                 hardMax / stale data / CRITICAL / online state
     */
    public List<String> checkOpenPreconditions(FieldEntity field, FieldConfig cfg, FieldSnapshot snap,
                                               DecisionResult decision, boolean manualHardInterlocksOnly) {
        List<String> failures = new ArrayList<>();

        if (!snap.isValveOnline()) {
            failures.add("阀门 " + field.getValveCode() + " 离线");
        }
        if (!snap.isGatewayOnline()) {
            failures.add("网关 " + snap.getGatewaySn() + " 离线，云端不下发");
        }
        if (snapshotService.isStale(snap.getSoilTs(), props.getStalenessSec())) {
            failures.add("湿度数据过期（staleness " + props.getStalenessSec() + "s）");
        }
        if (Double.isNaN(snap.getMoistureAvg())) {
            failures.add("无有效湿度读数");
        }

        double hardMax = nz(cfg.getThetaFc()) + nz(cfg.getHardMaxOffsetPct(), 3d);
        if (!Double.isNaN(snap.getMoistureAvg()) && snap.getMoistureAvg() >= hardMax) {
            failures.add("湿度 " + round(snap.getMoistureAvg()) + "% >= hardMax " + round(hardMax) + "%");
        }

        // 安全联锁（任何模式）：主管道缺水（水压低）/ 施肥泵过载
        if (cfg.getPressureMinKpa() != null && snap.getPressureKpa() != null
                && snap.getPressureKpa() < cfg.getPressureMinKpa().doubleValue()) {
            failures.add("主管道水压 " + round(snap.getPressureKpa()) + "kPa < 下限 "
                    + cfg.getPressureMinKpa() + "kPa（缺水联锁）");
        }
        if (snap.isPumpOverload()
                || (cfg.getPumpOverloadA() != null && snap.getPumpCurrentA() != null
                        && snap.getPumpCurrentA() > cfg.getPumpOverloadA().doubleValue())) {
            failures.add("施肥泵过载（电流 "
                    + (snap.getPumpCurrentA() == null ? "?" : round(snap.getPumpCurrentA()))
                    + "A），联锁禁开");
        }

        if (alarmService.hasUnacknowledgedCritical(field.getId())) {
            failures.add("存在未确认 CRITICAL 告警");
        }

        if (manualHardInterlocksOnly) {
            // MANUAL: edge interlocks do the rest; cloud only enforces the hard blockers above.
            return failures;
        }

        // AUTO-only agronomic / scheduling preconditions
        // 气象联动条件（风速/气温/湿度/降雨）——策略约束，仅自动模式拦截
        failures.addAll(weatherLinkage.evaluate(cfg, snap));

        if (decision.getThetaStart() != null && !Double.isNaN(snap.getMoistureAvg())
                && snap.getMoistureAvg() >= decision.getThetaStart()) {
            failures.add("湿度 " + round(snap.getMoistureAvg())
                    + "% 未低于 thetaStart " + round(decision.getThetaStart()) + "%");
        }
        if (snap.getEc() != null && outOfWindow(snap.getEc(), cfg.getEcMin(), cfg.getEcMax())) {
            failures.add("EC " + snap.getEc() + " 超出窗口 [" + cfg.getEcMin() + "," + cfg.getEcMax() + "]");
        }
        if (snap.getPh() != null && outOfWindow(snap.getPh(), cfg.getPhMin(), cfg.getPhMax())) {
            failures.add("pH " + snap.getPh() + " 超出窗口 [" + cfg.getPhMin() + "," + cfg.getPhMax() + "]");
        }
        if (cfg.getMinIntervalH() != null && snap.getLastIrrigAgoH() != null
                && snap.getLastIrrigAgoH() < cfg.getMinIntervalH().doubleValue()) {
            failures.add("距上次作业 " + round(snap.getLastIrrigAgoH())
                    + "h < minInterval " + cfg.getMinIntervalH() + "h");
        }
        long concurrent = jobRepository.countByFieldIdAndStatus(field.getId(), "RUNNING");
        if (concurrent > 0) {
            failures.add("同 field 已有运行中作业（单阀互斥）");
        }
        return failures;
    }

    /** Create RUNNING job and publish OPEN. Called for both AUTO and (accepted) MANUAL requests. */
    @Transactional
    public IrrigationJob startIrrigation(FieldEntity field, FieldConfig cfg, CropModel crop,
                                         FieldSnapshot snap, DecisionResult decision,
                                         String triggerType, CycleReport report) {
        return startIrrigation(field, cfg, crop, snap, decision, triggerType, report,
                "MANUAL".equals(triggerType));
    }

    /**
     * @param manualHardInterlocksOnly MANUAL OPEN skips agronomic preconditions;
     *                                 AUTO/SCHEDULED enforce the full policy set.
     */
    @Transactional
    public IrrigationJob startIrrigation(FieldEntity field, FieldConfig cfg, CropModel crop,
                                         FieldSnapshot snap, DecisionResult decision,
                                         String triggerType, CycleReport report,
                                         boolean manualHardInterlocksOnly) {
        List<String> failures = checkOpenPreconditions(field, cfg, snap, decision,
                manualHardInterlocksOnly);
        if (!failures.isEmpty()) {
            String reason = String.join("; ", failures);
            log.info("field {} OPEN blocked by safety preconditions: {}", field.getId(), reason);
            if (report != null) {
                report.addNote("field " + field.getId() + " blocked: " + reason);
            }
            if ("AUTO".equals(triggerType)) {
                alarmService.raise("INFO", "IRRIGATION_BLOCKED", field.getValveCode(), field.getId(),
                        "自动开阀被安全前置拦截: " + reason, null);
                return null;
            } else {
                throw new SafetyBlockedException(reason);
            }
        }

        if (decision.getVolumeM3() == null || decision.getVolumeM3() <= 0) {
            log.warn("field {} decision volume invalid: {}", field.getId(), decision.getVolumeM3());
            if (report != null) {
                report.addNote("field " + field.getId() + " non-positive volume, skip");
            }
            return null;
        }

        BigDecimal planned = BigDecimal.valueOf(decision.getVolumeM3()).setScale(3, RoundingMode.HALF_UP);

        IrrigationJob job = new IrrigationJob();
        job.setFieldId(field.getId());
        job.setTriggerType(triggerType);
        job.setStartTime(Instant.now());
        job.setPlannedM3(planned);
        job.setAppliedM3(BigDecimal.ZERO);
        job.setStatus("RUNNING");
        job.setValveCode(field.getValveCode());
        job.setDecision(writeDecisionJson(decision, snap));
        job = jobRepository.save(job);
        job.setJobBizCode(buildBizCode(job));
        job = jobRepository.save(job);

        BigDecimal hardMax = cfg.getThetaFc() == null ? null
                : cfg.getThetaFc().add(cfg.getHardMaxOffsetPct() == null
                        ? new BigDecimal("3") : cfg.getHardMaxOffsetPct());
        String sensorCode = snap.getPrimarySoilSensorCode();
        String gw = snap.getGatewaySn();
        if (gw == null) {
            gw = gatewayOf(job);
        }
        valveCommandService.sendOpen(job, gw, planned, cfg.getMaxDurationSec(), hardMax, sensorCode);
        ledgerService.openForJob(job);
        log.info("field {} irrigation started job {} planned={}m3 mode={}",
                field.getId(), job.getId(), planned, triggerType);
        if (report != null) {
            report.addStarted(job.getId());
        }
        return job;
    }

    // ------------------------------------------------------------------
    // RUNNING supervision: every stop condition from control-logic.md 4
    // ------------------------------------------------------------------

    @Transactional
    public void superviseRunning(IrrigationJob job, CycleReport report) {
        FieldEntity field = fieldRepository.findById(job.getFieldId()).orElse(null);
        FieldConfig cfg = configRepository.findById(job.getFieldId()).orElse(null);
        if (field == null || cfg == null) {
            return;
        }
        FieldSnapshot snap = snapshotService.build(field, cfg);

        // 注肥阶段控制（PRE_WATER 清水 / MID_RUN 注肥 / FLUSH 冲洗）
        applyFertPlan(job, field, snap);

        DecisionThreshold thresholds = readThresholds(job, cfg, field);
        double moisture = snap.getMoistureAvg();
        double hardMax = thresholds.hardMax;

        // stop condition 4: hardMax moisture -> CLOSE + CRITICAL
        if (!Double.isNaN(moisture) && moisture >= hardMax) {
            alarmService.raise("CRITICAL", "INTERLOCK_MOISTURE_HARD_MAX", job.getValveCode(),
                    field.getId(),
                    "运行中湿度 " + round(moisture) + "% 达到硬上限 " + round(hardMax)
                            + "%，云端安全关阀",
                    Map.of("moisture", moisture, "hardMax", hardMax, "jobId", job.getId()));
            requestClose(job, "MOISTURE_HARD_MAX");
            note(report, "job " + job.getId() + " CLOSED: hardMax");
            return;
        }

        // stop condition 4: sensor data stale while running (sensor lost)
        if (snapshotService.isStale(snap.getSoilTs(), props.getStalenessSec())) {
            log.warn("job {} running but soil data stale, safety CLOSE", job.getId());
            alarmService.raise("WARN", "INTERLOCK_SENSOR_LOST", snap.getPrimarySoilSensorCode(),
                    field.getId(), "运行中湿度数据超过 " + props.getStalenessSec()
                            + "s 未更新，云端安全关阀", Map.of("jobId", job.getId()));
            requestClose(job, "INTERLOCK_SENSOR_LOST");
            note(report, "job " + job.getId() + " CLOSED: sensor lost");
            return;
        }

        // gateway offline: cannot command; raise WARN and wait (edge interlock closes locally)
        if (!snap.isGatewayOnline()) {
            log.warn("job {} gateway {} offline; relying on edge interlock", job.getId(), snap.getGatewaySn());
            alarmService.raise("WARN", "GATEWAY_OFFLINE_DURING_JOB", snap.getGatewaySn(), field.getId(),
                    "作业进行中网关闭线，依赖边缘本地联锁关阀", Map.of("jobId", job.getId()));
        }

        // stop condition: fertilizer pump overload -> CLOSE + stop pump + CRITICAL
        if (snap.isPumpOverload()
                || (cfg.getPumpOverloadA() != null && snap.getPumpCurrentA() != null
                        && snap.getPumpCurrentA() > cfg.getPumpOverloadA().doubleValue())) {
            alarmService.raise("CRITICAL", "INTERLOCK_PUMP_OVERLOAD",
                    field.getFertPumpCode(), field.getId(),
                    "施肥泵电机过载（电流 "
                            + (snap.getPumpCurrentA() == null ? "?" : round(snap.getPumpCurrentA()))
                            + "A），安全关阀停泵", Map.of("jobId", job.getId()));
            deviceCommandService.stopPump(field, "PUMP_OVERLOAD");
            requestClose(job, "INTERLOCK_PUMP_OVERLOAD");
            note(report, "job " + job.getId() + " CLOSED: pump overload");
            return;
        }

        // stop condition: main-line water pressure low for longer than waterLostDelaySec
        if (cfg.getPressureMinKpa() != null && snap.getPressureKpa() != null
                && snap.getPressureKpa() < cfg.getPressureMinKpa().doubleValue()) {
            Instant since = pressureLowSince.computeIfAbsent(job.getId(), k -> Instant.now());
            long lowSec = Duration.between(since, Instant.now()).getSeconds();
            if (lowSec >= cfg.getWaterLostDelaySec()) {
                alarmService.raise("CRITICAL", "INTERLOCK_WATER_LOST", snap.getPressureDeviceCode(),
                        field.getId(),
                        "主管道水压 " + round(snap.getPressureKpa()) + "kPa 持续 " + lowSec
                                + "s 低于下限 " + cfg.getPressureMinKpa() + "kPa，判定缺水，安全关阀停泵",
                        Map.of("jobId", job.getId(), "pressureKpa", snap.getPressureKpa(),
                                "thresholdKpa", cfg.getPressureMinKpa().doubleValue()));
                if (field.getFertPumpCode() != null) {
                    deviceCommandService.stopPump(field, "WATER_LOST");
                }
                requestClose(job, "INTERLOCK_WATER_LOST");
                note(report, "job " + job.getId() + " CLOSED: water pressure low");
                return;
            }
        } else {
            pressureLowSince.remove(job.getId());
        }

        // stop condition: valve open but instant flow below minimum (burst/blockage), with grace period
        long runningSec = Duration.between(job.getStartTime(), Instant.now()).getSeconds();
        if (cfg.getFlowMinM3h() != null && snap.getInstantFlow() != null
                && runningSec > FLOW_GRACE_SEC
                && "OPEN".equalsIgnoreCase(snap.getValveState())
                && snap.getInstantFlow() < cfg.getFlowMinM3h().doubleValue()) {
            Instant since = flowLowSince.computeIfAbsent(job.getId(), k -> Instant.now());
            long lowSec = Duration.between(since, Instant.now()).getSeconds();
            if (lowSec >= cfg.getWaterLostDelaySec()) {
                alarmService.raise("CRITICAL", "INTERLOCK_FLOW_LOW", snap.getFlowDeviceCode(),
                        field.getId(),
                        "阀门开启但瞬时流量 " + round(snap.getInstantFlow()) + "m³/h 持续 " + lowSec
                                + "s 低于下限 " + cfg.getFlowMinM3h() + "m³/h（爆管/堵塞/缺水），安全关阀",
                        Map.of("jobId", job.getId(), "instantFlow", snap.getInstantFlow()));
                if (field.getFertPumpCode() != null) {
                    deviceCommandService.stopPump(field, "FLOW_LOW");
                }
                requestClose(job, "INTERLOCK_FLOW_LOW");
                note(report, "job " + job.getId() + " CLOSED: flow low");
                return;
            }
        } else {
            flowLowSince.remove(job.getId());
        }

        // stop condition 1: cumulative flow >= planned volume
        Double applied = latestAppliedVolume(job, snap);
        if (applied != null && job.getPlannedM3() != null
                && applied >= job.getPlannedM3().doubleValue()) {
            log.info("job {} volume reached: {} >= {}", job.getId(), round(applied), job.getPlannedM3());
            requestClose(job, "VOLUME_REACHED");
            note(report, "job " + job.getId() + " CLOSED: volume reached");
            return;
        }

        // stop condition 2: duration limit
        if (cfg.getMaxDurationSec() != null && runningSec >= cfg.getMaxDurationSec()) {
            log.info("job {} duration limit reached ({}s)", job.getId(), runningSec);
            requestClose(job, "DURATION_LIMIT");
            note(report, "job " + job.getId() + " CLOSED: duration limit");
            return;
        }

        // stop condition 3: moisture reached thetaTarget (cloud soft stop)
        if (!Double.isNaN(moisture) && thresholds.thetaTarget != null
                && moisture >= thresholds.thetaTarget) {
            log.info("job {} moisture target reached: {} >= {}", job.getId(),
                    round(moisture), round(thresholds.thetaTarget));
            requestClose(job, "MOISTURE_TARGET");
            note(report, "job " + job.getId() + " CLOSED: moisture target");
            return;
        }

        // valve reports FAULT
        if ("FAULT".equals(snap.getValveState())) {
            job.setStatus("ABORTED");
            job.setStopReason("INTERLOCK_VALVE_FAULT");
            job.setEndTime(Instant.now());
            job.setDurationSec((int) runningSec);
            if (field.getFertPumpCode() != null) {
                deviceCommandService.stopPump(field, "VALVE_FAULT");
            }
            jobRepository.save(job);
            ledgerService.settleForJob(job);
            alarmService.raise("CRITICAL", "INTERLOCK_VALVE_FAULT", job.getValveCode(),
                    field.getId(), "阀门故障，作业中止", Map.of("jobId", job.getId()));
        }
    }

    /** Cloud-initiated CLOSE (idempotent command). Job settles when CLOSED status arrives. */
    @Transactional
    public void requestClose(IrrigationJob job, String reason) {
        // 关阀先停注肥泵（任何云端主动关阀路径）
        FieldEntity field = fieldRepository.findById(job.getFieldId()).orElse(null);
        if (field != null && field.getFertPumpCode() != null) {
            deviceCommandService.stopPump(field, reason);
        }
        // avoid duplicate CLOSE commands: a CLOSE command already ACKed/closing
        Optional<ValveCommand> lastClose =
                commandRepository.findFirstByJobIdAndCommandOrderByCreatedAtDesc(job.getId(), "CLOSE");
        if (lastClose.isPresent()) {
            String st = lastClose.get().getStatus();
            if ("SENT".equals(st) || "CLOSED".equals(st)) {
                log.debug("job {} CLOSE already in-flight/done ({}), skip duplicate", job.getId(), st);
                return;
            }
        }
        valveCommandService.sendClose(job, reason);
    }

    // ------------------------------------------------------------------
    // Fertigation phase control (PRE_WATER / MID_RUN / FLUSH)
    // ------------------------------------------------------------------

    /**
     * 按 field.fertPlan 的 durationFraction 时间比例切换注肥泵；
     * 未配置阶段但 injectRatioPct>0 时整段注肥。仅在阶段迁移时下发一次指令。
     */
    private void applyFertPlan(IrrigationJob job, FieldEntity field, FieldSnapshot snap) {
        if (field.getFertPumpCode() == null || field.getFertPumpCode().isBlank()) {
            return;
        }
        // 阀门未确认 OPEN 前不启泵
        if (!"OPEN".equalsIgnoreCase(snap.getValveState())) {
            return;
        }

        List<FertPhase> phases = parseFertPlan(field.getFertPlan());
        String target;
        double ratioPct;
        if (phases.isEmpty()) {
            ratioPct = injectRatioForJob(job, field);
            target = ratioPct > 0 ? "MID_RUN" : "PRE_WATER";
        } else {
            FertPhase current = phaseAt(phases, elapsedFraction(job));
            target = current.phase;
            ratioPct = current.ratioPct;
        }

        String prev = fertPhaseByJob.get(job.getId());
        if (target.equals(prev)) {
            return;
        }
        fertPhaseByJob.put(job.getId(), target);

        if ("MID_RUN".equals(target) && ratioPct > 0) {
            Integer opening = pumpOpeningForRatio(field, ratioPct, snap);
            deviceCommandService.startPump(field, opening, job.getId());
            log.info("job {} 进入注肥阶段 {} ratio={}% opening={}", job.getId(), target, ratioPct, opening);
        } else {
            deviceCommandService.stopPump(field, "PHASE_" + target);
            log.info("job {} 进入清水阶段 {}", job.getId(), target);
        }
    }

    /** elapsed / planned duration fraction，缺省按 maxDurationSec 估算。 */
    private double elapsedFraction(IrrigationJob job) {
        long elapsed = Duration.between(job.getStartTime(), Instant.now()).getSeconds();
        Long plannedSec = plannedDurationSec(job);
        if (plannedSec == null || plannedSec <= 0) {
            return 0d;
        }
        return Math.min(1d, Math.max(0d, (double) elapsed / plannedSec));
    }

    private Long plannedDurationSec(IrrigationJob job) {
        if (job.getDecision() != null) {
            try {
                com.fasterxml.jackson.databind.JsonNode n = objectMapper.readTree(job.getDecision());
                com.fasterxml.jackson.databind.JsonNode d = n.get("durationSec");
                if (d != null && d.isNumber() && d.asLong() > 0) {
                    return d.asLong();
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return null;
    }

    private FertPhase phaseAt(List<FertPhase> phases, double fraction) {
        double acc = 0;
        for (FertPhase p : phases) {
            acc += Math.max(0d, p.durationFraction);
            if (fraction < acc - 1e-9) {
                return p;
            }
        }
        return phases.get(phases.size() - 1);
    }

    private List<FertPhase> parseFertPlan(String json) {
        List<FertPhase> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode arr = objectMapper.readTree(json);
            if (arr.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode n : arr) {
                    FertPhase p = new FertPhase();
                    p.phase = n.path("phase").asText("");
                    p.ratioPct = n.path("ratioPct").asDouble(0d);
                    p.durationFraction = n.path("durationFraction").asDouble(0d);
                    if (!p.phase.isBlank()) {
                        out.add(p);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("bad fertPlan json: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 注肥泵开度：按灌区总流量 L/h 与泵额定流量 capacityLph 折算
     * opening = 注肥比% × 水流量 / 泵额定流量（缺流量信息时全开，由网关比例注入）。
     */
    private Integer pumpOpeningForRatio(FieldEntity field, double ratioPct, FieldSnapshot snap) {
        double waterLph = field.getEmitterTotalLph() == null
                ? 0d : field.getEmitterTotalLph().doubleValue();
        double capacityLph = deviceCommandService.pumpCapacityLph(field.getFertPumpCode());
        if (waterLph <= 0 || capacityLph <= 0) {
            return 100;
        }
        int opening = (int) Math.round(ratioPct / 100d * waterLph / capacityLph * 100d);
        return Math.max(1, Math.min(100, opening));
    }

    /** 注肥比：生长模型作业决策 JSON 中的覆盖值（fertInjectRatioPct）优先，否则灌区默认。 */
    private double injectRatioForJob(IrrigationJob job, FieldEntity field) {
        if (job.getDecision() != null) {
            try {
                com.fasterxml.jackson.databind.JsonNode n =
                        objectMapper.readTree(job.getDecision()).get("fertInjectRatioPct");
                if (n != null && n.isNumber() && n.asDouble() > 0) {
                    return n.asDouble();
                }
            } catch (Exception ignored) {
                // fall through：用灌区默认
            }
        }
        return nz(field.getInjectRatioPct());
    }

    private static class FertPhase {
        String phase;
        double ratioPct;
        double durationFraction;
    }

    // ------------------------------------------------------------------
    // Telemetry-triggered soft stop checks (stop conditions 1/3/4)
    // ------------------------------------------------------------------

    @EventListener
    @Transactional
    public void onTelemetry(TelemetryArrivedEvent event) {
        if (event.getFieldId() == null) {
            return;
        }
        Optional<IrrigationJob> opt = jobRepository
                .findFirstByFieldIdAndStatusOrderByStartTimeDesc(event.getFieldId(), "RUNNING");
        if (opt.isEmpty()) {
            return;
        }
        IrrigationJob job = opt.get();
        FieldConfig cfg = configRepository.findById(job.getFieldId()).orElse(null);
        if (cfg == null) {
            return;
        }
        double hardMax = nz(cfg.getThetaFc()) + nz(cfg.getHardMaxOffsetPct(), 3d);
        Object mRaw = event.getValues().get("soilMoist");
        if (mRaw == null) {
            mRaw = event.getValues().get("soilMoisture");
        }

        if (mRaw instanceof Number m) {
            double moisture = m.doubleValue();
            if (moisture >= hardMax) {
                alarmService.raise("CRITICAL", "INTERLOCK_MOISTURE_HARD_MAX", event.getDeviceCode(),
                        event.getFieldId(), "实时湿度 " + round(moisture) + "% 达到硬上限 "
                                + round(hardMax) + "%，云端安全关阀",
                        Map.of("moisture", moisture, "hardMax", hardMax, "jobId", job.getId()));
                requestClose(job, "MOISTURE_HARD_MAX");
                return;
            }
            DecisionThreshold t = readThresholds(job, cfg,
                    fieldRepository.findById(job.getFieldId()).orElse(null));
            if (t.thetaTarget != null && moisture >= t.thetaTarget) {
                requestClose(job, "MOISTURE_TARGET");
                return;
            }
        }
        if ("flow".equals(event.getKind())) {
            Object total = event.getValues().get("totalFlow");
            Double baseline = flowBaseline(job);
            if (total instanceof Number tNum && baseline != null && job.getPlannedM3() != null) {
                double applied = tNum.doubleValue() - baseline;
                if (applied >= job.getPlannedM3().doubleValue()) {
                    requestClose(job, "VOLUME_REACHED");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // MANUAL control (REST)
    // ------------------------------------------------------------------

    @Transactional
    public IrrigationJob manualOpen(Long fieldId, Double volumeM3) {
        return forceOpen(fieldId, volumeM3, "MANUAL", true);
    }

    /** 轮灌计划到点释放：受全部策略与安全前置约束；被拦截时抛出原因供调度器标记。 */
    @Transactional
    public IrrigationJob scheduledOpen(Long fieldId, Double volumeM3) {
        return forceOpen(fieldId, volumeM3, "SCHEDULED", false);
    }

    private IrrigationJob forceOpen(Long fieldId, Double volumeM3, String triggerType,
                                    boolean manualHardInterlocksOnly) {
        FieldEntity field = fieldRepository.findById(fieldId)
                .orElseThrow(() -> new IllegalArgumentException("field not found: " + fieldId));
        FieldConfig cfg = configRepository.findById(fieldId)
                .orElseThrow(() -> new IllegalStateException("field config missing: " + fieldId));
        CropModel crop = cropModelRepository.findById(field.getCropCode()).orElse(null);
        FieldSnapshot snap = snapshotService.build(field, cfg);
        snap.setLatitude(props.getLatitude());

        // even in MANUAL, cloud performs an advisory evaluation for thresholds/volume defaults
        DecisionResult decision;
        if (crop != null && !Double.isNaN(snap.getMoistureAvg())) {
            decision = decisionClient.decide(field, cfg, crop, snap);
        } else {
            decision = new DecisionResult();
            decision.setDecision("IRRIGATE");
        }
        if (volumeM3 != null && volumeM3 > 0) {
            decision.setDecision("IRRIGATE");
            decision.setVolumeM3(volumeM3);
            double emitterLph = field.getEmitterTotalLph() == null ? 0
                    : field.getEmitterTotalLph().doubleValue();
            if (emitterLph > 0) {
                decision.setDurationSec((int) Math.round(volumeM3 * 1000d / emitterLph * 3600d));
            }
        }
        if (decision.getVolumeM3() == null || decision.getVolumeM3() <= 0) {
            throw SafetyBlockedException.of(triggerType + " 开阀缺少有效灌量（decision volume 为空，且未指定 volumeM3）");
        }
        return startIrrigation(field, cfg, crop, snap, decision, triggerType, null,
                manualHardInterlocksOnly);
    }

    @Transactional
    public void manualClose(Long fieldId) {
        fieldRepository.findById(fieldId)
                .orElseThrow(() -> new IllegalArgumentException("field not found: " + fieldId));
        IrrigationJob job = jobRepository
                .findFirstByFieldIdAndStatusOrderByStartTimeDesc(fieldId, "RUNNING")
                .orElseThrow(() -> SafetyBlockedException.of("田块 " + fieldId + " 无运行中作业"));
        requestClose(job, "REMOTE_CLOSE");
        log.info("manual CLOSE requested for field {} job {}", fieldId, job.getId());
    }

    public boolean isManualMode(Long fieldId) {
        return configRepository.findById(fieldId)
                .map(c -> "MANUAL".equalsIgnoreCase(c.getMode()))
                .orElse(false);
    }

    /** POST /api/fields/{id}/decision/evaluate — advice only, never actuates. */
    public DecisionResult evaluateAdvisory(Long fieldId) {
        FieldEntity field = fieldRepository.findById(fieldId)
                .orElseThrow(() -> new IllegalArgumentException("field not found: " + fieldId));
        FieldConfig cfg = configRepository.findById(fieldId)
                .orElseThrow(() -> new IllegalStateException("field config missing: " + fieldId));
        CropModel crop = cropModelRepository.findById(field.getCropCode())
                .orElseThrow(() -> new IllegalStateException("crop model missing: " + field.getCropCode()));
        FieldSnapshot snap = snapshotService.build(field, cfg);
        snap.setLatitude(props.getLatitude());
        return decisionClient.decide(field, cfg, crop, snap);
    }

    // ------------------------------------------------------------------
    // 生长模型回调（Python 生长模型服务 → POST /api/growth/callback）
    // ------------------------------------------------------------------

    /**
     * 生长模型融合决策回调：校验田块开关 → 幂等去重 → 组装 DecisionResult
     * （含施肥覆盖参数）→ 走全量安全前置执行（triggerType=MODEL）。
     * 仅需施肥时按水肥一体生成最小清水段作业携带肥液。
     */
    @Transactional
    public Map<String, Object> applyGrowthCallback(GrowthCallbackRequest req) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("decisionId", req.getDecisionId());

        FieldEntity field = req.getFieldId() == null ? null
                : fieldRepository.findById(req.getFieldId()).orElse(null);
        if (field == null) {
            result.put("accepted", false);
            result.put("message", "field not found: " + req.getFieldId());
            return result;
        }
        FieldConfig cfg = configRepository.findById(field.getId()).orElse(null);
        if (cfg == null || !cfg.isGrowthModelEnabled()) {
            result.put("accepted", false);
            result.put("message", "田块未开启生长模型驱动（growthModelEnabled）");
            return result;
        }
        // 幂等：同一 decisionId 只执行一次
        if (req.getDecisionId() != null && !req.getDecisionId().isBlank()) {
            if (processedDecisionIds.size() > 10000) {
                processedDecisionIds.clear();
            }
            if (!processedDecisionIds.add(req.getDecisionId())) {
                result.put("accepted", false);
                result.put("duplicate", true);
                result.put("message", "decisionId 已处理，忽略重复回调");
                return result;
            }
        }

        GrowthCallbackRequest.Irrigation irr = req.getIrrigation();
        GrowthCallbackRequest.Fertigation fert = req.getFertigation();
        boolean wantIrrigate = irr != null && "OPEN".equalsIgnoreCase(irr.getAction())
                && irr.getVolumeM3() != null && irr.getVolumeM3() > 0;
        boolean wantFert = fert != null && Boolean.TRUE.equals(fert.getShouldFertilize())
                && fert.getFertilizerL() != null && fert.getFertilizerL() > 0;

        if (!wantIrrigate && !wantFert) {
            result.put("accepted", true);
            result.put("action", "NONE");
            result.put("message", "决策为保持（不灌溉/不施肥），无需执行");
            return result;
        }

        DecisionResult decision = new DecisionResult();
        decision.setDecision("IRRIGATE");
        if (irr != null && irr.getReasons() != null) {
            decision.getReasons().addAll(irr.getReasons());
        }

        Double fertRatioPct = null;
        if (wantFert) {
            if (field.getFertPumpCode() == null || field.getFertPumpCode().isBlank()) {
                decision.getReasons().add("田块未配置注肥泵，施肥决策不执行");
                wantFert = false;
            } else {
                fertRatioPct = fert.getInjectRatioPct();
                if (fertRatioPct == null || fertRatioPct <= 0) {
                    fertRatioPct = nz(field.getInjectRatioPct()) > 0
                            ? nz(field.getInjectRatioPct()) : 1.0;
                }
                decision.setFertInjectRatioPct(fertRatioPct);
                decision.setFertFertilizerL(fert.getFertilizerL());
                decision.setFertNpkKg(fert.getNpkKg());
                if (fert.getReasons() != null) {
                    decision.getReasons().addAll(fert.getReasons());
                }
            }
        }

        double volumeM3;
        Integer durationSec = null;
        if (wantIrrigate) {
            volumeM3 = irr.getVolumeM3();
            durationSec = irr.getDurationSec();
        } else {
            // 水肥一体：仅需施肥时生成最小清水段携带肥液（注肥泵不能空转）
            volumeM3 = round3(fert.getFertilizerL() / (fertRatioPct / 100d) / 1000d);
            volumeM3 = Math.max(0.05, volumeM3);
            decision.getReasons().add(String.format(
                    "仅需施肥：水肥一体作业，清水 %.3fm³ 携带肥液 %.2fL（注肥比 %.2f%%）",
                    volumeM3, fert.getFertilizerL(), fertRatioPct));
        }
        if (durationSec == null || durationSec <= 0) {
            double emitterLph = field.getEmitterTotalLph() == null
                    ? 0d : field.getEmitterTotalLph().doubleValue();
            if (emitterLph > 0) {
                durationSec = (int) Math.round(volumeM3 * 1000d / emitterLph * 3600d);
            }
        }
        decision.setVolumeM3(volumeM3);
        decision.setDurationSec(durationSec);

        CropModel crop = cropModelRepository.findById(field.getCropCode()).orElse(null);
        FieldSnapshot snap = snapshotService.build(field, cfg);
        snap.setLatitude(props.getLatitude());

        try {
            IrrigationJob job = startIrrigation(field, cfg, crop, snap, decision,
                    "MODEL", null, false);
            if (job == null) {
                result.put("accepted", false);
                result.put("message", "灌量无效或被安全前置拦截");
                return result;
            }
            result.put("accepted", true);
            result.put("action", "IRRIGATE");
            result.put("jobId", job.getId());
            result.put("jobBizCode", job.getJobBizCode());
            result.put("fertigation", wantFert);
            log.info("growth callback accepted: field {} job {} decisionId={} fert={}",
                    field.getId(), job.getId(), req.getDecisionId(), wantFert);
            return result;
        } catch (SafetyBlockedException e) {
            alarmService.raise("INFO", "GROWTH_CALLBACK_BLOCKED", field.getValveCode(),
                    field.getId(), "生长模型回调被安全前置拦截: " + e.getMessage(), null);
            result.put("accepted", false);
            result.put("message", "安全前置拦截: " + e.getMessage());
            return result;
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private String gatewayOf(IrrigationJob job) {
        if (job.getValveCode() == null) {
            return null;
        }
        // resolve via field -> valve device gateway
        FieldEntity f = fieldRepository.findById(job.getFieldId()).orElse(null);
        if (f == null) {
            return null;
        }
        return valveGateway(f.getValveCode());
    }

    private String valveGateway(String valveCode) {
        // DeviceRepository is not injected here to keep constructor compact;
        // use SnapshotService's cached resolution through a lightweight lookup
        return snapshotService.gatewaySnOf(valveCode);
    }

    private Double latestAppliedVolume(IrrigationJob job, FieldSnapshot snap) {
        // 1) valve-reported per-job applied volume (reset by gateway on each OPEN)
        if (snap.getValveAppliedVolume() != null) {
            return snap.getValveAppliedVolume();
        }
        // 2) flow meter cumulative total minus the baseline snapshotted at OPEN
        if (snap.getFlowDeviceCode() != null && snap.getTotalFlow() != null) {
            Double baseline = flowBaseline(job);
            if (baseline != null) {
                double v = snap.getTotalFlow() - baseline;
                if (v >= 0) {
                    return v;
                }
            }
        }
        return null;
    }

    private Double flowBaseline(IrrigationJob job) {
        if (job.getDecision() == null) {
            return null;
        }
        try {
            JsonNodeHint hint = readHint(job.getDecision());
            return hint.flowBaseline;
        } catch (Exception e) {
            return null;
        }
    }

    private DecisionThreshold readThresholds(IrrigationJob job, FieldConfig cfg, FieldEntity field) {
        DecisionThreshold t = new DecisionThreshold();
        t.hardMax = nz(cfg.getThetaFc()) + nz(cfg.getHardMaxOffsetPct(), 3d);
        t.thetaTarget = null;
        if (job.getDecision() != null) {
            try {
                JsonNodeHint hint = readHint(job.getDecision());
                t.thetaTarget = hint.thetaTarget;
            } catch (Exception ignored) {
                // fall through
            }
        }
        if (t.thetaTarget == null && field != null) {
            CropModel crop = cropModelRepository.findById(field.getCropCode()).orElse(null);
            if (crop != null && cfg.getThetaFc() != null) {
                t.thetaTarget = cfg.getThetaFc().doubleValue() - 1.0;
            }
        }
        return t;
    }

    private JsonNodeHint readHint(String json) throws Exception {
        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(json);
        JsonNodeHint h = new JsonNodeHint();
        com.fasterxml.jackson.databind.JsonNode target = node.get("thetaTarget");
        if (target != null && target.isNumber()) {
            h.thetaTarget = target.asDouble();
        }
        com.fasterxml.jackson.databind.JsonNode base = node.get("flowBaseline");
        if (base != null && base.isNumber()) {
            h.flowBaseline = base.asDouble();
        }
        return h;
    }

    private String writeDecisionJson(DecisionResult decision, FieldSnapshot snap) {
        try {
            Map<String, Object> all = objectMapper.convertValue(decision,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            if (snap.getTotalFlow() != null) {
                all.put("flowBaseline", snap.getTotalFlow());
            }
            return objectMapper.writeValueAsString(all);
        } catch (Exception e) {
            log.warn("serialize decision failed: {}", e.getMessage());
            return "{}";
        }
    }

    private String buildBizCode(IrrigationJob job) {
        return "J" + BIZ_FMT.format(job.getStartTime()) + "F" + job.getFieldId() + "-" + job.getId();
    }

    private static boolean outOfWindow(double v, BigDecimal lo, BigDecimal hi) {
        return (lo != null && v < lo.doubleValue()) || (hi != null && v > hi.doubleValue());
    }

    private static double nz(BigDecimal v) {
        return v == null ? 0d : v.doubleValue();
    }

    private static double nz(BigDecimal v, double dflt) {
        return v == null ? dflt : v.doubleValue();
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static double round3(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }

    private static void note(CycleReport r, String s) {
        if (r != null) {
            r.addNote(s);
        }
    }

    private static class DecisionThreshold {
        double hardMax;
        Double thetaTarget;
    }

    private static class JsonNodeHint {
        Double thetaTarget;
        Double flowBaseline;
    }
}
