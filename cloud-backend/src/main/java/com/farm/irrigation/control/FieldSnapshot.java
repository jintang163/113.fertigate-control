package com.farm.irrigation.control;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregated live snapshot of one field used by the control engine
 * and the decision-service request builder.
 */
public class FieldSnapshot {

    private double moistureAvg;
    private Double ec;
    private Double ph;
    private final List<SoilZone> soilZones = new ArrayList<>();
    private Instant soilTs;

    private Double airTemp;
    private Double tmax;
    private Double tmin;
    private Double airHumidity;
    private Double windSpeed;
    private double rainfallToday;
    private List<Double> rainForecast;
    private Instant weatherTs;

    /** latest flow meter instant flow m3/h and cumulative m3 since job start baseline */
    private Double instantFlow;
    private Double totalFlow;
    private String flowDeviceCode;
    private Instant flowTs;

    /** 主管道水压 kPa（缺水联锁） */
    private Double pressureKpa;
    private String pressureDeviceCode;

    /** 注肥泵当前状态 */
    private String pumpState;
    private Integer pumpOpening;
    private Double pumpCurrentA;
    private boolean pumpOverload;

    private String valveState;
    private Double valveAppliedVolume;
    private Instant valveTs;

    private boolean gatewayOnline;
    private boolean valveOnline;
    private String gatewaySn;

    private Instant lastJobTime;
    private Double lastIrrigAgoH;
    private String primarySoilSensorCode;

    private double latitude = 34.5;

    public record SoilZone(int depthMm, double moisture) {}

    public double getMoistureAvg() { return moistureAvg; }
    public void setMoistureAvg(double moistureAvg) { this.moistureAvg = moistureAvg; }
    public Double getEc() { return ec; }
    public void setEc(Double ec) { this.ec = ec; }
    public Double getPh() { return ph; }
    public void setPh(Double ph) { this.ph = ph; }
    public List<SoilZone> getSoilZones() { return soilZones; }
    public Instant getSoilTs() { return soilTs; }
    public void setSoilTs(Instant soilTs) { this.soilTs = soilTs; }
    public Double getAirTemp() { return airTemp; }
    public void setAirTemp(Double airTemp) { this.airTemp = airTemp; }
    public Double getTmax() { return tmax; }
    public void setTmax(Double tmax) { this.tmax = tmax; }
    public Double getTmin() { return tmin; }
    public void setTmin(Double tmin) { this.tmin = tmin; }
    public Double getAirHumidity() { return airHumidity; }
    public void setAirHumidity(Double airHumidity) { this.airHumidity = airHumidity; }
    public Double getWindSpeed() { return windSpeed; }
    public void setWindSpeed(Double windSpeed) { this.windSpeed = windSpeed; }
    public double getRainfallToday() { return rainfallToday; }
    public void setRainfallToday(double rainfallToday) { this.rainfallToday = rainfallToday; }
    public List<Double> getRainForecast() { return rainForecast; }
    public void setRainForecast(List<Double> rainForecast) { this.rainForecast = rainForecast; }
    public Instant getWeatherTs() { return weatherTs; }
    public void setWeatherTs(Instant weatherTs) { this.weatherTs = weatherTs; }
    public Double getInstantFlow() { return instantFlow; }
    public void setInstantFlow(Double instantFlow) { this.instantFlow = instantFlow; }
    public Double getTotalFlow() { return totalFlow; }
    public void setTotalFlow(Double totalFlow) { this.totalFlow = totalFlow; }
    public String getFlowDeviceCode() { return flowDeviceCode; }
    public void setFlowDeviceCode(String flowDeviceCode) { this.flowDeviceCode = flowDeviceCode; }
    public Instant getFlowTs() { return flowTs; }
    public void setFlowTs(Instant flowTs) { this.flowTs = flowTs; }
    public Double getPressureKpa() { return pressureKpa; }
    public void setPressureKpa(Double pressureKpa) { this.pressureKpa = pressureKpa; }
    public String getPressureDeviceCode() { return pressureDeviceCode; }
    public void setPressureDeviceCode(String pressureDeviceCode) { this.pressureDeviceCode = pressureDeviceCode; }
    public String getPumpState() { return pumpState; }
    public void setPumpState(String pumpState) { this.pumpState = pumpState; }
    public Integer getPumpOpening() { return pumpOpening; }
    public void setPumpOpening(Integer pumpOpening) { this.pumpOpening = pumpOpening; }
    public Double getPumpCurrentA() { return pumpCurrentA; }
    public void setPumpCurrentA(Double pumpCurrentA) { this.pumpCurrentA = pumpCurrentA; }
    public boolean isPumpOverload() { return pumpOverload; }
    public void setPumpOverload(boolean pumpOverload) { this.pumpOverload = pumpOverload; }
    public String getValveState() { return valveState; }
    public void setValveState(String valveState) { this.valveState = valveState; }
    public Double getValveAppliedVolume() { return valveAppliedVolume; }
    public void setValveAppliedVolume(Double valveAppliedVolume) { this.valveAppliedVolume = valveAppliedVolume; }
    public Instant getValveTs() { return valveTs; }
    public void setValveTs(Instant valveTs) { this.valveTs = valveTs; }
    public boolean isGatewayOnline() { return gatewayOnline; }
    public void setGatewayOnline(boolean gatewayOnline) { this.gatewayOnline = gatewayOnline; }
    public boolean isValveOnline() { return valveOnline; }
    public void setValveOnline(boolean valveOnline) { this.valveOnline = valveOnline; }
    public String getGatewaySn() { return gatewaySn; }
    public void setGatewaySn(String gatewaySn) { this.gatewaySn = gatewaySn; }
    public Instant getLastJobTime() { return lastJobTime; }
    public void setLastJobTime(Instant lastJobTime) { this.lastJobTime = lastJobTime; }
    public Double getLastIrrigAgoH() { return lastIrrigAgoH; }
    public void setLastIrrigAgoH(Double lastIrrigAgoH) { this.lastIrrigAgoH = lastIrrigAgoH; }
    public String getPrimarySoilSensorCode() { return primarySoilSensorCode; }
    public void setPrimarySoilSensorCode(String primarySoilSensorCode) { this.primarySoilSensorCode = primarySoilSensorCode; }
    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }
}
