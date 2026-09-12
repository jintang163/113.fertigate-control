package com.farm.irrigation.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.farm.irrigation.domain.Device;
import com.farm.irrigation.domain.FieldConfig;
import com.farm.irrigation.domain.FieldEntity;
import com.farm.irrigation.domain.IrrigationJob;
import com.farm.irrigation.domain.SensorLatest;
import com.farm.irrigation.repo.DeviceRepository;
import com.farm.irrigation.repo.FieldSensorRepository;
import com.farm.irrigation.repo.IrrigationJobRepository;
import com.farm.irrigation.repo.SensorLatestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Assembles a {@link FieldSnapshot} from sensor_latest rows, device online
 * state and the latest job. Multi-point soil moisture is averaged;
 * EC/pH use the latest readings.
 */
@Service
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);

    private final FieldSensorRepository fieldSensorRepository;
    private final SensorLatestRepository sensorLatestRepository;
    private final DeviceRepository deviceRepository;
    private final IrrigationJobRepository jobRepository;
    private final ObjectMapper objectMapper;

    public SnapshotService(FieldSensorRepository fieldSensorRepository,
                           SensorLatestRepository sensorLatestRepository,
                           DeviceRepository deviceRepository,
                           IrrigationJobRepository jobRepository,
                           ObjectMapper objectMapper) {
        this.fieldSensorRepository = fieldSensorRepository;
        this.sensorLatestRepository = sensorLatestRepository;
        this.deviceRepository = deviceRepository;
        this.jobRepository = jobRepository;
        this.objectMapper = objectMapper;
    }

    public FieldSnapshot build(FieldEntity field, FieldConfig cfg) {
        FieldSnapshot snap = new FieldSnapshot();
        snap.setLatitude(34.5d);

        List<String> soilCodes = fieldSensorRepository.findDeviceCodesByFieldIdAndRole(field.getId(), "SOIL");
        List<String> flowCodes = fieldSensorRepository.findDeviceCodesByFieldIdAndRole(field.getId(), "FLOW");
        List<String> weatherCodes = fieldSensorRepository.findDeviceCodesByFieldIdAndRole(field.getId(), "WEATHER");

        List<Double> moistures = new ArrayList<>();
        String primarySoil = null;
        Instant newestSoilTs = null;
        for (String code : soilCodes) {
            SensorLatest latest = sensorLatestRepository.findById(code).orElse(null);
            if (latest == null) {
                continue;
            }
            JsonNode payload = readPayload(latest);
            if (payload == null) {
                continue;
            }
            JsonNode values = payload.get("values");
            double m = firstDouble(values, "soilMoist", "soilMoisture");
            if (!Double.isNaN(m)) {
                Integer depth = depthOf(code);
                snap.getSoilZones().add(new FieldSnapshot.SoilZone(depth == null ? 0 : depth, m));
                if (!"SENSOR_FAULT".equals(payload.path("quality").asText())) {
                    moistures.add(m);
                }
                if (primarySoil == null) {
                    primarySoil = code;
                }
            }
            double ec = firstDouble(values, "ec");
            if (!Double.isNaN(ec)) {
                snap.setEc(ec);
            }
            double ph = firstDouble(values, "ph");
            if (!Double.isNaN(ph)) {
                snap.setPh(ph);
            }
            if (newestSoilTs == null || latest.getTs().isAfter(newestSoilTs)) {
                newestSoilTs = latest.getTs();
            }
        }
        snap.setPrimarySoilSensorCode(primarySoil);
        snap.setSoilTs(newestSoilTs);
        snap.setMoistureAvg(moistures.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN));

        for (String code : weatherCodes) {
            SensorLatest latest = sensorLatestRepository.findById(code).orElse(null);
            if (latest == null) {
                continue;
            }
            JsonNode payload = readPayload(latest);
            JsonNode values = payload == null ? null : payload.get("values");
            double airTemp = firstDouble(values, "airTemp");
            if (!Double.isNaN(airTemp)) {
                snap.setAirTemp(airTemp);
            }
            double tmax = firstDouble(values, "tMax");
            if (!Double.isNaN(tmax)) {
                snap.setTmax(tmax);
            } else if (!Double.isNaN(airTemp)) {
                snap.setTmax(airTemp);
            }
            double tmin = firstDouble(values, "tMin");
            if (!Double.isNaN(tmin)) {
                snap.setTmin(tmin);
            } else if (!Double.isNaN(airTemp)) {
                snap.setTmin(airTemp);
            }
            double rain = firstDouble(values, "rainfall");
            if (!Double.isNaN(rain)) {
                // values are per-period mm; daily aggregation is approximate here (latest day sum
                // is expected from the gateway; we accumulate by trusting the reported value)
                snap.setRainfallToday(snap.getRainfallToday() + Math.max(0d, rain));
            }
            snap.setWeatherTs(latest.getTs());
        }

        for (String code : flowCodes) {
            SensorLatest latest = sensorLatestRepository.findById(code).orElse(null);
            if (latest == null) {
                continue;
            }
            JsonNode payload = readPayload(latest);
            JsonNode values = payload == null ? null : payload.get("values");
            double instant = firstDouble(values, "instantFlow");
            if (!Double.isNaN(instant)) {
                snap.setInstantFlow(instant);
            }
            double total = firstDouble(values, "totalFlow");
            if (!Double.isNaN(total)) {
                snap.setTotalFlow(total);
            }
            snap.setFlowDeviceCode(code);
            snap.setFlowTs(latest.getTs());
        }

        // valve state / online
        if (field.getValveCode() != null) {
            Device valve = deviceRepository.findByCode(field.getValveCode()).orElse(null);
            if (valve != null) {
                snap.setValveOnline(valve.isOnline());
                snap.setGatewaySn(valve.getGatewaySn());
                SensorLatest vl = sensorLatestRepository.findById(field.getValveCode()).orElse(null);
                if (vl != null) {
                    snap.setValveState(vl.getState());
                    snap.setValveTs(vl.getTs());
                    JsonNode valvePayload = readPayload(vl);
                    JsonNode vv = valvePayload == null ? null : valvePayload.path("values");
                    double applied = firstDouble(vv, "appliedVolume");
                    if (!Double.isNaN(applied)) {
                        snap.setValveAppliedVolume(applied);
                    }
                }
                if (valve.getGatewaySn() != null) {
                    Device gw = deviceRepository.findByCode(valve.getGatewaySn()).orElse(null);
                    snap.setGatewayOnline(gw != null && gw.isOnline());
                }
            }
        }

        // last job timing
        Optional<IrrigationJob> lastJob = jobRepository.findFirstByFieldIdOrderByStartTimeDesc(field.getId());
        lastJob.ifPresent(j -> {
            Instant ref = j.getEndTime() != null ? j.getEndTime() : j.getStartTime();
            snap.setLastJobTime(ref);
            snap.setLastIrrigAgoH(Duration.between(ref, Instant.now()).toMillis() / 3_600_000d);
        });

        return snap;
    }

    public boolean isStale(Instant ts, int stalenessSec) {
        return ts == null || Duration.between(ts, Instant.now()).getSeconds() > stalenessSec;
    }

    /** Resolve gateway SN of a valve/device code. */
    public String gatewaySnOf(String deviceCode) {
        if (deviceCode == null) {
            return null;
        }
        return deviceRepository.findByCode(deviceCode).map(Device::getGatewaySn).orElse(null);
    }

    private Integer depthOf(String deviceCode) {
        Device d = deviceRepository.findByCode(deviceCode).orElse(null);
        if (d == null || d.getProtocolConfig() == null) {
            return null;
        }
        try {
            JsonNode cfg = objectMapper.readTree(d.getProtocolConfig());
            JsonNode depth = cfg.get("depthMm");
            return depth != null && depth.isNumber() ? depth.asInt() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode readPayload(SensorLatest latest) {
        try {
            return objectMapper.readTree(latest.getPayload());
        } catch (Exception e) {
            log.warn("bad sensor_latest payload for {}: {}", latest.getDeviceCode(), e.getMessage());
            return null;
        }
    }

    private static double firstDouble(JsonNode values, String... names) {
        if (values == null) {
            return Double.NaN;
        }
        for (String name : names) {
            JsonNode n = values.get(name);
            if (n != null && n.isNumber()) {
                return n.asDouble();
            }
        }
        return Double.NaN;
    }
}
