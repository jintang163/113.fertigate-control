package com.farm.irrigation.dto;

import jakarta.validation.constraints.NotBlank;

public class DeviceDto {

    @NotBlank
    private String code;
    private String name;
    /** SOIL_SENSOR / WEATHER_STATION / VALVE / FLOW_METER / FERT_PUMP / PRESSURE_SENSOR */
    @NotBlank
    private String type;
    private String gatewaySn;
    private Integer modbusAddr;
    private Object protocolConfig;
    private Object params;
    private Long linkedField;
    /** ENABLED / DISABLED — 投运状态 */
    private String status;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getGatewaySn() { return gatewaySn; }
    public void setGatewaySn(String gatewaySn) { this.gatewaySn = gatewaySn; }
    public Integer getModbusAddr() { return modbusAddr; }
    public void setModbusAddr(Integer modbusAddr) { this.modbusAddr = modbusAddr; }
    public Object getProtocolConfig() { return protocolConfig; }
    public void setProtocolConfig(Object protocolConfig) { this.protocolConfig = protocolConfig; }
    public Object getParams() { return params; }
    public void setParams(Object params) { this.params = params; }
    public Long getLinkedField() { return linkedField; }
    public void setLinkedField(Long linkedField) { this.linkedField = linkedField; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
