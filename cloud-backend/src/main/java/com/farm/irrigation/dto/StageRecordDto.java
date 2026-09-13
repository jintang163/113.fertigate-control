package com.farm.irrigation.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.LocalDate;

public class StageRecordDto {

    @NotBlank
    private String stageCode;
    private String stageName;
    private LocalDate recordDate;
    private String note;
    private String operator;

    public String getStageCode() { return stageCode; }
    public void setStageCode(String stageCode) { this.stageCode = stageCode; }
    public String getStageName() { return stageName; }
    public void setStageName(String stageName) { this.stageName = stageName; }
    public LocalDate getRecordDate() { return recordDate; }
    public void setRecordDate(LocalDate recordDate) { this.recordDate = recordDate; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
}
