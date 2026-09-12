package com.farm.irrigation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "mqtt")
public class MqttProperties {

    private String host = "tcp://emqx:1883";
    private String clientId = "cloud-backend";
    private String username = "";
    private String password = "";
    private int keepAliveSec = 30;
    private int connectionTimeoutSec = 10;
    private Topics topics = new Topics();

    public static class Topics {
        private String telemetry = "farm/+/telemetry";
        private String valveStatus = "farm/+/valve/status";
        private String events = "farm/+/events";
        private String health = "farm/+/health";

        public String getTelemetry() { return telemetry; }
        public void setTelemetry(String telemetry) { this.telemetry = telemetry; }
        public String getValveStatus() { return valveStatus; }
        public void setValveStatus(String valveStatus) { this.valveStatus = valveStatus; }
        public String getEvents() { return events; }
        public void setEvents(String events) { this.events = events; }
        public String getHealth() { return health; }
        public void setHealth(String health) { this.health = health; }
    }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public int getKeepAliveSec() { return keepAliveSec; }
    public void setKeepAliveSec(int keepAliveSec) { this.keepAliveSec = keepAliveSec; }
    public int getConnectionTimeoutSec() { return connectionTimeoutSec; }
    public void setConnectionTimeoutSec(int connectionTimeoutSec) { this.connectionTimeoutSec = connectionTimeoutSec; }
    public Topics getTopics() { return topics; }
    public void setTopics(Topics topics) { this.topics = topics; }
}
