package com.farm.irrigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.domain.IrrigationJob;
import com.farm.irrigation.repo.IrrigationJobRepository;
import com.farm.irrigation.repo.SensorLatestRepository;
import com.farm.irrigation.repo.ValveCommandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 最小回归测试：{@link ValveStatusService} 收到 CLOSED 状态后对作业水量的结算
 * （内部走 applyVolume：阀门上报 appliedVolume 优先，为 0 时回退 totalFlow，
 * 二者都为 0 时不改写灌量，金额保留 3 位小数 HALF_UP）。
 */
@ExtendWith(MockitoExtension.class)
class ValveStatusServiceTest {

    @Mock private IrrigationJobRepository jobRepository;
    @Mock private ValveCommandRepository commandRepository;
    @Mock private SensorLatestRepository sensorLatestRepository;
    @Mock private DeviceService deviceService;
    @Mock private InfluxService influx;
    @Mock private LedgerService ledgerService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ValveStatusService service;

    @BeforeEach
    void setUp() {
        service = new ValveStatusService(jobRepository, commandRepository,
                sensorLatestRepository, deviceService, influx, objectMapper, ledgerService);
    }

    private IrrigationJob runningJob() {
        IrrigationJob job = new IrrigationJob();
        job.setId(42L);
        job.setFieldId(1L);
        job.setValveCode("V1");
        job.setStatus("RUNNING");
        job.setStartTime(Instant.now().minusSeconds(300));
        return job;
    }

    private void handleClosed(IrrigationJob job, double appliedVolume, double totalFlow) throws Exception {
        JsonNode msg = objectMapper.readTree(
                "{\"valveCode\":\"V1\",\"state\":\"CLOSED\","
                        + "\"appliedVolume\":" + appliedVolume + ","
                        + "\"totalFlow\":" + totalFlow + "}");
        when(jobRepository.findByStatusOrderByStartTimeDesc("RUNNING"))
                .thenReturn(List.of(job));
        service.handle("GW1", msg);
    }

    /** 阀门上报了本次作业灌量：直接采用，保留 3 位小数（HALF_UP，0.6005 -> 0.601）。 */
    @Test
    void closedWithReportedAppliedVolume_settledFromAppliedVolume() throws Exception {
        IrrigationJob job = runningJob();
        handleClosed(job, 0.6005d, 99.9d);

        assertThat(job.getStatus()).isEqualTo("DONE");
        assertThat(job.getAppliedM3()).isEqualByComparingTo(new BigDecimal("0.601"));
        assertThat(job.getEndTime()).isNotNull();
        assertThat(job.getDurationSec()).isNotNull();
    }

    /** 阀门未上报灌量（0）：回退用流量计累计 totalFlow 结算。 */
    @Test
    void closedWithoutAppliedVolume_fallsBackToTotalFlow() throws Exception {
        IrrigationJob job = runningJob();
        handleClosed(job, 0d, 1.25d);

        assertThat(job.getStatus()).isEqualTo("DONE");
        assertThat(job.getAppliedM3()).isEqualByComparingTo(new BigDecimal("1.250"));
    }

    /** 两个读数都为 0：不改写灌量字段（保持原值，这里为 null），但作业仍正常结算为 DONE。 */
    @Test
    void closedWithNoVolumeLeavesAppliedUntouched() throws Exception {
        IrrigationJob job = runningJob();
        handleClosed(job, 0d, 0d);

        assertThat(job.getStatus()).isEqualTo("DONE");
        // applyVolume 对 v<=0 不做 setAppliedM3，保留实体默认值 ZERO
        assertThat(job.getAppliedM3()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
