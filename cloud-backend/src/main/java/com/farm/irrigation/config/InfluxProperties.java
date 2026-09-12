package com.farm.irrigation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "influx")
public class InfluxProperties {

    private String url = "http://influxdb:8086";
    private String token = "";
    private String org = "farm";
    private String bucket = "telemetry";
    private boolean ensureBucket = true;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getOrg() { return org; }
    public void setOrg(String org) { this.org = org; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public boolean isEnsureBucket() { return ensureBucket; }
    public void setEnsureBucket(boolean ensureBucket) { this.ensureBucket = ensureBucket; }
}
