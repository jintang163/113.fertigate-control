package com.farm.irrigation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.common.ApiException;
import com.farm.irrigation.domain.Device;
import com.farm.irrigation.domain.FieldConfig;
import com.farm.irrigation.domain.FieldEntity;
import com.farm.irrigation.dto.GatewayConfigRequest;
import com.farm.irrigation.mqtt.MqttGateway;
import com.farm.irrigation.mqtt.MqttTopics;
import com.farm.irrigation.repo.DeviceRepository;
import com.farm.irrigation.repo.FieldConfigRepository;
import com.farm.irrigation.repo.FieldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pushes poll/interlock configuration to a gateway over
 * farm/{gw}/config (mqtt-protocol.md section 8). Thresholds are derived
 * from the fields attached to the gateway when the body omits them.
 */
@Service
public class GatewayConfigService {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfigService.class);

    private final DeviceRepository deviceRepository;
    private final FieldRepository fieldRepository;
    private final FieldConfigRepository fieldConfigRepository;
    private final MqttGateway mqttGateway;
    private final ObjectMapper objectMapper;

    public GatewayConfigService(DeviceRepository deviceRepository,
                                FieldRepository fieldRepository,
                                FieldConfigRepository fieldConfigRepository,
                                MqttGateway mqttGateway,
                                ObjectMapper objectMapper) {
        this.deviceRepository = deviceRepository;
        this.fieldRepository = fieldRepository;
        this.fieldConfigRepository = fieldConfigRepository;
        this.mqttGateway = mqttGateway;
        this.objectMapper = objectMapper;
    }

    public List<Device> listGateways() {
        return deviceRepository.findByType("GATEWAY");
    }

    @Transactional
    public Map<String, Object> pushConfig(String gatewaySn, GatewayConfigRequest req) {
        deviceRepository.findByCode(gatewaySn)
                .orElseThrow(() -> ApiException.notFound("gateway not found: " + gatewaySn));

        // derive interlock defaults from the fields served by this gateway
        FieldConfig template = findFieldConfigForGateway(gatewaySn);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", "config");
        envelope.put("gatewaySn", gatewaySn);
        envelope.put("ts", Instant.now().toEpochMilli());
        envelope.put("pollIntervalSec",
                req.getPollIntervalSec() != null ? req.getPollIntervalSec() : 10);

        Map<String, Object> interlocks = new LinkedHashMap<>();
        GatewayConfigRequest.Interlocks src = req.getInterlocks();
        interlocks.put("moistureHardMax",
                pick(src == null ? null : src.getMoistureHardMax(),
                        template == null ? null : hardMax(template)));
        interlocks.put("moistureHardMin",
                pick(src == null ? null : src.getMoistureHardMin(),
                        template == null ? null : template.getHardMin()));
        interlocks.put("sensorLostSec",
                src == null || src.getSensorLostSec() == null ? 180 : src.getSensorLostSec());
        interlocks.put("ecHigh",
                src == null ? null : pick(src.getEcHigh(),
                        template == null ? null : template.getEcMax()));
        interlocks.put("phLow",
                src == null ? null : pick(src.getPhLow(),
                        template == null ? null : template.getPhMin()));
        interlocks.put("phHigh",
                src == null ? null : pick(src.getPhHigh(),
                        template == null ? null : template.getPhMax()));
        envelope.put("interlocks", interlocks);

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw ApiException.badRequest("cannot serialize config: " + e.getMessage());
        }
        mqttGateway.publish(MqttTopics.config(gatewaySn), json);
        log.info("config pushed to gateway {}: {}", gatewaySn, json);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gatewaySn", gatewaySn);
        result.put("published", mqttGateway.isConnected());
        result.put("payload", envelope);
        return result;
    }

    private FieldConfig findFieldConfigForGateway(String gatewaySn) {
        for (FieldEntity f : fieldRepository.findAll()) {
            if (f.getValveCode() == null) {
                continue;
            }
            Device valve = deviceRepository.findByCode(f.getValveCode()).orElse(null);
            if (valve != null && gatewaySn.equals(valve.getGatewaySn())) {
                return fieldConfigRepository.findById(f.getId()).orElse(null);
            }
        }
        return null;
    }

    private static BigDecimal hardMax(FieldConfig cfg) {
        if (cfg.getThetaFc() == null) {
            return null;
        }
        double off = cfg.getHardMaxOffsetPct() == null ? 3d : cfg.getHardMaxOffsetPct().doubleValue();
        return cfg.getThetaFc().add(BigDecimal.valueOf(off));
    }

    private static BigDecimal pick(BigDecimal explicit, BigDecimal derived) {
        return explicit != null ? explicit : derived;
    }
}
