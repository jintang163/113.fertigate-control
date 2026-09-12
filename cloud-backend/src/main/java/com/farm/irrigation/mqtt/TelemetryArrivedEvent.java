package com.farm.irrigation.mqtt;

import java.time.Instant;
import java.util.Map;

/**
 * Fired after one telemetry record has been persisted. The control engine
 * listens to perform RUNNING-state soft-stop checks (volume / moisture target).
 */
public class TelemetryArrivedEvent {

    private final String deviceCode;
    private final Long fieldId;
    private final String kind;
    private final Map<String, Object> values;
    private final Instant ts;
    private final String gatewaySn;

    public TelemetryArrivedEvent(String deviceCode, Long fieldId, String kind,
                                 Map<String, Object> values, Instant ts, String gatewaySn) {
        this.deviceCode = deviceCode;
        this.fieldId = fieldId;
        this.kind = kind;
        this.values = values;
        this.ts = ts;
        this.gatewaySn = gatewaySn;
    }

    public String getDeviceCode() { return deviceCode; }
    public Long getFieldId() { return fieldId; }
    public String getKind() { return kind; }
    public Map<String, Object> getValues() { return values; }
    public Instant getTs() { return ts; }
    public String getGatewaySn() { return gatewaySn; }
}
