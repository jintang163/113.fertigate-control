package com.farm.irrigation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "field_sensor")
public class FieldSensor {

    @EmbeddedId
    private FieldSensorId id;

    /** SOIL / FLOW / WEATHER */
    @Column(name = "sensor_role", nullable = false)
    private String sensorRole = "SOIL";

    public FieldSensor() {}

    public FieldSensor(Long fieldId, String deviceCode, String sensorRole) {
        this.id = new FieldSensorId(fieldId, deviceCode);
        this.sensorRole = sensorRole;
    }

    public FieldSensorId getId() { return id; }
    public void setId(FieldSensorId id) { this.id = id; }
    public String getSensorRole() { return sensorRole; }
    public void setSensorRole(String sensorRole) { this.sensorRole = sensorRole; }
}
