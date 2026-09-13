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

    /** SOIL_SENSOR / WEATHER_STATION / VALVE / FLOW_METER / FERT_PUMP / PRESSURE_SENSOR / GATEWAY */
    @Column(nullable = false)
    private String type;

    /** 设备别名（中文名） */
    private String name;

    @Column(name = "gateway_sn")
    private String gatewaySn;

    @Column(name = "modbus_addr")
    private Integer modbusAddr;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "protocol_config", columnDefinition = "jsonb")
    private String protocolConfig;

    /** 通信在线性：心跳驱动 */
    @Column(nullable = false)
    private boolean online = false;

    /** 投运状态：UNKNOWN / ENABLED / DISABLED / FAULT */
    @Column(nullable = false)
    private String status = "UNKNOWN";

    /** 执行器当前开度 0-100（VALVE / FERT_PUMP） */
    private Integer opening;

    @Column(name = "queue_depth", nullable = false)
    private int queueDepth = 0;

    @Column(name = "linked_field")
    private Long linkedField;

    @Column(name = "last_heartbeat")
    private Instant lastHeartbeat;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt = Instant.now();

    /** 设备参数：capacityLph / linkedPressureSensor / normalOpen … */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String params = "{}";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getGatewaySn() { return gatewaySn; }
    public void setGatewaySn(String gatewaySn) { this.gatewaySn = gatewaySn; }
    public Integer getModbusAddr() { return modbusAddr; }
    public void setModbusAddr(Integer modbusAddr) { this.modbusAddr = modbusAddr; }
    public String getProtocolConfig() { return protocolConfig; }
    public void setProtocolConfig(String protocolConfig) { this.protocolConfig = protocolConfig; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getOpening() { return opening; }
    public void setOpening(Integer opening) { this.opening = opening; }
    public int getQueueDepth() { return queueDepth; }
    public void setQueueDepth(int queueDepth) { this.queueDepth = queueDepth; }
    public Long getLinkedField() { return linkedField; }
    public void setLinkedField(Long linkedField) { this.linkedField = linkedField; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(Instant lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }
    public String getParams() { return params; }
    public void setParams(String params) { this.params = params; }
}
