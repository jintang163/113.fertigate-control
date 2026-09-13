package com.farm.irrigation.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.farm.irrigation.config.DecisionProperties;
import com.farm.irrigation.domain.CropModel;
import com.farm.irrigation.domain.FieldConfig;
import com.farm.irrigation.domain.FieldEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Calls the Python decision service ({@code POST /decide}) strictly following
 * api-contract.md. On any failure (service down / timeout / bad payload) the
 * local FAO-56 threshold fallback is used so the evaluation loop never breaks.
 */
@Service
public class DecisionClient {

    private static final Logger log = LoggerFactory.getLogger(DecisionClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final LocalDecisionCalculator localCalculator;
    private final DecisionProperties props;

    public DecisionClient(RestClient decisionRestClient,
                          ObjectMapper objectMapper,
                          LocalDecisionCalculator localCalculator,
                          DecisionProperties props) {
        this.restClient = decisionRestClient;
        this.objectMapper = objectMapper;
        this.localCalculator = localCalculator;
        this.props = props;
    }

    /**
     * Evaluate a field. Never throws: returns a local fallback result when the
     * decision service cannot be reached, with fallback=true flagged in reasons.
     */
    public DecisionResult decide(FieldEntity field, FieldConfig cfg, CropModel crop,
                                 FieldSnapshot snap) {
        Map<String, Object> request = buildRequest(field, cfg, crop, snap);
        try {
            String body = objectMapper.writeValueAsString(request);
            log.debug("POST {}/decide body={}", props.getUrl(), body);
            String response = restClient.post()
                    .uri("/decide")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(response);
            return parse(node, false);
        } catch (Exception e) {
            log.warn("Decision service unavailable ({}), using local threshold fallback for field {}",
                    e.getMessage(), field.getId());
            DecisionResult fallback = localCalculator.compute(field, cfg, crop, snap);
            fallback.getReasons().add(0, "LOCAL_FALLBACK decision-service unreachable: " + e.getMessage());
            return fallback;
        }
    }

    public Map<String, Object> buildRequest(FieldEntity field, FieldConfig cfg,
                                            CropModel crop, FieldSnapshot snap) {
        long daysAfterSowing = daysAfterSowing(field);

        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> cropSec = new LinkedHashMap<>();
        cropSec.put("code", crop.getCode());
        cropSec.put("stages", parseStages(crop));
        cropSec.put("daysAfterSowing", daysAfterSowing);
        root.put("crop", cropSec);

        Map<String, Object> soil = new LinkedHashMap<>();
        soil.put("thetaFc", num(cfg.getThetaFc()));
        soil.put("thetaWp", num(cfg.getThetaWp()));
        soil.put("moisture", nanToNull(snap.getMoistureAvg()));
        ArrayNode zones = objectMapper.createArrayNode();
        for (FieldSnapshot.SoilZone z : snap.getSoilZones()) {
            zones.addObject()
                    .put("depth", z.depthMm())
                    .put("moist", z.moisture());
        }
        soil.put("zones", zones);
        root.put("soil", soil);

        Map<String, Object> weather = new LinkedHashMap<>();
        weather.put("airTemp", nanToNull(snap.getAirTemp()));
        weather.put("tMax", nanToNull(snap.getTmax()));
        weather.put("tMin", nanToNull(snap.getTmin()));
        weather.put("rainfallToday", nanToNull(snap.getRainfallToday()));
        weather.put("rainForecastMm", snap.getRainForecast() != null ? snap.getRainForecast() : List.of(0, 0, 0));
        weather.put("latitude", snap.getLatitude());
        root.put("weather", weather);

        Map<String, Object> fieldSec = new LinkedHashMap<>();
        fieldSec.put("areaM2", num(field.getAreaM2()));
        fieldSec.put("wetRatio", num(cfg.getWetRatio()));
        fieldSec.put("efficiency", num(cfg.getEfficiency()));
        fieldSec.put("emitterTotalLph", num(field.getEmitterTotalLph()));
        root.put("field", fieldSec);

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("hardMax", hardMax(cfg));
        limits.put("hardMin", num(cfg.getHardMin()));
        limits.put("ec", nanToNull(snap.getEc()));
        limits.put("ecMin", num(cfg.getEcMin()));
        limits.put("ecMax", num(cfg.getEcMax()));
        limits.put("ph", nanToNull(snap.getPh()));
        limits.put("phMin", num(cfg.getPhMin()));
        limits.put("phMax", num(cfg.getPhMax()));
        limits.put("maxDurationSec", cfg.getMaxDurationSec());
        limits.put("minIntervalH", num(cfg.getMinIntervalH()));
        limits.put("lastIrrigAgoH", nanToNull(snap.getLastIrrigAgoH()));
        root.put("limits", limits);
        return root;
    }

    /** JSON has no NaN; absent readings must be sent as null (Python fields are Optional). */
    private static Double nanToNull(Double v) {
        return (v == null || Double.isNaN(v) || Double.isInfinite(v)) ? null : v;
    }

    private DecisionResult parse(JsonNode node, boolean fallback) {
        DecisionResult r = new DecisionResult();
        r.setDecision(text(node.get("decision"), "HOLD"));
        r.setStage(text(node.get("stage"), null));
        r.setThetaStart(doub(node.get("thetaStart")));
        r.setThetaTarget(doub(node.get("thetaTarget")));
        r.setMoisture(doub(node.get("moisture")));
        r.setDeficitMm(doub(node.get("deficitMm")));
        r.setVolumeM3(doub(node.get("volumeM3")));
        JsonNode dur = node.get("durationSec");
        if (dur != null && dur.isNumber()) {
            r.setDurationSec(dur.asInt());
        }
        r.setClampReason(text(node.get("clampReason"), null));
        r.setEt0MmDay(doub(node.get("et0MmDay")));
        r.setEtcMmDay(doub(node.get("etcMmDay")));
        JsonNode reasons = node.get("reasons");
        if (reasons != null && reasons.isArray()) {
            reasons.forEach(x -> r.getReasons().add(x.asText()));
        }
        r.setFallback(fallback);
        return r;
    }

    private List<JsonNode> parseStages(CropModel crop) {
        try {
            JsonNode arr = objectMapper.readTree(crop.getStages());
            if (arr.isArray()) {
                return objectMapper.convertValue(arr,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, JsonNode.class));
            }
        } catch (Exception e) {
            log.warn("invalid stages json for crop {}: {}", crop.getCode(), e.getMessage());
        }
        return List.of();
    }

    public static long daysAfterSowing(FieldEntity field) {
        if (field.getSowingDate() == null) {
            return 0;
        }
        return ChronoUnit.DAYS.between(field.getSowingDate(), LocalDate.now());
    }

    static double hardMax(FieldConfig cfg) {
        double fc = num(cfg.getThetaFc());
        double off = cfg.getHardMaxOffsetPct() == null ? 3d : cfg.getHardMaxOffsetPct().doubleValue();
        return fc + off;
    }

    private static double num(java.math.BigDecimal v) {
        return v == null ? 0d : v.doubleValue();
    }

    private static String text(JsonNode n, String dflt) {
        if (n == null || n.isNull()) {
            return dflt;
        }
        return n.asText();
    }

    private static Double doub(JsonNode n) {
        if (n == null || n.isNull() || !n.isNumber()) {
            return null;
        }
        return n.asDouble();
    }
}
