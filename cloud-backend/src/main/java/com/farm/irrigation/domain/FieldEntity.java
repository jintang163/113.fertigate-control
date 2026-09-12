package com.farm.irrigation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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

    @Column(name = "area_m2", nullable = false)
    private java.math.BigDecimal areaM2;

    /** DRIP / SPRINKLER */
    @Column(name = "irrigation_mode", nullable = false)
    private String irrigationMode = "DRIP";

    @Column(name = "emitter_total_lph")
    private java.math.BigDecimal emitterTotalLph;

    @Column(name = "valve_code")
    private String valveCode;

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
    public java.math.BigDecimal getAreaM2() { return areaM2; }
    public void setAreaM2(java.math.BigDecimal areaM2) { this.areaM2 = areaM2; }
    public String getIrrigationMode() { return irrigationMode; }
    public void setIrrigationMode(String irrigationMode) { this.irrigationMode = irrigationMode; }
    public java.math.BigDecimal getEmitterTotalLph() { return emitterTotalLph; }
    public void setEmitterTotalLph(java.math.BigDecimal emitterTotalLph) { this.emitterTotalLph = emitterTotalLph; }
    public String getValveCode() { return valveCode; }
    public void setValveCode(String valveCode) { this.valveCode = valveCode; }
    public LocalDate getSowingDate() { return sowingDate; }
    public void setSowingDate(LocalDate sowingDate) { this.sowingDate = sowingDate; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
