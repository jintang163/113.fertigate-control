package com.farm.irrigation.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.domain.Device;
import com.farm.irrigation.domain.DeviceCommand;
import com.farm.irrigation.domain.FieldEntity;
import com.farm.irrigation.domain.IrrigationJob;
import com.farm.irrigation.mqtt.MqttGateway;
import com.farm.irrigation.mqtt.MqttTopics;
import com.farm.irrigation.repo.DeviceCommandRepository;
import com.farm.irrigation.repo.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 通用执行器指令下发（farm/{gw}/device/command）：施肥泵启停/开度、调节阀开度。
 * 与 valve/command 同样幂等（commandId），持久化到 device_command。
 */
@Service
public class DeviceCommandService {

    private static final Logger log = LoggerFactory.getLogger(DeviceCommandService.class);

    private final MqttGateway mqttGateway;
    private final DeviceCommandRepository commandRepository;
    private final DeviceRepository deviceRepository;
    private final ObjectMapper objectMapper;

    public DeviceCommandService(MqttGateway mqttGateway,
                                DeviceCommandRepository commandRepository,
                                DeviceRepository deviceRepository,
                                ObjectMapper objectMapper) {
        this.mqttGateway = mqttGateway;
        this.commandRepository = commandRepository;
        this.deviceRepository = deviceRepository;
        this.objectMapper = objectMapper;
    }

    /** 通用下发。 */
    @Transactional
    public DeviceCommand send(String deviceCode, String action, Integer opening,
                              Long jobId, Map<String, Object> extra) {
        Device device = deviceRepository.findByCode(deviceCode).orElse(null);
        if (device == null) {
            log.warn("device command target not registered: {}", deviceCode);
            return null;
        }
        String gatewaySn = device.getGatewaySn();
        if (gatewaySn == null || gatewaySn.isBlank()) {
            log.warn("device {} has no gateway, cannot command", deviceCode);
            return null;
        }
        String commandId = UUID.randomUUID().toString();
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("type", "deviceCommand");
        cmd.put("gatewaySn", gatewaySn);
        cmd.put("commandId", commandId);
        cmd.put("deviceCode", deviceCode);
        cmd.put("deviceType", device.getType());
        cmd.put("action", action);
        if (opening != null) {
            cmd.put("opening", opening);
        }
        if (jobId != null) {
            cmd.put("jobId", jobId);
        }
        if (extra != null) {
            cmd.putAll(extra);
        }
        cmd.put("ts", Instant.now().toEpochMilli());

        String json;
        try {
            json = objectMapper.writeValueAsString(cmd);
        } catch (Exception e) {
            log.error("serialize device command failed", e);
            return null;
        }

        DeviceCommand entity = new DeviceCommand();
        entity.setCommandId(commandId);
        entity.setDeviceCode(deviceCode);
        entity.setDeviceType(device.getType());
        entity.setJobId(jobId);
        entity.setAction(action);
        entity.setOpening(opening);
        entity.setPayload(json);
        entity.setStatus("SENT");
        commandRepository.save(entity);

        mqttGateway.publish(MqttTopics.deviceCommand(gatewaySn), json);
        log.info("Device command published: {} {} opening={} job={} id={}",
                action, deviceCode, opening, jobId, commandId);
        return entity;
    }

    /** 启动灌区注肥泵（opening 0-100；null 时默认 100）。 */
    @Transactional
    public DeviceCommand startPump(FieldEntity field, Integer opening, Long jobId) {
        if (field.getFertPumpCode() == null || field.getFertPumpCode().isBlank()) {
            return null;
        }
        int op = opening == null ? 100 : Math.max(0, Math.min(100, opening));
        return send(field.getFertPumpCode(), "START", op, jobId,
                Map.of("injectRatioPct", field.getInjectRatioPct() == null
                        ? 0d : field.getInjectRatioPct().doubleValue()));
    }

    /** 停止灌区注肥泵。任何异常不外抛（安全路径调用）。 */
    @Transactional
    public DeviceCommand stopPump(FieldEntity field, String reason) {
        if (field.getFertPumpCode() == null || field.getFertPumpCode().isBlank()) {
            return null;
        }
        try {
            return send(field.getFertPumpCode(), "STOP", 0, null,
                    reason == null ? Map.of() : Map.of("reason", reason));
        } catch (Exception e) {
            log.error("stopPump {} failed: {}", field.getFertPumpCode(), e.getMessage());
            return null;
        }
    }

    /** 调节阀/电磁阀开度（支持开度的执行器）。 */
    @Transactional
    public DeviceCommand setOpening(String deviceCode, int opening, Long jobId) {
        return send(deviceCode, "SET_OPENING", Math.max(0, Math.min(100, opening)), jobId, Map.of());
    }

    @Transactional
    public void markAck(String commandId, String status) {
        commandRepository.findById(commandId).ifPresent(c -> {
            c.setStatus(status);
            c.setAckAt(Instant.now());
            commandRepository.save(c);
        });
    }

    /** 读取注肥泵额定流量 L/h（设备 params.capacityLph），缺省 0。 */
    public double pumpCapacityLph(String pumpCode) {
        Device d = deviceRepository.findByCode(pumpCode).orElse(null);
        if (d == null || d.getParams() == null) {
            return 0d;
        }
        try {
            var node = objectMapper.readTree(d.getParams());
            var cap = node.get("capacityLph");
            return cap != null && cap.isNumber() ? cap.asDouble() : 0d;
        } catch (Exception e) {
            return 0d;
        }
    }
}
