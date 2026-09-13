package com.farm.irrigation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.common.ApiException;
import com.farm.irrigation.domain.Device;
import com.farm.irrigation.dto.DeviceDto;
import com.farm.irrigation.repo.DeviceRepository;
import com.farm.irrigation.repo.FieldSensorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    /** 可注册设备类型 */
    public static final Set<String> DEVICE_TYPES = Set.of(
            "SOIL_SENSOR", "WEATHER_STATION", "VALVE", "FLOW_METER",
            "FERT_PUMP", "PRESSURE_SENSOR", "GATEWAY");

    private final DeviceRepository deviceRepository;
    private final FieldSensorRepository fieldSensorRepository;
    private final ObjectMapper objectMapper;

    public DeviceService(DeviceRepository deviceRepository,
                         FieldSensorRepository fieldSensorRepository,
                         ObjectMapper objectMapper) {
        this.deviceRepository = deviceRepository;
        this.fieldSensorRepository = fieldSensorRepository;
        this.objectMapper = objectMapper;
    }

    public List<Device> list(String gatewaySn, String type) {
        if (gatewaySn != null && !gatewaySn.isBlank() && type != null && !type.isBlank()) {
            return deviceRepository.findByGatewaySnAndType(gatewaySn, type);
        }
        if (gatewaySn != null && !gatewaySn.isBlank()) {
            return deviceRepository.findByGatewaySn(gatewaySn);
        }
        if (type != null && !type.isBlank()) {
            return deviceRepository.findByType(type);
        }
        return deviceRepository.findAll();
    }

    public Device getByCode(String code) {
        return deviceRepository.findByCode(code)
                .orElseThrow(() -> ApiException.notFound("device not found: " + code));
    }

    public Device findByCodeOrNull(String code) {
        return deviceRepository.findByCode(code).orElse(null);
    }

    // ------------------------------------------------------------------
    // 注册 / 更新 / 停用（设备管理）
    // ------------------------------------------------------------------

    @Transactional
    public Device register(DeviceDto dto) {
        String type = normalizeType(dto.getType());
        if (deviceRepository.existsByCode(dto.getCode())) {
            throw ApiException.badRequest("device code already exists: " + dto.getCode());
        }
        Device d = new Device();
        d.setCode(dto.getCode());
        d.setType(type);
        applyDto(d, dto);
        d.setOnline(false);
        if (d.getStatus() == null || "UNKNOWN".equals(d.getStatus())) {
            d.setStatus("ENABLED");
        }
        d.setRegisteredAt(Instant.now());
        Device saved = deviceRepository.save(d);
        log.info("设备注册: code={} type={} gateway={}", saved.getCode(), saved.getType(), saved.getGatewaySn());
        return saved;
    }

    @Transactional
    public Device update(String code, DeviceDto dto) {
        Device d = getByCode(code);
        if (dto.getType() != null && !dto.getType().isBlank()) {
            d.setType(normalizeType(dto.getType()));
        }
        applyDto(d, dto);
        return deviceRepository.save(d);
    }

    /** 停用/启用设备（逻辑状态，不删除历史数据）。 */
    @Transactional
    public Device setStatus(String code, String status) {
        if (!Set.of("ENABLED", "DISABLED", "FAULT", "UNKNOWN").contains(status)) {
            throw ApiException.badRequest("invalid device status: " + status);
        }
        Device d = getByCode(code);
        d.setStatus(status);
        return deviceRepository.save(d);
    }

    @Transactional
    public void delete(String code) {
        Device d = getByCode(code);
        deviceRepository.delete(d);
        log.info("设备删除: {}", code);
    }

    private void applyDto(Device d, DeviceDto dto) {
        if (dto.getName() != null) {
            d.setName(dto.getName());
        }
        if (dto.getGatewaySn() != null) {
            d.setGatewaySn(dto.getGatewaySn());
        }
        if (dto.getModbusAddr() != null) {
            d.setModbusAddr(dto.getModbusAddr());
        }
        if (dto.getProtocolConfig() != null) {
            d.setProtocolConfig(writeJson(dto.getProtocolConfig(), "{}"));
        }
        if (dto.getParams() != null) {
            d.setParams(writeJson(dto.getParams(), "{}"));
        }
        if (dto.getLinkedField() != null) {
            d.setLinkedField(dto.getLinkedField());
        }
        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            String s = dto.getStatus().toUpperCase();
            if (!Set.of("ENABLED", "DISABLED", "FAULT", "UNKNOWN").contains(s)) {
                throw ApiException.badRequest("invalid device status: " + s);
            }
            d.setStatus(s);
        }
    }

    private String normalizeType(String type) {
        if (type == null) {
            throw ApiException.badRequest("device type required");
        }
        String t = type.trim().toUpperCase();
        if (!DEVICE_TYPES.contains(t)) {
            throw ApiException.badRequest(
                    "unsupported device type: " + t + ", allowed: " + DEVICE_TYPES);
        }
        return t;
    }

    private String writeJson(Object o, String fallback) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // 心跳 / 在线性
    // ------------------------------------------------------------------

    @Transactional
    public void markHeartbeat(String code, Instant ts) {
        if (code == null) {
            return;
        }
        Device d = deviceRepository.findByCode(code).orElse(null);
        if (d == null) {
            log.debug("heartbeat for unknown device {}, ignoring", code);
            return;
        }
        boolean wasOffline = !d.isOnline();
        d.setOnline(true);
        d.setLastHeartbeat(ts == null ? Instant.now() : ts);
        if (!"DISABLED".equals(d.getStatus()) && !"FAULT".equals(d.getStatus())) {
            d.setStatus("ENABLED");
        }
        deviceRepository.save(d);
        if (wasOffline) {
            log.info("设备上线: {}", code);
        }
    }

    @Transactional
    public void markQueueDepth(String code, int queueDepth) {
        Device d = deviceRepository.findByCode(code).orElse(null);
        if (d != null) {
            d.setQueueDepth(queueDepth);
            deviceRepository.save(d);
        }
    }

    /**
     * A live gateway heartbeat proves the whole RS485 bus behind it is reachable,
     * including devices that do not independently report (e.g. solenoid valves
     * whose state only changes on command). Mark every device of the gateway online.
     */
    @Transactional
    public void markGatewayDevicesOnline(String gatewaySn, Instant ts) {
        Instant now = ts == null ? Instant.now() : ts;
        List<Device> devices = deviceRepository.findByGatewaySn(gatewaySn);
        for (Device d : devices) {
            d.setOnline(true);
            if (d.getLastHeartbeat() == null) {
                d.setLastHeartbeat(now);
            }
        }
        deviceRepository.saveAll(devices);
    }

    /** Resolve which field a device belongs to, or null. */
    public Long fieldIdOfDevice(String deviceCode) {
        if (deviceCode == null) {
            return null;
        }
        Device d = deviceRepository.findByCode(deviceCode).orElse(null);
        if (d != null && d.getLinkedField() != null) {
            return d.getLinkedField();
        }
        List<Long> ids = fieldSensorRepository.findFieldIdsByDeviceCode(deviceCode);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
