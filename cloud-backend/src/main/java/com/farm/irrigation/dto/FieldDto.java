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
    @NotNull
    private BigDecimal areaM2;
    private String irrigationMode;
    private BigDecimal emitterTotalLph;
    private String valveCode;
    private LocalDate sowingDate;
    private List<String> sensorCodes;
    private FieldConfigDto config;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCropCode() { return cropCode; }
    public void setCropCode(String cropCode) { this.cropCode = cropCode; }
    public BigDecimal getAreaM2() { return areaM2; }
    public void setAreaM2(BigDecimal areaM2) { this.areaM2 = areaM2; }
    public String getIrrigationMode() { return irrigationMode; }
    public void setIrrigationMode(String irrigationMode) { this.irrigationMode = irrigationMode; }
    public BigDecimal getEmitterTotalLph() { return emitterTotalLph; }
    public void setEmitterTotalLph(BigDecimal emitterTotalLph) { this.emitterTotalLph = emitterTotalLph; }
    public String getValveCode() { return valveCode; }
    public void setValveCode(String valveCode) { this.valveCode = valveCode; }
    public LocalDate getSowingDate() { return sowingDate; }
    public void setSowingDate(LocalDate sowingDate) { this.sowingDate = sowingDate; }
    public List<String> getSensorCodes() { return sensorCodes; }
    public void setSensorCodes(List<String> sensorCodes) { this.sensorCodes = sensorCodes; }
    public FieldConfigDto getConfig() { return config; }
    public void setConfig(FieldConfigDto config) { this.config = config; }
}
