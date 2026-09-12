package com.farm.irrigation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "decision")
public class DecisionProperties {

    private String url = "http://decision-service:8000";
    private int timeoutMs = 5000;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
}
