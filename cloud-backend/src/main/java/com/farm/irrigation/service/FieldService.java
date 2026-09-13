package com.farm.irrigation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.common.ApiException;
import com.farm.irrigation.domain.CropModel;
import com.farm.irrigation.domain.FieldConfig;
import com.farm.irrigation.domain.FieldEntity;
import com.farm.irrigation.domain.FieldSensor;
import com.farm.irrigation.dto.FieldConfigDto;
import com.farm.irrigation.dto.FieldDto;
import com.farm.irrigation.repo.CropModelRepository;
import com.farm.irrigation.repo.FieldConfigRepository;
import com.farm.irrigation.repo.FieldRepository;
import com.farm.irrigation.repo.FieldSensorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FieldService {

    private final FieldRepository fieldRepository;
    private final FieldConfigRepository configRepository;
    private final FieldSensorRepository sensorRepository;
    private final CropModelRepository cropModelRepository;
    private final ObjectMapper objectMapper;

    public FieldService(FieldRepository fieldRepository,
                        FieldConfigRepository configRepository,
                        FieldSensorRepository sensorRepository,
                        CropModelRepository cropModelRepository,
                        ObjectMapper objectMapper) {
        this.fieldRepository = fieldRepository;
        this.configRepository = configRepository;
        this.sensorRepository = sensorRepository;
        this.cropModelRepository = cropModelRepository;
        this.objectMapper = objectMapper;
    }

    public List<FieldEntity> list() {
        return fieldRepository.findAll();
    }

    public FieldEntity get(Long id) {
        return fieldRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("field not found: " + id));
    }

    public Map<String, Object> toView(FieldEntity field) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", field.getId());
        m.put("name", field.getName());
        m.put("cropCode", field.getCropCode());
        m.put("cropVariety", field.getCropVariety());
        m.put("areaM2", field.getAreaM2());
        m.put("irrigationMode", field.getIrrigationMode());
        m.put("priority", field.getPriority());
        m.put("emitterTotalLph", field.getEmitterTotalLph());
        m.put("valveCode", field.getValveCode());
        m.put("fertPumpCode", field.getFertPumpCode());
        m.put("injectRatioPct", field.getInjectRatioPct());
        m.put("windowStartMin", field.getWindowStartMin());
        m.put("windowEndMin", field.getWindowEndMin());
        m.put("windowDays", field.getWindowDays());
        m.put("fertPlan", parseJson(field.getFertPlan()));
        m.put("sowingDate", field.getSowingDate());
        m.put("sensorCodes", sensorRepository.findDeviceCodesByFieldId(field.getId()));
        configRepository.findById(field.getId()).ifPresent(cfg -> m.put("config", configToView(cfg)));
        return m;
    }

    public Map<String, Object> configToView(FieldConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("thetaFc", c.getThetaFc());
        m.put("thetaWp", c.getThetaWp());
        m.put("mode", c.getMode());
        m.put("hardMaxOffsetPct", c.getHardMaxOffsetPct());
        m.put("hardMin", c.getHardMin());
        m.put("moistureLowerPct", c.getMoistureLowerPct());
        m.put("moistureUpperPct", c.getMoistureUpperPct());
        m.put("maxDurationSec", c.getMaxDurationSec());
        m.put("minIntervalH", c.getMinIntervalH());
        m.put("ecMin", c.getEcMin());
        m.put("ecMax", c.getEcMax());
        m.put("phMin", c.getPhMin());
        m.put("phMax", c.getPhMax());
        m.put("rainSkipMm", c.getRainSkipMm());
        m.put("wetRatio", c.getWetRatio());
        m.put("efficiency", c.getEfficiency());
        m.put("weatherLinked", c.isWeatherLinked());
        m.put("windMaxMs", c.getWindMaxMs());
        m.put("tempMin", c.getTempMin());
        m.put("tempMax", c.getTempMax());
        m.put("humidityMin", c.getHumidityMin());
        m.put("rainTodaySkipMm", c.getRainTodaySkipMm());
        m.put("forecastSkipMm", c.getForecastSkipMm());
        m.put("forecastDays", c.getForecastDays());
        m.put("pressureMinKpa", c.getPressureMinKpa());
        m.put("flowMinM3h", c.getFlowMinM3h());
        m.put("waterLostDelaySec", c.getWaterLostDelaySec());
        m.put("pumpOverloadA", c.getPumpOverloadA());
        m.put("enabled", c.isEnabled());
        return m;
    }

    @Transactional
    public FieldEntity create(FieldDto dto) {
        validateCrop(dto.getCropCode());
        FieldEntity f = new FieldEntity();
        applyFields(f, dto);
        f = fieldRepository.save(f);
        replaceSensors(f.getId(), dto.getSensorCodes());
        saveConfig(f.getId(), dto.getConfig(), true);
        return f;
    }

    @Transactional
    public FieldEntity update(Long id, FieldDto dto) {
        FieldEntity f = get(id);
        validateCrop(dto.getCropCode());
        applyFields(f, dto);
        f = fieldRepository.save(f);
        if (dto.getSensorCodes() != null) {
            replaceSensors(f.getId(), dto.getSensorCodes());
        }
        if (dto.getConfig() != null) {
            saveConfig(f.getId(), dto.getConfig(), false);
        }
        return f;
    }

    private void applyFields(FieldEntity f, FieldDto dto) {
        f.setName(dto.getName());
        f.setCropCode(dto.getCropCode());
        if (dto.getCropVariety() != null) {
            f.setCropVariety(dto.getCropVariety());
        }
        f.setAreaM2(dto.getAreaM2());
        if (dto.getIrrigationMode() != null) {
            f.setIrrigationMode(dto.getIrrigationMode());
        }
        if (dto.getPriority() != null) {
            f.setPriority(dto.getPriority());
        }
        f.setEmitterTotalLph(dto.getEmitterTotalLph());
        f.setValveCode(dto.getValveCode());
        if (dto.getFertPumpCode() != null) {
            f.setFertPumpCode(dto.getFertPumpCode());
        }
        if (dto.getInjectRatioPct() != null) {
            f.setInjectRatioPct(dto.getInjectRatioPct());
        }
        if (dto.getWindowStartMin() != null) {
            f.setWindowStartMin(dto.getWindowStartMin());
        }
        if (dto.getWindowEndMin() != null) {
            f.setWindowEndMin(dto.getWindowEndMin());
        }
        if (dto.getWindowDays() != null) {
            f.setWindowDays(dto.getWindowDays());
        }
        if (dto.getFertPlan() != null) {
            try {
                f.setFertPlan(objectMapper.writeValueAsString(dto.getFertPlan()));
            } catch (Exception e) {
                throw ApiException.badRequest("invalid fertPlan json: " + e.getMessage());
            }
        }
        if (dto.getSowingDate() != null) {
            f.setSowingDate(dto.getSowingDate());
        }
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private void replaceSensors(Long fieldId, List<String> codes) {
        if (codes == null) {
            return;
        }
        sensorRepository.deleteByFieldId(fieldId);
        for (String code : codes) {
            String role = guessRole(code);
            sensorRepository.save(new FieldSensor(fieldId, code, role));
        }
    }

    private String guessRole(String code) {
        String c = code == null ? "" : code.toUpperCase();
        if (c.startsWith("WS")) {
            return "WEATHER";
        }
        if (c.startsWith("FM")) {
            return "FLOW";
        }
        if (c.startsWith("PS")) {
            return "PRESSURE";
        }
        if (c.startsWith("FP") || c.startsWith("PUMP")) {
            return "PUMP";
        }
        if (c.startsWith("V")) {
            return "VALVE";
        }
        return "SOIL";
    }

    private void saveConfig(Long fieldId, FieldConfigDto dto, boolean create) {
        FieldConfig cfg = create ? new FieldConfig()
                : configRepository.findById(fieldId).orElseGet(FieldConfig::new);
        cfg.setFieldId(fieldId);
        if (dto == null) {
            if (create) {
                configRepository.save(cfg);
            }
            return;
        }
        if (dto.getThetaFc() != null) cfg.setThetaFc(dto.getThetaFc());
        if (dto.getThetaWp() != null) cfg.setThetaWp(dto.getThetaWp());
        if (dto.getMode() != null) cfg.setMode(dto.getMode());
        if (dto.getHardMaxOffsetPct() != null) cfg.setHardMaxOffsetPct(dto.getHardMaxOffsetPct());
        if (dto.getHardMin() != null) cfg.setHardMin(dto.getHardMin());
        if (dto.getMoistureLowerPct() != null) cfg.setMoistureLowerPct(dto.getMoistureLowerPct());
        if (dto.getMoistureUpperPct() != null) cfg.setMoistureUpperPct(dto.getMoistureUpperPct());
        if (dto.getMaxDurationSec() != null) cfg.setMaxDurationSec(dto.getMaxDurationSec());
        if (dto.getMinIntervalH() != null) cfg.setMinIntervalH(dto.getMinIntervalH());
        if (dto.getEcMin() != null) cfg.setEcMin(dto.getEcMin());
        if (dto.getEcMax() != null) cfg.setEcMax(dto.getEcMax());
        if (dto.getPhMin() != null) cfg.setPhMin(dto.getPhMin());
        if (dto.getPhMax() != null) cfg.setPhMax(dto.getPhMax());
        if (dto.getRainSkipMm() != null) cfg.setRainSkipMm(dto.getRainSkipMm());
        if (dto.getWetRatio() != null) cfg.setWetRatio(dto.getWetRatio());
        if (dto.getEfficiency() != null) cfg.setEfficiency(dto.getEfficiency());
        if (dto.getEnabled() != null) cfg.setEnabled(dto.getEnabled());
        if (dto.getWeatherLinked() != null) cfg.setWeatherLinked(dto.getWeatherLinked());
        if (dto.getWindMaxMs() != null) cfg.setWindMaxMs(dto.getWindMaxMs());
        if (dto.getTempMin() != null) cfg.setTempMin(dto.getTempMin());
        if (dto.getTempMax() != null) cfg.setTempMax(dto.getTempMax());
        if (dto.getHumidityMin() != null) cfg.setHumidityMin(dto.getHumidityMin());
        if (dto.getRainTodaySkipMm() != null) cfg.setRainTodaySkipMm(dto.getRainTodaySkipMm());
        if (dto.getForecastSkipMm() != null) cfg.setForecastSkipMm(dto.getForecastSkipMm());
        if (dto.getForecastDays() != null) cfg.setForecastDays(dto.getForecastDays());
        if (dto.getPressureMinKpa() != null) cfg.setPressureMinKpa(dto.getPressureMinKpa());
        if (dto.getFlowMinM3h() != null) cfg.setFlowMinM3h(dto.getFlowMinM3h());
        if (dto.getWaterLostDelaySec() != null) cfg.setWaterLostDelaySec(dto.getWaterLostDelaySec());
        if (dto.getPumpOverloadA() != null) cfg.setPumpOverloadA(dto.getPumpOverloadA());
        configRepository.save(cfg);
    }

    private void validateCrop(String cropCode) {
        if (cropCode != null && !cropCode.isBlank()
                && cropModelRepository.findById(cropCode).isEmpty()) {
            throw ApiException.badRequest("unknown cropCode: " + cropCode);
        }
    }

    public FieldConfig getConfig(Long fieldId) {
        return configRepository.findById(fieldId)
                .orElseThrow(() -> ApiException.notFound("field config not found: " + fieldId));
    }

    public CropModel getCrop(String code) {
        return cropModelRepository.findById(code)
                .orElseThrow(() -> ApiException.notFound("crop model not found: " + code));
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
