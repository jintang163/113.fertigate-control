package com.farm.irrigation.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.config.ControlProperties;
import com.farm.irrigation.domain.FieldConfig;
import com.farm.irrigation.domain.FieldEntity;
import com.farm.irrigation.domain.IrrigationJob;
import com.farm.irrigation.dto.GrowthCallbackRequest;
import com.farm.irrigation.repo.CropModelRepository;
import com.farm.irrigation.repo.FieldConfigRepository;
import com.farm.irrigation.repo.FieldRepository;
import com.farm.irrigation.repo.IrrigationJobRepository;
import com.farm.irrigation.repo.ValveCommandRepository;
import com.farm.irrigation.service.AlarmService;
import com.farm.irrigation.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生长模型回调测试：开关校验 / 幂等 / 灌溉+施肥执行 / 水肥一体最小清水段。
 * 不启动 Spring 容器，仓储全部 Mock。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GrowthCallbackTest {

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
    @Mock private LedgerService ledgerService;

    private ControlEngine engine;
    private FieldEntity field;
    private FieldConfig cfg;
    private FieldSnapshot snap;

    @BeforeEach
    void setUp() {
        engine = new ControlEngine(fieldRepository, configRepository, cropModelRepository,
                jobRepository, commandRepository, snapshotService, decisionClient,
                valveCommandService, deviceCommandService, alarmService, ledgerService,
                new ControlProperties(), new ObjectMapper(),
                new WeatherLinkageService(), new IrrigationWindow(new ControlProperties()));

        field = new FieldEntity();
        field.setId(1L);
        field.setName("1号棚");
        field.setCropCode("tomato");
        field.setValveCode("V1");
        field.setFertPumpCode("FP1");
        field.setAreaM2(new BigDecimal("2000"));
        field.setEmitterTotalLph(new BigDecimal("2400"));
        field.setInjectRatioPct(new BigDecimal("1.0"));

        cfg = new FieldConfig();
        cfg.setFieldId(1L);
        cfg.setThetaFc(new BigDecimal("30"));
        cfg.setHardMaxOffsetPct(new BigDecimal("3"));
        cfg.setGrowthModelEnabled(true);

        snap = new FieldSnapshot();
        snap.setValveOnline(true);
        snap.setGatewayOnline(true);
        snap.setGatewaySn("GW1");
        snap.setSoilTs(Instant.now());
        snap.setMoistureAvg(18d);

        lenient().when(fieldRepository.findById(1L)).thenReturn(Optional.of(field));
        lenient().when(configRepository.findById(1L)).thenReturn(Optional.of(cfg));
        lenient().when(snapshotService.build(field, cfg)).thenReturn(snap);
        lenient().when(cropModelRepository.findById(any())).thenReturn(Optional.empty());
        lenient().when(snapshotService.isStale(any(Instant.class), anyInt())).thenReturn(false);
        lenient().when(alarmService.hasUnacknowledgedCritical(eq(1L))).thenReturn(false);
        lenient().when(jobRepository.countByFieldIdAndStatus(eq(1L), eq("RUNNING"))).thenReturn(0L);
        lenient().when(jobRepository.save(any(IrrigationJob.class))).thenAnswer(inv -> {
            IrrigationJob j = inv.getArgument(0);
            if (j.getId() == null) {
                j.setId(99L);
            }
            return j;
        });
    }

    private GrowthCallbackRequest req(String decisionId, String action, Double volumeM3,
                                      Boolean shouldFert, Double fertL, Double ratioPct) {
        GrowthCallbackRequest req = new GrowthCallbackRequest();
        req.setDecisionId(decisionId);
        req.setFieldId(1L);
        GrowthCallbackRequest.Irrigation irr = new GrowthCallbackRequest.Irrigation();
        irr.setAction(action);
        irr.setVolumeM3(volumeM3);
        irr.setDurationSec(900);
        req.setIrrigation(irr);
        GrowthCallbackRequest.Fertigation fert = new GrowthCallbackRequest.Fertigation();
        fert.setShouldFertilize(shouldFert);
        fert.setFertilizerL(fertL);
        fert.setInjectRatioPct(ratioPct);
        fert.setNpkKg(Map.of("n", 1.2, "p", 0.36, "k", 1.8));
        req.setFertigation(fert);
        return req;
    }

    @Test
    void rejectedWhenGrowthModelDisabled() {
        cfg.setGrowthModelEnabled(false);
        Map<String, Object> r = engine.applyGrowthCallback(req("d-0", "OPEN", 0.6, true, 11.2, 0.56));
        assertThat(r.get("accepted")).isEqualTo(false);
        verify(jobRepository, never()).save(any());
    }

    @Test
    void executesIrrigationWithFertOverride() {
        Map<String, Object> r = engine.applyGrowthCallback(req("d-1", "OPEN", 0.6, true, 11.2, 0.56));

        assertThat(r.get("accepted")).isEqualTo(true);
        assertThat(r.get("jobId")).isEqualTo(99L);
        assertThat(r.get("fertigation")).isEqualTo(true);

        ArgumentCaptor<IrrigationJob> captor = ArgumentCaptor.forClass(IrrigationJob.class);
        verify(jobRepository, atLeastOnce()).save(captor.capture());
        IrrigationJob saved = captor.getValue();
        assertThat(saved.getTriggerType()).isEqualTo("MODEL");
        assertThat(saved.getPlannedM3()).isEqualByComparingTo(new BigDecimal("0.600"));
        // 作业决策 JSON 携带施肥覆盖参数（注肥阶段与台账据此取值）
        assertThat(saved.getDecision()).contains("fertInjectRatioPct");
        assertThat(saved.getDecision()).contains("0.56");
        verify(valveCommandService, atLeastOnce()).sendOpen(any(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateDecisionIdIgnored() {
        Map<String, Object> first = engine.applyGrowthCallback(req("d-2", "OPEN", 0.6, false, null, null));
        assertThat(first.get("accepted")).isEqualTo(true);

        Map<String, Object> second = engine.applyGrowthCallback(req("d-2", "OPEN", 0.6, false, null, null));
        assertThat(second.get("accepted")).isEqualTo(false);
        assertThat(second.get("duplicate")).isEqualTo(true);
    }

    @Test
    void noneWhenNoAction() {
        Map<String, Object> r = engine.applyGrowthCallback(req("d-3", "CLOSED", 0.0, false, null, null));
        assertThat(r.get("accepted")).isEqualTo(true);
        assertThat(r.get("action")).isEqualTo("NONE");
        verify(jobRepository, never()).save(any());
    }

    @Test
    void fertOnlyGeneratesCarrierWaterJob() {
        // 仅需施肥：肥液 10L @ 2% → 清水 10/0.02/1000 = 0.5m³ 携带
        Map<String, Object> r = engine.applyGrowthCallback(req("d-4", "CLOSED", 0.0, true, 10.0, 2.0));

        assertThat(r.get("accepted")).isEqualTo(true);
        assertThat(r.get("action")).isEqualTo("IRRIGATE");
        ArgumentCaptor<IrrigationJob> captor = ArgumentCaptor.forClass(IrrigationJob.class);
        verify(jobRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getPlannedM3()).isEqualByComparingTo(new BigDecimal("0.500"));
    }

    @Test
    void fertSkippedWhenNoPump() {
        field.setFertPumpCode(null);
        Map<String, Object> r = engine.applyGrowthCallback(req("d-5", "OPEN", 0.6, true, 11.2, 0.56));
        // 无注肥泵：灌溉照常执行，施肥部分跳过
        assertThat(r.get("accepted")).isEqualTo(true);
        assertThat(r.get("fertigation")).isEqualTo(false);
    }
}
