package com.farm.irrigation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "device")
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    /** SOIL_SENSOR / WEATHER_STATION / VALVE / FLOW_METER / GATEWAY */
    @Column(nullable = false)
    private String type;

    @Column(name = "gateway_sn")
    private String gatewaySn;

    @Column(name = "modbus_addr")
    private Integer modbusAddr;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "protocol_config", columnDefinition = "jsonb")
    private String protocolConfig;

    @Column(nullable = false)
    private boolean online = false;

    @Column(name = "queue_depth", nullable = false)
    private int queueDepth = 0;

    @Column(name = "last_heartbeat")
    private Instant lastHeartbeat;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getGatewaySn() { return gatewaySn; }
    public void setGatewaySn(String gatewaySn) { this.gatewaySn = gatewaySn; }
    public Integer getModbusAddr() { return modbusAddr; }
    public void setModbusAddr(Integer modbusAddr) { this.modbusAddr = modbusAddr; }
    public String getProtocolConfig() { return protocolConfig; }
    public void setProtocolConfig(String protocolConfig) { this.protocolConfig = protocolConfig; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }
    public int getQueueDepth() { return queueDepth; }
    public void setQueueDepth(int queueDepth) { this.queueDepth = queueDepth; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(Instant lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
}
