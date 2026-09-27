package com.evops.order.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.Size;

/**
 * 状态流转/冲销/恢复请求体，备注可选，versionNo 可选（传入则做乐观并发校验）。
 */
@Getter
@Setter
public class OrderActionRequest {
    @Size(max = 255)
    private String remark;
    private Long versionNo;
}
