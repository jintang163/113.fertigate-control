package com.farm.irrigation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.control")
public class ControlProperties {

    /** Automatic evaluation period, ms (default 5 minutes). Used when evaluateCron is blank. */
    private long evaluateIntervalMs = 300_000;
    /** Optional Spring cron expression (overrides evaluateIntervalMs when set), e.g. "0 0/5 * * * ?". */
    private String evaluateCron;
    /** STARTING: wait for valve OPEN ACK before retry. */
    private long commandAckTimeoutMs = 30_000;
    /** STARTING: final give-up time after one retry. */
    private long commandFinalTimeoutMs = 60_000;
    /** Sensor data is considered fresh within this many seconds. */
    private int stalenessSec = 600;
    /** Device/gateway is marked offline when its heartbeat is older than this. */
    private int deviceOfflineSec = 180;
    /** 灌溉时窗与轮灌排程使用的时区。 */
    private String zoneId = "Asia/Shanghai";
    /** 轮灌计划相邻灌区切换间隔（秒，管道泄压/稳流）。 */
    private long rotationGapSec = 300;
    /** 轮灌到期项释放检查周期（秒）。 */
    private long rotationTickSec = 30;
    private double latitude = 34.5;

    public long getEvaluateIntervalMs() { return evaluateIntervalMs; }
    public void setEvaluateIntervalMs(long evaluateIntervalMs) { this.evaluateIntervalMs = evaluateIntervalMs; }
    public String getEvaluateCron() { return evaluateCron; }
    public void setEvaluateCron(String evaluateCron) { this.evaluateCron = evaluateCron; }
    public long getCommandAckTimeoutMs() { return commandAckTimeoutMs; }
    public void setCommandAckTimeoutMs(long commandAckTimeoutMs) { this.commandAckTimeoutMs = commandAckTimeoutMs; }
    public long getCommandFinalTimeoutMs() { return commandFinalTimeoutMs; }
    public void setCommandFinalTimeoutMs(long commandFinalTimeoutMs) { this.commandFinalTimeoutMs = commandFinalTimeoutMs; }
    public int getStalenessSec() { return stalenessSec; }
    public void setStalenessSec(int stalenessSec) { this.stalenessSec = stalenessSec; }
    public int getDeviceOfflineSec() { return deviceOfflineSec; }
    public void setDeviceOfflineSec(int deviceOfflineSec) { this.deviceOfflineSec = deviceOfflineSec; }
    public String getZoneId() { return zoneId; }
    public void setZoneId(String zoneId) { this.zoneId = zoneId; }
    public long getRotationGapSec() { return rotationGapSec; }
    public void setRotationGapSec(long rotationGapSec) { this.rotationGapSec = rotationGapSec; }
    public long getRotationTickSec() { return rotationTickSec; }
    public void setRotationTickSec(long rotationTickSec) { this.rotationTickSec = rotationTickSec; }
    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }
}
