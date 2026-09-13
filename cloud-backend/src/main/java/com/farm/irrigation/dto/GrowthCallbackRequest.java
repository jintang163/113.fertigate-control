package com.farm.irrigation.dto;

import java.util.List;
import java.util.Map;

/**
 * Python 生长模型服务的融合决策回调载荷（POST /api/growth/callback）。
 * 与 decision-service app/growth_models.py 的 GrowthCallback 对应。
 */
public class GrowthCallbackRequest {

    /** 幂等键：同一 decisionId 只执行一次 */
    private String decisionId;
    private Long fieldId;
    private String decidedAt;
    private Irrigation irrigation;
    private Fertigation fertigation;

    public static class Irrigation {
        /** OPEN（开启，含时长/水量） / CLOSED（保持关闭） */
        private String action;
        private Double volumeM3;
        private Integer durationSec;
        private List<String> reasons;

        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public Double getVolumeM3() { return volumeM3; }
        public void setVolumeM3(Double volumeM3) { this.volumeM3 = volumeM3; }
        public Integer getDurationSec() { return durationSec; }
        public void setDurationSec(Integer durationSec) { this.durationSec = durationSec; }
        public List<String> getReasons() { return reasons; }
        public void setReasons(List<String> reasons) { this.reasons = reasons; }
    }

    public static class Fertigation {
        private Boolean shouldFertilize;
        private Double fertilizerL;
        /** N/P/K 纯养分量 kg：{"n":..,"p":..,"k":..} */
        private Map<String, Double> npkKg;
        private Double injectRatioPct;
        private List<String> reasons;

        public Boolean getShouldFertilize() { return shouldFertilize; }
        public void setShouldFertilize(Boolean shouldFertilize) { this.shouldFertilize = shouldFertilize; }
        public Double getFertilizerL() { return fertilizerL; }
        public void setFertilizerL(Double fertilizerL) { this.fertilizerL = fertilizerL; }
        public Map<String, Double> getNpkKg() { return npkKg; }
        public void setNpkKg(Map<String, Double> npkKg) { this.npkKg = npkKg; }
        public Double getInjectRatioPct() { return injectRatioPct; }
        public void setInjectRatioPct(Double injectRatioPct) { this.injectRatioPct = injectRatioPct; }
        public List<String> getReasons() { return reasons; }
        public void setReasons(List<String> reasons) { this.reasons = reasons; }
    }

    public String getDecisionId() { return decisionId; }
    public void setDecisionId(String decisionId) { this.decisionId = decisionId; }
    public Long getFieldId() { return fieldId; }
    public void setFieldId(Long fieldId) { this.fieldId = fieldId; }
    public String getDecidedAt() { return decidedAt; }
    public void setDecidedAt(String decidedAt) { this.decidedAt = decidedAt; }
    public Irrigation getIrrigation() { return irrigation; }
    public void setIrrigation(Irrigation irrigation) { this.irrigation = irrigation; }
    public Fertigation getFertigation() { return fertigation; }
    public void setFertigation(Fertigation fertigation) { this.fertigation = fertigation; }
}
