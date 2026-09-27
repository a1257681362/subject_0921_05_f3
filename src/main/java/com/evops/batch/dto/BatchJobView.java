package com.evops.batch.dto;

import com.evops.batch.entity.BatchJob;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 任务视图：状态、5% 粒度进度、计数、参数、终态报告（result）、错误与关键时间。
 * result 为 result_json 解析后的结构（neverScanned/changed/matchedFields/failedOrders 等）。
 */
@Getter
@Setter
public class BatchJobView {

    private String jobNo;
    private String jobType;
    private String jobStatus;

    private Integer totalCount;
    private Integer processedCount;
    private Integer successCount;
    private Integer failedCount;
    /** 进度百分比，恒为 5 的整数倍 0~100 */
    private Integer progressPct;
    private boolean cancelRequested;

    private JsonNode params;
    /** 完成后的核对报告；未完成/无报告为 null */
    private JsonNode result;
    private String errorMessage;

    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
    private LocalDateTime expireTime;
    private LocalDateTime createdTime;

    public static BatchJobView from(BatchJob job, ObjectMapper objectMapper) {
        BatchJobView view = new BatchJobView();
        view.jobNo = job.getJobNo();
        view.jobType = job.getJobType();
        view.jobStatus = job.getJobStatus();
        view.totalCount = job.getTotalCount();
        view.processedCount = job.getProcessedCount();
        view.successCount = job.getSuccessCount();
        view.failedCount = job.getFailedCount();
        view.progressPct = job.getProgressPct();
        view.cancelRequested = BatchJob.YES.equals(job.getCancelRequested());
        view.params = readQuietly(objectMapper, job.getParamsJson());
        view.result = readQuietly(objectMapper, job.getResultJson());
        view.errorMessage = job.getErrorMessage();
        view.startedTime = job.getStartedTime();
        view.finishedTime = job.getFinishedTime();
        view.expireTime = job.getExpireTime();
        view.createdTime = job.getCreatedTime();
        return view;
    }

    private static JsonNode readQuietly(ObjectMapper objectMapper, String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            // 展示层解析失败不影响查任务，原始内容可从库中取
            return null;
        }
    }
}
