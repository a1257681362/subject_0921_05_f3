package com.evops.task.dto;

import com.fasterxml.jackson.databind.JsonNode;

import javax.validation.constraints.NotBlank;

/**
 * 创建后台批量任务：任务类型 + 任务参数（透传给对应处理器，如 {"storeCode":"S01"}）。
 */
public class TaskCreateRequest {

    @NotBlank(message = "任务类型不能为空")
    private String taskType;

    /** 任务参数，原样 JSON 透传，可为空 */
    private JsonNode params;

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public JsonNode getParams() { return params; }
    public void setParams(JsonNode params) { this.params = params; }
}
