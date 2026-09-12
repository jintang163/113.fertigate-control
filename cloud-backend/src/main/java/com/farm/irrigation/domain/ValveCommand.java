package com.farm.irrigation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "valve_command")
public class ValveCommand {

    @Id
    @Column(name = "command_id", length = 64)
    private String commandId;

    @Column(name = "job_id")
    private Long jobId;

    @Column(name = "valve_code", nullable = false)
    private String valveCode;

    /** OPEN / CLOSE */
    @Column(nullable = false, length = 8)
    private String command;

    /** PENDING / SENT / OPENED / CLOSED / REJECTED / TIMEOUT / FAILED */
    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "ack_at")
    private Instant ackAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    public String getCommandId() { return commandId; }
    public void setCommandId(String commandId) { this.commandId = commandId; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public String getValveCode() { return valveCode; }
    public void setValveCode(String valveCode) { this.valveCode = valveCode; }
    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getAckAt() { return ackAt; }
    public void setAckAt(Instant ackAt) { this.ackAt = ackAt; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
}
