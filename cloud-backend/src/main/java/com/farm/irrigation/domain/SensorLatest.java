package com.farm.irrigation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "sensor_latest")
public class SensorLatest {

    @Id
    @Column(name = "device_code", length = 64)
    private String deviceCode;

    @Column(name = "field_id")
    private Long fieldId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String payload;

    /** valve state when this latest row comes from a valve: OPEN / CLOSED / OPENING / CLOSING / FAULT */
    @Column(length = 16)
    private String state;

    @Column(nullable = false)
    private Instant ts;

    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public Long getFieldId() { return fieldId; }
    public void setFieldId(Long fieldId) { this.fieldId = fieldId; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public Instant getTs() { return ts; }
    public void setTs(Instant ts) { this.ts = ts; }
}
