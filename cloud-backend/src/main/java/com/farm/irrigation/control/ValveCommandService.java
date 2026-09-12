package com.farm.irrigation.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.domain.Device;
import com.farm.irrigation.domain.IrrigationJob;
import com.farm.irrigation.domain.ValveCommand;
import com.farm.irrigation.mqtt.MqttGateway;
import com.farm.irrigation.mqtt.MqttTopics;
import com.farm.irrigation.repo.DeviceRepository;
import com.farm.irrigation.repo.ValveCommandRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds, persists and publishes idempotent valve command envelopes
 * (farm/{gw}/valve/command) per mqtt-protocol.md section 5.
 */
@Service
public class ValveCommandService {

    private static final Logger log = LoggerFactory.getLogger(ValveCommandService.class);

    private final MqttGateway mqttGateway;
    private final ValveCommandRepository commandRepository;
    private final DeviceRepository deviceRepository;
    private final ObjectMapper objectMapper;

    public ValveCommandService(MqttGateway mqttGateway,
                               ValveCommandRepository commandRepository,
                               DeviceRepository deviceRepository,
                               ObjectMapper objectMapper) {
        this.mqttGateway = mqttGateway;
        this.commandRepository = commandRepository;
        this.deviceRepository = deviceRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ValveCommand sendOpen(IrrigationJob job, String gatewaySn,
                                 BigDecimal plannedM3, Integer maxDurationSec,
                                 BigDecimal hardMaxMoisture, String sensorCode) {
        String commandId = UUID.randomUUID().toString();
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("type", "valveCommand");
        cmd.put("gatewaySn", gatewaySn);
        cmd.put("commandId", commandId);
        cmd.put("valveCode", job.getValveCode());
        cmd.put("jobId", job.getJobBizCode() != null ? job.getJobBizCode() : String.valueOf(job.getId()));
        cmd.put("command", "OPEN");
        cmd.put("plannedVolume", plannedM3);
        cmd.put("maxDurationSec", maxDurationSec);
        cmd.put("hardMaxMoisture", hardMaxMoisture);
        cmd.put("sensorCode", sensorCode);
        cmd.put("ts", Instant.now().toEpochMilli());

        return persistAndPublish(job, gatewaySn, commandId, "OPEN", cmd);
    }

    @Transactional
    public ValveCommand sendClose(IrrigationJob job, String stopReason) {
        // resolve gateway via the valve device's gateway_sn
        String gatewaySn = job.getValveCode() == null ? null
                : deviceRepository.findByCode(job.getValveCode())
                        .map(Device::getGatewaySn).orElse(null);
        if (gatewaySn == null) {
            log.error("Cannot CLOSE job {} valve {}: gateway unknown", job.getId(), job.getValveCode());
            return null;
        }
        return sendClose(job, gatewaySn, stopReason);
    }

    @Transactional
    public ValveCommand sendClose(IrrigationJob job, String gatewaySn, String stopReason) {
        String commandId = UUID.randomUUID().toString();
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("type", "valveCommand");
        cmd.put("gatewaySn", gatewaySn);
        cmd.put("commandId", commandId);
        cmd.put("valveCode", job.getValveCode());
        cmd.put("jobId", job.getJobBizCode() != null ? job.getJobBizCode() : String.valueOf(job.getId()));
        cmd.put("command", "CLOSE");
        cmd.put("ts", Instant.now().toEpochMilli());
        if (stopReason != null) {
            cmd.put("reason", stopReason);
        }

        return persistAndPublish(job, gatewaySn, commandId, "CLOSE", cmd);
    }

    /** Standalone CLOSE for a valve that may have no known job (safety interlock). */
    @Transactional
    public ValveCommand sendBareClose(String valveCode, String gatewaySn, String reason) {
        String commandId = UUID.randomUUID().toString();
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("type", "valveCommand");
        cmd.put("gatewaySn", gatewaySn);
        cmd.put("commandId", commandId);
        cmd.put("valveCode", valveCode);
        cmd.put("command", "CLOSE");
        cmd.put("reason", reason);
        cmd.put("ts", Instant.now().toEpochMilli());

        String json;
        try {
            json = objectMapper.writeValueAsString(cmd);
        } catch (Exception e) {
            log.error("serialize bare CLOSE failed", e);
            return null;
        }
        ValveCommand vc = new ValveCommand();
        vc.setCommandId(commandId);
        vc.setValveCode(valveCode);
        vc.setCommand("CLOSE");
        vc.setStatus("SENT");
        vc.setPayload(json);
        commandRepository.save(vc);
        mqttGateway.publish(MqttTopics.valveCommand(gatewaySn), json);
        log.warn("Bare CLOSE published valve={} reason={} commandId={}", valveCode, reason, commandId);
        return vc;
    }

    /** Republish the original payload of an unacknowledged command (STARTING retry, once). */
    @Transactional
    public void republish(ValveCommand vc, String gatewaySn) {
        if (vc.getPayload() == null) {
            log.warn("cannot republish command {} without payload", vc.getCommandId());
            return;
        }
        vc.setRetryCount(vc.getRetryCount() + 1);
        commandRepository.save(vc);
        mqttGateway.publish(MqttTopics.valveCommand(gatewaySn), vc.getPayload());
        log.info("Republished command {} (retry {}) to valve {}",
                vc.getCommandId(), vc.getRetryCount(), vc.getValveCode());
    }

    private ValveCommand persistAndPublish(IrrigationJob job, String gatewaySn,
                                           String commandId, String action, Map<String, Object> cmd) {
        String json;
        try {
            json = objectMapper.writeValueAsString(cmd);
        } catch (Exception e) {
            log.error("serialize valve command failed", e);
            return null;
        }
        ValveCommand vc = new ValveCommand();
        vc.setCommandId(commandId);
        vc.setJobId(job.getId());
        vc.setValveCode(job.getValveCode());
        vc.setCommand(action);
        vc.setStatus("SENT");
        vc.setPayload(json);
        commandRepository.save(vc);

        mqttGateway.publish(MqttTopics.valveCommand(gatewaySn), json);
        log.info("Valve {} published: job={} cmd={} id={}", action, job.getId(),
                job.getValveCode(), commandId);
        return vc;
    }
}
