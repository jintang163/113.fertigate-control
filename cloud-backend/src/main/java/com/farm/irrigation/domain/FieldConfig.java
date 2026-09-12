package com.farm.irrigation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "field_config")
public class FieldConfig {

    @Id
    @Column(name = "field_id")
    private Long fieldId;

    @Column(name = "theta_fc")
    private BigDecimal thetaFc;

    @Column(name = "theta_wp")
    private BigDecimal thetaWp;

    /** AUTO / MANUAL */
    @Column(nullable = false)
    private String mode = "AUTO";

    @Column(name = "hard_max_offset_pct", nullable = false)
    private BigDecimal hardMaxOffsetPct = new BigDecimal("3");

    @Column(name = "hard_min")
    private BigDecimal hardMin;

    @Column(name = "max_duration_sec")
    private Integer maxDurationSec;

    @Column(name = "min_interval_h")
    private BigDecimal minIntervalH;

    @Column(name = "ec_min")
    private BigDecimal ecMin;

    @Column(name = "ec_max")
    private BigDecimal ecMax;

    @Column(name = "ph_min")
    private BigDecimal phMin;

    @Column(name = "ph_max")
    private BigDecimal phMax;

    @Column(name = "rain_skip_mm")
    private BigDecimal rainSkipMm;

    @Column(name = "wet_ratio")
    private BigDecimal wetRatio;

    private BigDecimal efficiency;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private Instant updatedAt;

    public Long getFieldId() { return fieldId; }
    public void setFieldId(Long fieldId) { this.fieldId = fieldId; }
    public BigDecimal getThetaFc() { return thetaFc; }
    public void setThetaFc(BigDecimal thetaFc) { this.thetaFc = thetaFc; }
    public BigDecimal getThetaWp() { return thetaWp; }
    public void setThetaWp(BigDecimal thetaWp) { this.thetaWp = thetaWp; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public BigDecimal getHardMaxOffsetPct() { return hardMaxOffsetPct; }
    public void setHardMaxOffsetPct(BigDecimal hardMaxOffsetPct) { this.hardMaxOffsetPct = hardMaxOffsetPct; }
    public BigDecimal getHardMin() { return hardMin; }
    public void setHardMin(BigDecimal hardMin) { this.hardMin = hardMin; }
    public Integer getMaxDurationSec() { return maxDurationSec; }
    public void setMaxDurationSec(Integer maxDurationSec) { this.maxDurationSec = maxDurationSec; }
    public BigDecimal getMinIntervalH() { return minIntervalH; }
    public void setMinIntervalH(BigDecimal minIntervalH) { this.minIntervalH = minIntervalH; }
    public BigDecimal getEcMin() { return ecMin; }
    public void setEcMin(BigDecimal ecMin) { this.ecMin = ecMin; }
    public BigDecimal getEcMax() { return ecMax; }
    public void setEcMax(BigDecimal ecMax) { this.ecMax = ecMax; }
    public BigDecimal getPhMin() { return phMin; }
    public void setPhMin(BigDecimal phMin) { this.phMin = phMin; }
    public BigDecimal getPhMax() { return phMax; }
    public void setPhMax(BigDecimal phMax) { this.phMax = phMax; }
    public BigDecimal getRainSkipMm() { return rainSkipMm; }
    public void setRainSkipMm(BigDecimal rainSkipMm) { this.rainSkipMm = rainSkipMm; }
    public BigDecimal getWetRatio() { return wetRatio; }
    public void setWetRatio(BigDecimal wetRatio) { this.wetRatio = wetRatio; }
    public BigDecimal getEfficiency() { return efficiency; }
    public void setEfficiency(BigDecimal efficiency) { this.efficiency = efficiency; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getUpdatedAt() { return updatedAt; }
}
