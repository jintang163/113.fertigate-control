package com.farm.irrigation.control;

/**
 * Normalized decision result. Mirrors the Python /decide response
 * (api-contract.md) plus a fallback flag for degraded local computation.
 */
public class DecisionResult {

    /** IRRIGATE / HOLD / SKIP / FORBID */
    private String decision = "HOLD";
    private String stage;
    private Double thetaStart;
    private Double thetaTarget;
    private Double deficitMm;
    private Double volumeM3;
    private Integer durationSec;
    private String clampReason;
    private Double et0MmDay;
    private Double etcMmDay;
    private final java.util.List<String> reasons = new java.util.ArrayList<>();
    private boolean fallback;

    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public Double getThetaStart() { return thetaStart; }
    public void setThetaStart(Double thetaStart) { this.thetaStart = thetaStart; }
    public Double getThetaTarget() { return thetaTarget; }
    public void setThetaTarget(Double thetaTarget) { this.thetaTarget = thetaTarget; }
    public Double getDeficitMm() { return deficitMm; }
    public void setDeficitMm(Double deficitMm) { this.deficitMm = deficitMm; }
    public Double getVolumeM3() { return volumeM3; }
    public void setVolumeM3(Double volumeM3) { this.volumeM3 = volumeM3; }
    public Integer getDurationSec() { return durationSec; }
    public void setDurationSec(Integer durationSec) { this.durationSec = durationSec; }
    public String getClampReason() { return clampReason; }
    public void setClampReason(String clampReason) { this.clampReason = clampReason; }
    public Double getEt0MmDay() { return et0MmDay; }
    public void setEt0MmDay(Double et0MmDay) { this.et0MmDay = et0MmDay; }
    public Double getEtcMmDay() { return etcMmDay; }
    public void setEtcMmDay(Double etcMmDay) { this.etcMmDay = etcMmDay; }
    public java.util.List<String> getReasons() { return reasons; }
    public boolean isFallback() { return fallback; }
    public void setFallback(boolean fallback) { this.fallback = fallback; }
}
