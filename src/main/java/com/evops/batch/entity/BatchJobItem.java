package com.evops.batch.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 批量任务明细：每张加工单一行，是断点重跑的依据。
 * SUCCESS 的单重跑时跳过；FAILED 的单重跑时重新处理并更新本行。
 */
@Getter
@Setter
@TableName("t_batch_job_item")
public class BatchJobItem extends BaseEntity {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    private Long jobId;
    private Long orderId;
    private String orderNo;
    /** SUCCESS / FAILED */
    private String itemStatus;
    /** 失败原因（截断存储） */
    private String failReason;
    /** 该单核对结果 JSON（neverScanned/字段差异），供断点后最终汇总 */
    private String resultJson;
}
