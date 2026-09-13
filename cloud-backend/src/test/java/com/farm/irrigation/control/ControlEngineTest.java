package com.farm.irrigation.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.config.ControlProperties;
import com.farm.irrigation.domain.FieldConfig;
import com.farm.irrigation.domain.FieldEntity;
import com.farm.irrigation.repo.CropModelRepository;
import com.farm.irrigation.repo.FieldConfigRepository;
import com.farm.irrigation.repo.FieldRepository;
import com.farm.irrigation.repo.IrrigationJobRepository;
import com.farm.irrigation.repo.ValveCommandRepository;
import com.farm.irrigation.service.AlarmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 最小回归测试：仅覆盖 {@link ControlEngine#checkOpenPreconditions} 的四类安全拦截分支
 * （阀门/网关离线、湿度数据过期、湿度未低于 thetaStart、存在未确认 CRITICAL 告警），
 * 不启动 Spring 容器，仓储全部 Mock。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ControlEngineTest {

    @Mock private FieldRepository fieldRepository;
    @Mock private FieldConfigRepository configRepository;
    @Mock private CropModelRepository cropModelRepository;
    @Mock private IrrigationJobRepository jobRepository;
    @Mock private ValveCommandRepository commandRepository;
    @Mock private SnapshotService snapshotService;
    @Mock private DecisionClient decisionClient;
    @Mock private ValveCommandService valveCommandService;
    @Mock private DeviceCommandService deviceCommandService;
    @Mock private AlarmService alarmService;
    @Mock private com.farm.irrigation.service.LedgerService ledgerService;

    private ControlProperties props;
    private ControlEngine engine;

    private FieldEntity field;
    private FieldConfig cfg;
    private FieldSnapshot snap;
    private DecisionResult decision;

    @BeforeEach
    void setUp() {
        props = new ControlProperties();
        engine = new ControlEngine(fieldRepository, configRepository, cropModelRepository,
                jobRepository, commandRepository, snapshotService, decisionClient,
                valveCommandService, deviceCommandService, alarmService, ledgerService,
                props, new ObjectMapper(),
                new WeatherLinkageService(), new IrrigationWindow(props));

        field = new FieldEntity();
        field.setId(1L);
        field.setValveCode("V1");

        cfg = new FieldConfig();
        cfg.setFieldId(1L);
        cfg.setThetaFc(new BigDecimal("30"));
        cfg.setHardMaxOffsetPct(new BigDecimal("3"));

        snap = new FieldSnapshot();
        snap.setValveOnline(true);
        snap.setGatewayOnline(true);
        snap.setGatewaySn("GW1");
        snap.setSoilTs(Instant.now());
        snap.setMoistureAvg(18d);

        decision = new DecisionResult();
        decision.setDecision("IRRIGATE");
        decision.setThetaStart(20d);

        // 默认：数据新鲜、无未确认 CRITICAL、无并发作业
        lenient().when(snapshotService.isStale(any(Instant.class), anyInt())).thenReturn(false);
        lenient().when(alarmService.hasUnacknowledgedCritical(eq(1L))).thenReturn(false);
        lenient().when(jobRepository.countByFieldIdAndStatus(eq(1L), eq("RUNNING"))).thenReturn(0L);
    }

    /** 基线：全部前置满足时不应有拦截项（保证后续失败分支确实由对应条件触发）。 */
    @Test
    void allPreconditionsSatisfied_noFailures() {
        List<String> failures = engine.checkOpenPreconditions(field, cfg, snap, decision, false);
        assertThat(failures).isEmpty();
    }

    /** 分支 1：阀门离线必须拦截（网关离线同走在线状态分支，这里一并验证）。 */
    @Test
    void valveOrGatewayOffline_blocked() {
        snap.setValveOnline(false);
        List<String> failures = engine.checkOpenPreconditions(field, cfg, snap, decision, false);
        assertThat(failures).anyMatch(f -> f.contains("V1") && f.contains("离线"));

        snap.setValveOnline(true);
        snap.setGatewayOnline(false);
        failures = engine.checkOpenPreconditions(field, cfg, snap, decision, false);
        assertThat(failures).anyMatch(f -> f.contains("GW1") && f.contains("离线"));
    }

    /** 分支 2：湿度数据超过 stalenessSec 未更新必须拦截。 */
    @Test
    void staleSoilData_blocked() {
        when(snapshotService.isStale(any(Instant.class), eq(props.getStalenessSec()))).thenReturn(true);
        List<String> failures = engine.checkOpenPreconditions(field, cfg, snap, decision, false);
        assertThat(failures).anyMatch(f -> f.contains("湿度数据过期")
                && f.contains("staleness " + props.getStalenessSec()));
    }

    /** 分支 3：AUTO 模式下湿度未低于决策的 thetaStart 必须拦截。 */
    @Test
    void moistureAtOrAboveThetaStart_blocked() {
        // hardMax = thetaFc 30 + offset 3 = 33，取 22.5 只触发 thetaStart（20）而不触发 hardMax
        snap.setMoistureAvg(22.5d);
        List<String> failures = engine.checkOpenPreconditions(field, cfg, snap, decision, false);
        assertThat(failures).anyMatch(f -> f.contains("thetaStart"));
        assertThat(failures).noneMatch(f -> f.contains("hardMax"));
    }

    /** 分支 4：存在未确认 CRITICAL 告警必须拦截（MANUAL 模式的硬联锁同样生效）。 */
    @Test
    void unacknowledgedCritical_blocked() {
        when(alarmService.hasUnacknowledgedCritical(1L)).thenReturn(true);
        List<String> failures = engine.checkOpenPreconditions(field, cfg, snap, decision, true);
        assertThat(failures).anyMatch(f -> f.contains("未确认 CRITICAL"));
    }
}
