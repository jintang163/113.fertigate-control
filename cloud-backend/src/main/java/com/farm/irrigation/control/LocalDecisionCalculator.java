package com.farm.irrigation.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.domain.CropModel;
import com.farm.irrigation.domain.FieldConfig;
import com.farm.irrigation.domain.FieldEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Degraded local decision used when the Python decision service is unreachable.
 * Implements the FAO-56 threshold rule from control-logic.md section 1-2:
 * thetaStart = thetaFc - p*(thetaFc - thetaWp); volume from deficit depth.
 */
@Component
public class LocalDecisionCalculator {

    private static final Logger log = LoggerFactory.getLogger(LocalDecisionCalculator.class);

    private final ObjectMapper objectMapper;

    public LocalDecisionCalculator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DecisionResult compute(FieldEntity field, FieldConfig cfg, CropModel crop, FieldSnapshot snap) {
        DecisionResult r = new DecisionResult();
        r.setFallback(true);

        double fc = nz(cfg.getThetaFc());
        double wp = nz(cfg.getThetaWp());
        double now = snap.getMoistureAvg();

        StageParams stage = currentStage(crop, DecisionClient.daysAfterSowing(field));
        r.setStage(stage.name);
        double p = stage.p;
        double zr = stage.zrMm;
        double thetaStart = round(fc - p * (fc - wp), 2);
        double thetaTarget = round(fc - 1.0, 2); // drip: stop slightly below FC to avoid deep percolation
        r.setThetaStart(thetaStart);
        r.setThetaTarget(thetaTarget);
        if (!Double.isNaN(now)) {
            r.setMoisture(round(now, 2));
        }

        double hardMax = DecisionClient.hardMax(cfg);
        if (now >= hardMax || snap.getEc() != null && outOfWindow(snap.getEc(), cfg.getEcMin(), cfg.getEcMax())
                || snap.getPh() != null && outOfWindow(snap.getPh(), cfg.getPhMin(), cfg.getPhMax())) {
            r.setDecision("FORBID");
            r.getReasons().add("local fallback: hardMax or EC/pH interlock active");
            return r;
        }

        if (snap.getRainfallToday() >= nz(cfg.getRainSkipMm())) {
            r.setDecision("SKIP");
            r.getReasons().add("local fallback: rainfall " + snap.getRainfallToday()
                    + "mm >= rainSkip " + cfg.getRainSkipMm());
            return r;
        }

        if (now > thetaStart) {
            r.setDecision("HOLD");
            r.getReasons().add("local fallback: moisture " + round(now, 2)
                    + "% > thetaStart " + thetaStart + "%");
            return r;
        }

        // IRRIGATE: deficit depth D(mm) = (thetaTarget-thetaNow)/100 * Zr * p_wet / eta
        double wetRatio = nz(cfg.getWetRatio(), 1d);
        double efficiency = nz(cfg.getEfficiency(), 1d);
        double dNeed = Math.max(0d, (thetaTarget - now) / 100d * zr * wetRatio / efficiency);
        double area = field.getAreaM2() == null ? 0d : field.getAreaM2().doubleValue();
        double volumeM3 = dNeed / 1000d * area;

        double emitterLph = field.getEmitterTotalLph() == null ? 0d : field.getEmitterTotalLph().doubleValue();
        int durationSec = emitterLph > 0 ? (int) Math.round(volumeM3 * 1000d / emitterLph * 3600d) : 0;
        String clamp = null;
        if (cfg.getMaxDurationSec() != null && durationSec > cfg.getMaxDurationSec()) {
            durationSec = cfg.getMaxDurationSec();
            // clamp volume to what can be delivered in max duration
            if (emitterLph > 0) {
                volumeM3 = emitterLph / 1000d * durationSec / 3600d;
            }
            clamp = "DURATION_LIMIT";
        }
        volumeM3 = round(volumeM3, 3);

        r.setDecision("IRRIGATE");
        r.setDeficitMm(round(dNeed, 2));
        r.setVolumeM3(volumeM3);
        r.setDurationSec(durationSec);
        r.setClampReason(clamp);
        r.getReasons().add("local fallback: moisture " + round(now, 2)
                + "% <= thetaStart " + thetaStart + "%; stage=" + stage.name
                + " p=" + p + " zr=" + zr + "mm");
        log.warn("Local decision for field {}: IRRIGATE volume={}m3 duration={}s",
                field.getId(), volumeM3, durationSec);
        return r;
    }

    /** thetaStart for status display without a full decision call. */
    public double thetaStart(FieldConfig cfg, CropModel crop, long daysAfterSowing) {
        StageParams s = currentStage(crop, daysAfterSowing);
        double fc = nz(cfg.getThetaFc());
        return round(fc - s.p * (fc - nz(cfg.getThetaWp())), 2);
    }

    /** Current crop stage name for status display. */
    public String stageName(CropModel crop, long daysAfterSowing) {
        return currentStage(crop, daysAfterSowing).name;
    }

    private boolean outOfWindow(double v, BigDecimal lo, BigDecimal hi) {
        return (lo != null && v < lo.doubleValue()) || (hi != null && v > hi.doubleValue());
    }

    private StageParams currentStage(CropModel crop, long day) {
        StageParams dflt = new StageParams("unknown", 0.5, 400);
        if (crop == null || crop.getStages() == null) {
            return dflt;
        }
        try {
            JsonNode stages = objectMapper.readTree(crop.getStages());
            for (JsonNode st : stages) {
                int start = st.path("startDay").asInt(0);
                int end = st.path("endDay").asInt(Integer.MAX_VALUE);
                if (day >= start && day < end) {
                    double frac = end > start ? (double) (day - start) / (end - start) : 0d;
                    double kc = interp(st.path("startKc").asDouble(0.6), st.path("endKc").asDouble(0.6), frac);
                    double p = interp(st.path("startP").asDouble(0.5), st.path("endP").asDouble(0.5), frac);
                    double zr = st.path("zrMm").asDouble(400);
                    return new StageParams(st.path("name").asText("stage"), p, zr, kc);
                }
            }
            if (stages.isArray() && stages.size() > 0) {
                JsonNode last = stages.get(stages.size() - 1);
                return new StageParams(last.path("name").asText("late"),
                        last.path("endP").asDouble(0.5), last.path("zrMm").asDouble(700),
                        last.path("endKc").asDouble(0.8));
            }
        } catch (Exception e) {
            log.warn("failed parsing stages of {}: {}", crop.getCode(), e.getMessage());
        }
        return dflt;
    }

    private static double interp(double a, double b, double frac) {
        return a + (b - a) * Math.max(0d, Math.min(1d, frac));
    }

    private static double nz(BigDecimal v) {
        return v == null ? 0d : v.doubleValue();
    }

    private static double nz(BigDecimal v, double dflt) {
        return v == null ? dflt : v.doubleValue();
    }

    private static double round(double v, int scale) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return v;
        }
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private record StageParams(String name, double p, double zrMm, double kc) {
        StageParams(String name, double p, double zrMm) {
            this(name, p, zrMm, 0d);
        }
    }
}
