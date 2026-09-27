package com.evops.task.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 后台批量任务主表。
 * status：PENDING 待执行 / RUNNING 执行中 / SUCCESS 全部成功 / FAILED 有失败项（可重跑）
 *         / CANCEL_REQUESTED 已请求取消 / CANCELLED 已取消 / ARCHIVED 已归档（超保留期，不物理删除）。
 * progressPct 量化到 5% 一档（0,5,...,100）。任务号 taskNo 对外查询进度。
 */
@Getter
@Setter
@TableName("t_batch_task")
public class BatchTask extends BaseEntity {
    private String taskNo;
    private String taskType;
    /** JSON 任务参数 */
    private String params;
    private String status;
    private Integer totalCount;
    private Integer processedCount;
    private Integer successCount;
    private Integer failCount;
    private Integer progressPct;
    /** 最后处理到的目标 id（断点游标） */
    private Long lastOrderId;
    private String errorMessage;
    /** 任务级结果汇总 JSON */
    private String resultJson;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
    private LocalDateTime cancelRequestedTime;
    private LocalDateTime statusSetTime;
    private Integer expireDays;
    /** ACTIVE / ARCHIVED */
    private String expireStatus;
}
