package com.farm.irrigation.dto;

import java.util.List;

/** PUT /api/crop-models/{code} body. */
public class CropModelDto {

    private String code;
    private String name;
    private List<Object> stages;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public List<Object> getStages() { return stages; }
    public void setStages(List<Object> stages) { this.stages = stages; }
}
