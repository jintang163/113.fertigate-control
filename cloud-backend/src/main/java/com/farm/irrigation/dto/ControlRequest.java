package com.farm.irrigation.dto;

import jakarta.validation.constraints.NotNull;

public class ControlRequest {

    /** OPEN / CLOSE */
    @NotNull
    private String action;

    /** OPEN volume in m3 (optional: decision service volume used when absent) */
    private Double volumeM3;

    /** required to override AUTO mode with a manual command */
    private boolean force;

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Double getVolumeM3() { return volumeM3; }
    public void setVolumeM3(Double volumeM3) { this.volumeM3 = volumeM3; }
    public boolean isForce() { return force; }
    public void setForce(boolean force) { this.force = force; }
}
