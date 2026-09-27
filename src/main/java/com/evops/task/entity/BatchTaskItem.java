package com.evops.task.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 批量任务处理项（每个被处理对象一行，断点续跑依据）。
 * itemStatus：PENDING 待处理 / SUCCESS 成功 / FAILED 失败（重跑只重试这些）/ SKIPPED 跳过。
 * 同一任务内 itemRef 唯一（uk_batch_task_item 兜底）。
 */
@Getter
@Setter
@TableName("t_batch_task_item")
public class BatchTaskItem extends BaseEntity {
    private Long taskId;
    /** 处理对象标识（recheck 为加工单号） */
    private String itemRef;
    private Long targetId;
    private String itemStatus;
    private String errorMessage;
    /** 单项结果摘要 JSON */
    private String resultJson;
    private Integer attempt;
}
