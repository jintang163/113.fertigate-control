package com.farm.irrigation.service;

import com.farm.irrigation.common.ApiException;
import com.farm.irrigation.domain.Device;
import com.farm.irrigation.repo.DeviceRepository;
import com.farm.irrigation.repo.FieldSensorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceRepository deviceRepository;
    private final FieldSensorRepository fieldSensorRepository;

    public DeviceService(DeviceRepository deviceRepository,
                         FieldSensorRepository fieldSensorRepository) {
        this.deviceRepository = deviceRepository;
        this.fieldSensorRepository = fieldSensorRepository;
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
        d.setOnline(true);
        d.setLastHeartbeat(ts == null ? Instant.now() : ts);
        deviceRepository.save(d);
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
        List<Long> ids = fieldSensorRepository.findFieldIdsByDeviceCode(deviceCode);
        return ids.isEmpty() ? null : ids.get(0);
    }
}
