package com.farm.irrigation.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.service.EventService;
import com.farm.irrigation.service.HealthService;
import com.farm.irrigation.service.TelemetryIngestService;
import com.farm.irrigation.service.ValveStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Routes inbound MQTT messages to the matching handler based on the topic suffix:
 * farm/{sn}/telemetry | farm/{sn}/valve/status | farm/{sn}/events | farm/{sn}/health
 */
@Component
public class MqttMessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MqttMessageDispatcher.class);

    private final ObjectMapper objectMapper;
    private final TelemetryIngestService telemetryIngestService;
    private final ValveStatusService valveStatusService;
    private final EventService eventService;
    private final HealthService healthService;

    public MqttMessageDispatcher(ObjectMapper objectMapper,
                                 TelemetryIngestService telemetryIngestService,
                                 ValveStatusService valveStatusService,
                                 EventService eventService,
                                 HealthService healthService) {
        this.objectMapper = objectMapper;
        this.telemetryIngestService = telemetryIngestService;
        this.valveStatusService = valveStatusService;
        this.eventService = eventService;
        this.healthService = healthService;
    }

    public void dispatch(String topic, String payload) {
        String gatewaySn = MqttTopics.gatewayOf(topic);
        if (gatewaySn == null) {
            log.warn("unrecognized topic: {}", topic);
            return;
        }
        JsonNode msg;
        try {
            msg = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("invalid JSON on topic {}: {}", topic, e.getMessage());
            return;
        }

        try {
            if (topic.endsWith("/telemetry")) {
                telemetryIngestService.handleTelemetry(gatewaySn, msg);
            } else if (topic.endsWith("/valve/status")) {
                valveStatusService.handle(gatewaySn, msg);
            } else if (topic.endsWith("/events")) {
                eventService.handle(gatewaySn, msg);
            } else if (topic.endsWith("/health")) {
                healthService.handle(gatewaySn, msg);
            } else {
                log.debug("no handler for topic {}", topic);
            }
        } catch (Exception e) {
            // one bad message must never kill the MQTT callback
            log.error("handler error on topic {}: {}", topic, e.getMessage(), e);
        }
    }
}
