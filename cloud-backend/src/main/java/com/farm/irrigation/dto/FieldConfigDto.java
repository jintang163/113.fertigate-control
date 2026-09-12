package com.farm.irrigation.dto;

import java.math.BigDecimal;

public class FieldConfigDto {

    private BigDecimal thetaFc;
    private BigDecimal thetaWp;
    private String mode;
    private BigDecimal hardMaxOffsetPct;
    private BigDecimal hardMin;
    private Integer maxDurationSec;
    private BigDecimal minIntervalH;
    private BigDecimal ecMin;
    private BigDecimal ecMax;
    private BigDecimal phMin;
    private BigDecimal phMax;
    private BigDecimal rainSkipMm;
    private BigDecimal wetRatio;
    private BigDecimal efficiency;
    private Boolean enabled;

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
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
