package com.farm.irrigation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** 轮灌计划项：单个灌区的一次计划灌溉。 */
@Entity
@Table(name = "rotation_plan_item")
public class RotationPlanItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "field_id", nullable = false)
    private Long fieldId;

    @Column(nullable = false)
    private Integer seq;

    @Column(nullable = false)
    private Integer priority = 100;

    @Column(name = "scheduled_start")
    private Instant scheduledStart;

    @Column(name = "planned_volume_m3", precision = 10, scale = 3)
    private BigDecimal plannedVolumeM3;

    @Column(name = "job_id")
    private Long jobId;

    /** PENDING / RELEASED / DONE / SKIPPED / BLOCKED */
    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "skip_reason", length = 128)
    private String skipReason;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }
    public Long getFieldId() { return fieldId; }
    public void setFieldId(Long fieldId) { this.fieldId = fieldId; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public Instant getScheduledStart() { return scheduledStart; }
    public void setScheduledStart(Instant scheduledStart) { this.scheduledStart = scheduledStart; }
    public BigDecimal getPlannedVolumeM3() { return plannedVolumeM3; }
    public void setPlannedVolumeM3(BigDecimal plannedVolumeM3) { this.plannedVolumeM3 = plannedVolumeM3; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String skipReason) { this.skipReason = skipReason; }
    public Instant getCreatedAt() { return createdAt; }
}
