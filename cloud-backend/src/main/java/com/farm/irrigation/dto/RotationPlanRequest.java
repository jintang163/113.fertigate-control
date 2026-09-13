package com.farm.irrigation.dto;

import java.time.LocalDate;
import java.util.List;

/** 轮灌计划生成请求。 */
public class RotationPlanRequest {

    private String name;
    private LocalDate planDate;
    /** AUTO / MANUAL */
    private String generatedBy;
    private String note;
    /** 指定灌区；空表示所有启用的灌区 */
    private List<Long> fieldIds;
    /** 首个灌区计划开灌的本地小时（0-23）；缺省取当前时刻/时窗起点 */
    private Integer startHour;
    private Integer startMinute;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LocalDate getPlanDate() { return planDate; }
    public void setPlanDate(LocalDate planDate) { this.planDate = planDate; }
    public String getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(String generatedBy) { this.generatedBy = generatedBy; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public List<Long> getFieldIds() { return fieldIds; }
    public void setFieldIds(List<Long> fieldIds) { this.fieldIds = fieldIds; }
    public Integer getStartHour() { return startHour; }
    public void setStartHour(Integer startHour) { this.startHour = startHour; }
    public Integer getStartMinute() { return startMinute; }
    public void setStartMinute(Integer startMinute) { this.startMinute = startMinute; }
}
