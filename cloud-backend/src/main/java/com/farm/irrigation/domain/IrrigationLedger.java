package com.farm.irrigation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 灌肥台账：每次灌水/施肥一条（WATER）或一组（FERTIGATION 注肥段）。
 * 记录起止时间、水方量、肥液量/折纯量、执行方式（AUTO/MANUAL/SCHEDULED/SAFETY）。
 */
@Entity
@Table(name = "irrigation_ledger")
public class IrrigationLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "field_id", nullable = false)
    private Long fieldId;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "plan_item_id")
    private Long planItemId;

    /** WATER / FERTIGATION */
    @Column(nullable = false, length = 16)
    private String kind;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "water_m3", nullable = false, precision = 10, scale = 3)
    private BigDecimal waterM3 = BigDecimal.ZERO;

    /** 折纯养分量 kg（按肥液浓度折算，可空） */
    @Column(name = "fertilizer_kg", nullable = false, precision = 10, scale = 3)
    private BigDecimal fertilizerKg = BigDecimal.ZERO;

    /** 肥液量 L */
    @Column(name = "fertilizer_l", nullable = false, precision = 10, scale = 3)
    private BigDecimal fertilizerL = BigDecimal.ZERO;

    @Column(name = "fertilizer_name", length = 64)
    private String fertilizerName;

    @Column(name = "inject_ratio_pct", precision = 6, scale = 3)
    private BigDecimal injectRatioPct;

    /** AUTO / MANUAL / SCHEDULED / SAFETY */
    @Column(name = "execution_mode", nullable = false, length = 16)
    private String executionMode;

    @Column(name = "stop_reason", length = 48)
    private String stopReason;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFieldId() { return fieldId; }
    public void setFieldId(Long fieldId) { this.fieldId = fieldId; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public Long getPlanItemId() { return planItemId; }
    public void setPlanItemId(Long planItemId) { this.planItemId = planItemId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }
    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }
    public BigDecimal getWaterM3() { return waterM3; }
    public void setWaterM3(BigDecimal waterM3) { this.waterM3 = waterM3; }
    public BigDecimal getFertilizerKg() { return fertilizerKg; }
    public void setFertilizerKg(BigDecimal fertilizerKg) { this.fertilizerKg = fertilizerKg; }
    public BigDecimal getFertilizerL() { return fertilizerL; }
    public void setFertilizerL(BigDecimal fertilizerL) { this.fertilizerL = fertilizerL; }
    public String getFertilizerName() { return fertilizerName; }
    public void setFertilizerName(String fertilizerName) { this.fertilizerName = fertilizerName; }
    public BigDecimal getInjectRatioPct() { return injectRatioPct; }
    public void setInjectRatioPct(BigDecimal injectRatioPct) { this.injectRatioPct = injectRatioPct; }
    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String executionMode) { this.executionMode = executionMode; }
    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }
    public Instant getCreatedAt() { return createdAt; }
}
