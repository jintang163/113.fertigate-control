package com.farm.irrigation.dto;

import java.math.BigDecimal;

/** POST /api/gateways/{sn}/config body, mirrored into the MQTT config envelope. */
public class GatewayConfigRequest {

    private Integer pollIntervalSec;

    private Interlocks interlocks;

    public static class Interlocks {
        private BigDecimal moistureHardMax;
        private BigDecimal moistureHardMin;
        private Integer sensorLostSec;
        private BigDecimal ecHigh;
        private BigDecimal phLow;
        private BigDecimal phHigh;

        public BigDecimal getMoistureHardMax() { return moistureHardMax; }
        public void setMoistureHardMax(BigDecimal moistureHardMax) { this.moistureHardMax = moistureHardMax; }
        public BigDecimal getMoistureHardMin() { return moistureHardMin; }
        public void setMoistureHardMin(BigDecimal moistureHardMin) { this.moistureHardMin = moistureHardMin; }
        public Integer getSensorLostSec() { return sensorLostSec; }
        public void setSensorLostSec(Integer sensorLostSec) { this.sensorLostSec = sensorLostSec; }
        public BigDecimal getEcHigh() { return ecHigh; }
        public void setEcHigh(BigDecimal ecHigh) { this.ecHigh = ecHigh; }
        public BigDecimal getPhLow() { return phLow; }
        public void setPhLow(BigDecimal phLow) { this.phLow = phLow; }
        public BigDecimal getPhHigh() { return phHigh; }
        public void setPhHigh(BigDecimal phHigh) { this.phHigh = phHigh; }
    }

    public Integer getPollIntervalSec() { return pollIntervalSec; }
    public void setPollIntervalSec(Integer pollIntervalSec) { this.pollIntervalSec = pollIntervalSec; }
    public Interlocks getInterlocks() { return interlocks; }
    public void setInterlocks(Interlocks interlocks) { this.interlocks = interlocks; }
}
