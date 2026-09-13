package com.farm.irrigation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class FieldDto {

    private Long id;
    @NotBlank
    private String name;
    @NotBlank
    private String cropCode;
    /** 作物品种（具体栽培品种名） */
    private String cropVariety;
    @NotNull
    private BigDecimal areaM2;
    private String irrigationMode;
    private BigDecimal emitterTotalLph;
    private String valveCode;
    /** 比例注肥泵编码 */
    private String fertPumpCode;
    /** 注肥比例 %（肥液占水体积百分比） */
    private BigDecimal injectRatioPct;
    /** 轮灌优先级（小者优先） */
    private Integer priority;
    /** 允许灌溉时间窗（一天内分钟数） */
    private Short windowStartMin;
    private Short windowEndMin;
    private String windowDays;
    /** 注肥阶段配置（透传 jsonb） */
    private Object fertPlan;
    private LocalDate sowingDate;
    private List<String> sensorCodes;
    private FieldConfigDto config;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCropCode() { return cropCode; }
    public void setCropCode(String cropCode) { this.cropCode = cropCode; }
    public String getCropVariety() { return cropVariety; }
    public void setCropVariety(String cropVariety) { this.cropVariety = cropVariety; }
    public BigDecimal getAreaM2() { return areaM2; }
    public void setAreaM2(BigDecimal areaM2) { this.areaM2 = areaM2; }
    public String getIrrigationMode() { return irrigationMode; }
    public void setIrrigationMode(String irrigationMode) { this.irrigationMode = irrigationMode; }
    public BigDecimal getEmitterTotalLph() { return emitterTotalLph; }
    public void setEmitterTotalLph(BigDecimal emitterTotalLph) { this.emitterTotalLph = emitterTotalLph; }
    public String getValveCode() { return valveCode; }
    public void setValveCode(String valveCode) { this.valveCode = valveCode; }
    public String getFertPumpCode() { return fertPumpCode; }
    public void setFertPumpCode(String fertPumpCode) { this.fertPumpCode = fertPumpCode; }
    public BigDecimal getInjectRatioPct() { return injectRatioPct; }
    public void setInjectRatioPct(BigDecimal injectRatioPct) { this.injectRatioPct = injectRatioPct; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public Short getWindowStartMin() { return windowStartMin; }
    public void setWindowStartMin(Short windowStartMin) { this.windowStartMin = windowStartMin; }
    public Short getWindowEndMin() { return windowEndMin; }
    public void setWindowEndMin(Short windowEndMin) { this.windowEndMin = windowEndMin; }
    public String getWindowDays() { return windowDays; }
    public void setWindowDays(String windowDays) { this.windowDays = windowDays; }
    public Object getFertPlan() { return fertPlan; }
    public void setFertPlan(Object fertPlan) { this.fertPlan = fertPlan; }
    public LocalDate getSowingDate() { return sowingDate; }
    public void setSowingDate(LocalDate sowingDate) { this.sowingDate = sowingDate; }
    public List<String> getSensorCodes() { return sensorCodes; }
    public void setSensorCodes(List<String> sensorCodes) { this.sensorCodes = sensorCodes; }
    public FieldConfigDto getConfig() { return config; }
    public void setConfig(FieldConfigDto config) { this.config = config; }
}
