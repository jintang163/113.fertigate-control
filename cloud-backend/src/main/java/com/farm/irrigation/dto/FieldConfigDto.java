package com.farm.irrigation.dto;

import java.math.BigDecimal;

public class FieldConfigDto {

    private BigDecimal thetaFc;
    private BigDecimal thetaWp;
    private String mode;
    private BigDecimal hardMaxOffsetPct;
    private BigDecimal hardMin;
    /** 土壤湿度策略下限/上限（%） */
    private BigDecimal moistureLowerPct;
    private BigDecimal moistureUpperPct;
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
    /** 生长模型驱动：true 时由 Python 生长模型服务回调决策 */
    private Boolean growthModelEnabled;

    // ---- 气象联动 ----
    private Boolean weatherLinked;
    private BigDecimal windMaxMs;
    private BigDecimal tempMin;
    private BigDecimal tempMax;
    private BigDecimal humidityMin;
    private BigDecimal rainTodaySkipMm;
    private BigDecimal forecastSkipMm;
    private Short forecastDays;

    // ---- 缺水 / 过载联锁 ----
    private BigDecimal pressureMinKpa;
    private BigDecimal flowMinM3h;
    private Integer waterLostDelaySec;
    private BigDecimal pumpOverloadA;

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
    public BigDecimal getMoistureLowerPct() { return moistureLowerPct; }
    public void setMoistureLowerPct(BigDecimal moistureLowerPct) { this.moistureLowerPct = moistureLowerPct; }
    public BigDecimal getMoistureUpperPct() { return moistureUpperPct; }
    public void setMoistureUpperPct(BigDecimal moistureUpperPct) { this.moistureUpperPct = moistureUpperPct; }
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
    public Boolean getGrowthModelEnabled() { return growthModelEnabled; }
    public void setGrowthModelEnabled(Boolean growthModelEnabled) { this.growthModelEnabled = growthModelEnabled; }
    public Boolean getWeatherLinked() { return weatherLinked; }
    public void setWeatherLinked(Boolean weatherLinked) { this.weatherLinked = weatherLinked; }
    public BigDecimal getWindMaxMs() { return windMaxMs; }
    public void setWindMaxMs(BigDecimal windMaxMs) { this.windMaxMs = windMaxMs; }
    public BigDecimal getTempMin() { return tempMin; }
    public void setTempMin(BigDecimal tempMin) { this.tempMin = tempMin; }
    public BigDecimal getTempMax() { return tempMax; }
    public void setTempMax(BigDecimal tempMax) { this.tempMax = tempMax; }
    public BigDecimal getHumidityMin() { return humidityMin; }
    public void setHumidityMin(BigDecimal humidityMin) { this.humidityMin = humidityMin; }
    public BigDecimal getRainTodaySkipMm() { return rainTodaySkipMm; }
    public void setRainTodaySkipMm(BigDecimal rainTodaySkipMm) { this.rainTodaySkipMm = rainTodaySkipMm; }
    public BigDecimal getForecastSkipMm() { return forecastSkipMm; }
    public void setForecastSkipMm(BigDecimal forecastSkipMm) { this.forecastSkipMm = forecastSkipMm; }
    public Short getForecastDays() { return forecastDays; }
    public void setForecastDays(Short forecastDays) { this.forecastDays = forecastDays; }
    public BigDecimal getPressureMinKpa() { return pressureMinKpa; }
    public void setPressureMinKpa(BigDecimal pressureMinKpa) { this.pressureMinKpa = pressureMinKpa; }
    public BigDecimal getFlowMinM3h() { return flowMinM3h; }
    public void setFlowMinM3h(BigDecimal flowMinM3h) { this.flowMinM3h = flowMinM3h; }
    public Integer getWaterLostDelaySec() { return waterLostDelaySec; }
    public void setWaterLostDelaySec(Integer waterLostDelaySec) { this.waterLostDelaySec = waterLostDelaySec; }
    public BigDecimal getPumpOverloadA() { return pumpOverloadA; }
    public void setPumpOverloadA(BigDecimal pumpOverloadA) { this.pumpOverloadA = pumpOverloadA; }
}
