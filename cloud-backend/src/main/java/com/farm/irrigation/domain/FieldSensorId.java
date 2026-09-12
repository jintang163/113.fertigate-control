package com.farm.irrigation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class FieldSensorId implements Serializable {

    @Column(name = "field_id")
    private Long fieldId;

    @Column(name = "device_code")
    private String deviceCode;

    public FieldSensorId() {}

    public FieldSensorId(Long fieldId, String deviceCode) {
        this.fieldId = fieldId;
        this.deviceCode = deviceCode;
    }

    public Long getFieldId() { return fieldId; }
    public void setFieldId(Long fieldId) { this.fieldId = fieldId; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FieldSensorId that)) return false;
        return Objects.equals(fieldId, that.fieldId) && Objects.equals(deviceCode, that.deviceCode);
    }

    @Override
    public int hashCode() { return Objects.hash(fieldId, deviceCode); }
}
