package com.farm.irrigation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 分区轮灌计划：按灌区、时间、优先级生成的一组顺序灌溉项。 */
@Entity
@Table(name = "rotation_plan")
public class RotationPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    /** DRAFT / SCHEDULED / RUNNING / DONE / CANCELLED */
    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    @Column(name = "plan_date")
    private LocalDate planDate;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    /** AUTO / MANUAL */
    @Column(name = "generated_by", nullable = false, length = 64)
    private String generatedBy = "AUTO";

    @Column(length = 256)
    private String note;

    @Column(name = "total_planned_m3")
    private BigDecimal totalPlannedM3 = BigDecimal.ZERO;

    @Column(name = "total_applied_m3")
    private BigDecimal totalAppliedM3 = BigDecimal.ZERO;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDate getPlanDate() { return planDate; }
    public void setPlanDate(LocalDate planDate) { this.planDate = planDate; }
    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant generatedAt) { this.generatedAt = generatedAt; }
    public String getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(String generatedBy) { this.generatedBy = generatedBy; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public BigDecimal getTotalPlannedM3() { return totalPlannedM3; }
    public void setTotalPlannedM3(BigDecimal totalPlannedM3) { this.totalPlannedM3 = totalPlannedM3; }
    public BigDecimal getTotalAppliedM3() { return totalAppliedM3; }
    public void setTotalAppliedM3(BigDecimal totalAppliedM3) { this.totalAppliedM3 = totalAppliedM3; }
}
