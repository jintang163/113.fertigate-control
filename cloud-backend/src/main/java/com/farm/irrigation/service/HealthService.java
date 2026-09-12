package com.farm.irrigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.farm.irrigation.domain.Device;
import com.farm.irrigation.repo.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Handles inbound {@code farm/{gw}/health}: gateway online status,
 * firmware, uptime and outbox queue depth.
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private final DeviceRepository deviceRepository;
    private final DeviceService deviceService;

    public HealthService(DeviceRepository deviceRepository, DeviceService deviceService) {
        this.deviceRepository = deviceRepository;
        this.deviceService = deviceService;
    }

    @Transactional
    public void handle(String gatewaySn, JsonNode msg) {
        Instant ts = epochMillis(msg.get("ts"));
        int queueDepth = 0;
        JsonNode qd = msg.get("queueDepth");
        if (qd != null && qd.isNumber()) {
            queueDepth = qd.asInt();
        }
        String fw = msg.has("fw") ? msg.get("fw").asText() : null;
        long uptimeSec = msg.has("uptimeSec") ? msg.get("uptimeSec").asLong() : 0L;

        deviceService.markHeartbeat(gatewaySn, ts == null ? Instant.now() : ts);
        deviceService.markQueueDepth(gatewaySn, queueDepth);
        // reachable gateway ⇒ its RS485 devices (incl. valves without own telemetry) online
        deviceService.markGatewayDevicesOnline(gatewaySn, ts);

        Device gw = deviceRepository.findByCode(gatewaySn).orElse(null);
        if (gw != null && fw != null) {
            // keep fw inside protocol_config without clobbering other keys
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode cfg;
                if (gw.getProtocolConfig() != null && !gw.getProtocolConfig().isBlank()) {
                    cfg = (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(gw.getProtocolConfig());
                } else {
                    cfg = om.createObjectNode();
                }
                cfg.put("fw", fw);
                cfg.put("uptimeSec", uptimeSec);
                gw.setProtocolConfig(om.writeValueAsString(cfg));
                deviceRepository.save(gw);
            } catch (Exception e) {
                log.debug("health fw merge failed: {}", e.getMessage());
            }
        }
        log.debug("health gw={} queueDepth={} uptime={}s fw={}", gatewaySn, queueDepth, uptimeSec, fw);
    }

    private static Instant epochMillis(JsonNode node) {
        if (node == null || node.isNull() || !node.canConvertToLong()) {
            return null;
        }
        return Instant.ofEpochMilli(node.asLong());
    }
}
