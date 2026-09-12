package com.farm.irrigation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "irrigation_job")
public class IrrigationJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "field_id", nullable = false)
    private Long fieldId;

    /** AUTO / MANUAL / SAFETY_OFF */
    @Column(name = "trigger_type", nullable = false, length = 16)
    private String triggerType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String decision;

    @Column(name = "start_time", nullable = false)
    private Instant startTime = Instant.now();

    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "planned_m3", precision = 10, scale = 3)
    private BigDecimal plannedM3;

    @Column(name = "applied_m3", precision = 10, scale = 3)
    private BigDecimal appliedM3 = BigDecimal.ZERO;

    @Column(name = "duration_sec")
    private Integer durationSec;

    /** PLANNED / RUNNING / DONE / ABORTED */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "stop_reason", length = 48)
    private String stopReason;

    @Column(name = "valve_code")
    private String valveCode;

    @Column(name = "job_biz_code", length = 40)
    private String jobBizCode;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFieldId() { return fieldId; }
    public void setFieldId(Long fieldId) { this.fieldId = fieldId; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }
    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }
    public BigDecimal getPlannedM3() { return plannedM3; }
    public void setPlannedM3(BigDecimal plannedM3) { this.plannedM3 = plannedM3; }
    public BigDecimal getAppliedM3() { return appliedM3; }
    public void setAppliedM3(BigDecimal appliedM3) { this.appliedM3 = appliedM3; }
    public Integer getDurationSec() { return durationSec; }
    public void setDurationSec(Integer durationSec) { this.durationSec = durationSec; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStopReason() { return stopReason; }
    public void setStopReason(String stopReason) { this.stopReason = stopReason; }
    public String getValveCode() { return valveCode; }
    public void setValveCode(String valveCode) { this.valveCode = valveCode; }
    public String getJobBizCode() { return jobBizCode; }
    public void setJobBizCode(String jobBizCode) { this.jobBizCode = jobBizCode; }
}
