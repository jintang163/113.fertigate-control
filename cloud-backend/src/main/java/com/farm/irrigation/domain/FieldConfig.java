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

    /** 土壤湿度下限（体积含水率 %，策略可配；缺省回退 hardMin） */
    @Column(name = "moisture_lower_pct")
    private BigDecimal moistureLowerPct;

    /** 土壤湿度上限（策略可配；缺省回退 thetaFc+offset） */
    @Column(name = "moisture_upper_pct")
    private BigDecimal moistureUpperPct;

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

    // ---- 气象联动条件 ----
    @Column(name = "weather_linked", nullable = false)
    private boolean weatherLinked = false;

    /** 最大允许风速 m/s（喷灌受风影响大） */
    @Column(name = "wind_max_ms")
    private BigDecimal windMaxMs;

    @Column(name = "temp_min")
    private BigDecimal tempMin;

    @Column(name = "temp_max")
    private BigDecimal tempMax;

    /** 最低空气相对湿度 %（高温低湿可策略性调整，此处作为禁止开阀条件之一） */
    @Column(name = "humidity_min")
    private BigDecimal humidityMin;

    /** 当日累计降雨 ≥ 该值则跳过 mm */
    @Column(name = "rain_today_skip_mm")
    private BigDecimal rainTodaySkipMm;

    /** 未来 forecastDays 日预报最大/累计降雨 ≥ 该值则跳过 mm */
    @Column(name = "forecast_skip_mm")
    private BigDecimal forecastSkipMm;

    @Column(name = "forecast_days", nullable = false)
    private short forecastDays = 3;

    // ---- 安全联锁：缺水 / 过载 ----
    /** 主管道最低水压 kPa，低于持续 waterLostDelaySec 判缺水 */
    @Column(name = "pressure_min_kpa")
    private BigDecimal pressureMinKpa;

    /** 阀开期望最低瞬时流量 m³/h */
    @Column(name = "flow_min_m3h")
    private BigDecimal flowMinM3h;

    @Column(name = "water_lost_delay_sec", nullable = false)
    private int waterLostDelaySec = 15;

    /** 施肥泵过载电流 A */
    @Column(name = "pump_overload_a")
    private BigDecimal pumpOverloadA;

    @Column(nullable = false)
    private boolean enabled = true;

    /** 生长模型驱动：true 时由 Python 生长模型服务回调决策，云端定时评估跳过 */
    @Column(name = "growth_model_enabled", nullable = false)
    private boolean growthModelEnabled = false;

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
    public boolean isWeatherLinked() { return weatherLinked; }
    public void setWeatherLinked(boolean weatherLinked) { this.weatherLinked = weatherLinked; }
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
    public short getForecastDays() { return forecastDays; }
    public void setForecastDays(short forecastDays) { this.forecastDays = forecastDays; }
    public BigDecimal getPressureMinKpa() { return pressureMinKpa; }
    public void setPressureMinKpa(BigDecimal pressureMinKpa) { this.pressureMinKpa = pressureMinKpa; }
    public BigDecimal getFlowMinM3h() { return flowMinM3h; }
    public void setFlowMinM3h(BigDecimal flowMinM3h) { this.flowMinM3h = flowMinM3h; }
    public int getWaterLostDelaySec() { return waterLostDelaySec; }
    public void setWaterLostDelaySec(int waterLostDelaySec) { this.waterLostDelaySec = waterLostDelaySec; }
    public BigDecimal getPumpOverloadA() { return pumpOverloadA; }
    public void setPumpOverloadA(BigDecimal pumpOverloadA) { this.pumpOverloadA = pumpOverloadA; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isGrowthModelEnabled() { return growthModelEnabled; }
    public void setGrowthModelEnabled(boolean growthModelEnabled) { this.growthModelEnabled = growthModelEnabled; }
    public Instant getUpdatedAt() { return updatedAt; }
}
