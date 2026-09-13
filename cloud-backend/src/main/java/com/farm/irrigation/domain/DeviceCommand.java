package com.farm.irrigation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 通用执行器控制指令（电磁阀 / 施肥泵 / 调节阀），幂等。
 * action: START / STOP / SET_OPENING（opening 0-100）。
 */
@Entity
@Table(name = "device_command")
public class DeviceCommand {

    @Id
    @Column(name = "command_id", length = 64)
    private String commandId;

    @Column(name = "device_code", nullable = false, length = 64)
    private String deviceCode;

    @Column(name = "device_type", length = 32)
    private String deviceType;

    @Column(name = "job_id")
    private Long jobId;

    /** START / STOP / SET_OPENING */
    @Column(nullable = false, length = 16)
    private String action;

    /** 目标开度 0-100 */
    private Integer opening;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    /** PENDING / SENT / ACKED / DONE / FAILED / TIMEOUT / REJECTED */
    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "ack_at")
    private Instant ackAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    public String getCommandId() { return commandId; }
    public void setCommandId(String commandId) { this.commandId = commandId; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Integer getOpening() { return opening; }
    public void setOpening(Integer opening) { this.opening = opening; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getAckAt() { return ackAt; }
    public void setAckAt(Instant ackAt) { this.ackAt = ackAt; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
}
