package com.evops.batch.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 批量后台任务主表。
 * 状态机：PENDING 待执行 → RUNNING 执行中 → SUCCEEDED / FAILED / CANCELLED；
 * FAILED / CANCELLED 可重跑（回到 PENDING，按明细断点续跑）。
 * progressPct 恒为 5 的整数倍（0~100）；cancelRequested 为协作式取消标志，单据边界生效。
 * 任务记录为运维临时数据，expireTime 到期（默认完成后 21 天）物理清理。
 */
@Getter
@Setter
@TableName("t_batch_job")
public class BatchJob extends BaseEntity {

    public static final String TYPE_RECHECK = "RECHECK";

    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    public static final String YES = "Y";
    public static final String NO = "N";

    /** 任务号，凭此查进度/取消/重跑 */
    private String jobNo;
    /** 任务类型：RECHECK */
    private String jobType;
    /** PENDING / RUNNING / SUCCEEDED / FAILED / CANCELLED */
    private String jobStatus;

    private Integer totalCount;
    private Integer processedCount;
    private Integer successCount;
    private Integer failedCount;
    /** 进度百分比，5 的整数倍 */
    private Integer progressPct;
    /** Y/N：协作式取消请求 */
    private String cancelRequested;

    /** 任务参数 JSON（如 {"storeCode":"S01"}） */
    private String paramsJson;
    /** 终态报告 JSON（neverScanned/changed/matchedFields/failedOrders 等） */
    private String resultJson;
    /** 任务级错误信息 */
    private String errorMessage;

    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
    /** 记录过期时间（finishedTime + 保留天数） */
    private LocalDateTime expireTime;
}
