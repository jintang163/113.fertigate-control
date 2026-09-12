package com.farm.irrigation.dto;

import java.util.List;
import java.util.Map;

/** GET /api/fields/{id}/status real-time aggregate. */
public class FieldStatusDto {

    private Long fieldId;
    private String name;
    private String mode;
    private String valveState;
    private boolean valveOnline;
    private boolean gatewayOnline;
    private Double moisture;
    private Double ec;
    private Double ph;
    private Double thetaStart;
    private Double thetaFc;
    private Double thetaWp;
    private Double hardMax;
    private String stage;
    private Long jobId;
    private String jobBizCode;
    private String jobStatus;
    private String lastIrrigTime;
    private List<Map<String, Object>> alarms;
    private boolean decisionFallback;
    private List<String> reasons;

    public Long getFieldId() { return fieldId; }
    public void setFieldId(Long fieldId) { this.fieldId = fieldId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getValveState() { return valveState; }
    public void setValveState(String valveState) { this.valveState = valveState; }
    public boolean isValveOnline() { return valveOnline; }
    public void setValveOnline(boolean valveOnline) { this.valveOnline = valveOnline; }
    public boolean isGatewayOnline() { return gatewayOnline; }
    public void setGatewayOnline(boolean gatewayOnline) { this.gatewayOnline = gatewayOnline; }
    public Double getMoisture() { return moisture; }
    public void setMoisture(Double moisture) { this.moisture = moisture; }
    public Double getEc() { return ec; }
    public void setEc(Double ec) { this.ec = ec; }
    public Double getPh() { return ph; }
    public void setPh(Double ph) { this.ph = ph; }
    public Double getThetaStart() { return thetaStart; }
    public void setThetaStart(Double thetaStart) { this.thetaStart = thetaStart; }
    public Double getThetaFc() { return thetaFc; }
    public void setThetaFc(Double thetaFc) { this.thetaFc = thetaFc; }
    public Double getThetaWp() { return thetaWp; }
    public void setThetaWp(Double thetaWp) { this.thetaWp = thetaWp; }
    public Double getHardMax() { return hardMax; }
    public void setHardMax(Double hardMax) { this.hardMax = hardMax; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public String getJobBizCode() { return jobBizCode; }
    public void setJobBizCode(String jobBizCode) { this.jobBizCode = jobBizCode; }
    public String getJobStatus() { return jobStatus; }
    public void setJobStatus(String jobStatus) { this.jobStatus = jobStatus; }
    public String getLastIrrigTime() { return lastIrrigTime; }
    public void setLastIrrigTime(String lastIrrigTime) { this.lastIrrigTime = lastIrrigTime; }
    public List<Map<String, Object>> getAlarms() { return alarms; }
    public void setAlarms(List<Map<String, Object>> alarms) { this.alarms = alarms; }
    public boolean isDecisionFallback() { return decisionFallback; }
    public void setDecisionFallback(boolean decisionFallback) { this.decisionFallback = decisionFallback; }
    public List<String> getReasons() { return reasons; }
    public void setReasons(List<String> reasons) { this.reasons = reasons; }
}
