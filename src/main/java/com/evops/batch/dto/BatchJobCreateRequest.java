package com.evops.batch.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;

/**
 * 提交批量任务：当前仅支持 jobType=RECHECK（加工单敏感词全量反向核对）；
 * storeCode 可空，传入则只核对该门店。
 */
@Getter
@Setter
public class BatchJobCreateRequest {

    @NotBlank(message = "任务类型不能为空")
    private String jobType;

    /** 可选：按门店收窄范围，空为全量 */
    private String storeCode;
}
