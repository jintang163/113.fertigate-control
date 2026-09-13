package com.farm.irrigation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "field_t")
public class FieldEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "crop_code", nullable = false)
    private String cropCode;

    /** 作物品种（具体栽培品种，区别于作物类型 cropCode） */
    @Column(name = "crop_variety")
    private String cropVariety;

    @Column(name = "area_m2", nullable = false)
    private java.math.BigDecimal areaM2;

    /** DRIP / SPRINKLER */
    @Column(name = "irrigation_mode", nullable = false)
    private String irrigationMode = "DRIP";

    /** 轮灌优先级（数字越小越优先） */
    @Column(nullable = false)
    private Integer priority = 100;

    @Column(name = "emitter_total_lph")
    private java.math.BigDecimal emitterTotalLph;

    @Column(name = "valve_code")
    private String valveCode;

    /** 比例注肥泵设备编码 */
    @Column(name = "fert_pump_code")
    private String fertPumpCode;

    /** 注肥比例 %（肥液占灌溉水的体积百分比） */
    @Column(name = "inject_ratio_pct", nullable = false)
    private java.math.BigDecimal injectRatioPct = java.math.BigDecimal.ZERO;

    /** 允许灌溉时间窗（一天中的分钟数，本地时区；null 不限制） */
    @Column(name = "window_start_min")
    private Short windowStartMin;

    @Column(name = "window_end_min")
    private Short windowEndMin;

    /** 周允许位图，周一..周日，"1111111" 全允许 */
    @Column(name = "window_days")
    private String windowDays = "1111111";

    /** 注肥阶段 jsonb 数组：PRE_WATER / MID_RUN / FLUSH */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fert_plan", columnDefinition = "jsonb")
    private String fertPlan = "[]";

    @Column(name = "sowing_date")
    private LocalDate sowingDate;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCropCode() { return cropCode; }
    public void setCropCode(String cropCode) { this.cropCode = cropCode; }
    public String getCropVariety() { return cropVariety; }
    public void setCropVariety(String cropVariety) { this.cropVariety = cropVariety; }
    public java.math.BigDecimal getAreaM2() { return areaM2; }
    public void setAreaM2(java.math.BigDecimal areaM2) { this.areaM2 = areaM2; }
    public String getIrrigationMode() { return irrigationMode; }
    public void setIrrigationMode(String irrigationMode) { this.irrigationMode = irrigationMode; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public java.math.BigDecimal getEmitterTotalLph() { return emitterTotalLph; }
    public void setEmitterTotalLph(java.math.BigDecimal emitterTotalLph) { this.emitterTotalLph = emitterTotalLph; }
    public String getValveCode() { return valveCode; }
    public void setValveCode(String valveCode) { this.valveCode = valveCode; }
    public String getFertPumpCode() { return fertPumpCode; }
    public void setFertPumpCode(String fertPumpCode) { this.fertPumpCode = fertPumpCode; }
    public java.math.BigDecimal getInjectRatioPct() { return injectRatioPct; }
    public void setInjectRatioPct(java.math.BigDecimal injectRatioPct) { this.injectRatioPct = injectRatioPct; }
    public Short getWindowStartMin() { return windowStartMin; }
    public void setWindowStartMin(Short windowStartMin) { this.windowStartMin = windowStartMin; }
    public Short getWindowEndMin() { return windowEndMin; }
    public void setWindowEndMin(Short windowEndMin) { this.windowEndMin = windowEndMin; }
    public String getWindowDays() { return windowDays; }
    public void setWindowDays(String windowDays) { this.windowDays = windowDays; }
    public String getFertPlan() { return fertPlan; }
    public void setFertPlan(String fertPlan) { this.fertPlan = fertPlan; }
    public LocalDate getSowingDate() { return sowingDate; }
    public void setSowingDate(LocalDate sowingDate) { this.sowingDate = sowingDate; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
