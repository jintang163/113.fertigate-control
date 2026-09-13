package com.farm.irrigation.service;

import com.farm.irrigation.config.ControlProperties;
import com.farm.irrigation.domain.Device;
import com.farm.irrigation.repo.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 在线/离线检测：定时扫描 last_heartbeat，超时的网关/设备置离线并告警。
 * 网关离线时级联置离线其下挂设备（RS485 总线不可达，阀门/施肥泵失去云端联系，
 * 此时由边缘侧 LWT 与本地联锁保证物理安全）。
 */
@Service
public class DeviceWatchdogService {

    private static final Logger log = LoggerFactory.getLogger(DeviceWatchdogService.class);

    private final DeviceRepository deviceRepository;
    private final AlarmService alarmService;
    private final ControlProperties props;

    public DeviceWatchdogService(DeviceRepository deviceRepository,
                                 AlarmService alarmService,
                                 ControlProperties props) {
        this.deviceRepository = deviceRepository;
        this.alarmService = alarmService;
        this.props = props;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    @Transactional
    public void scanOffline() {
        Instant cutoff = Instant.now().minusSeconds(props.getDeviceOfflineSec());
        List<Device> all = deviceRepository.findAll();
        for (Device d : all) {
            if (!d.isOnline()) {
                continue;
            }
            Instant hb = d.getLastHeartbeat();
            if (hb == null || hb.isBefore(cutoff)) {
                long ageSec = hb == null ? -1 : Duration.between(hb, Instant.now()).getSeconds();
                markOffline(d, ageSec);
                if ("GATEWAY".equals(d.getType())) {
                    // 级联：该总线下所有设备离线
                    for (Device child : deviceRepository.findByGatewaySn(d.getCode())) {
                        if (child.isOnline()) {
                            markOffline(child, ageSec);
                        }
                    }
                }
            }
        }
    }

    private void markOffline(Device d, long ageSec) {
        d.setOnline(false);
        deviceRepository.save(d);
        Long fieldId = d.getLinkedField() != null ? d.getLinkedField() : null;
        String who = "GATEWAY".equals(d.getType()) ? "网关" : "设备";
        alarmService.raise("WARN", "DEVICE_OFFLINE", d.getCode(), fieldId,
                who + " " + d.getCode() + " 离线（最近心跳 "
                        + (ageSec < 0 ? "从未上报" : ageSec + "s 前") + "）",
                Map.of("type", d.getType() == null ? "" : d.getType(), "ageSec", ageSec));
        log.warn("设备离线: {} ({}) 心跳 {}s 前", d.getCode(), d.getType(), ageSec);
    }
}
