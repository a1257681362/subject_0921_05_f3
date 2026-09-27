package com.evops.sensitive.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

/**
 * 试扫请求：前端提交前调用，拿命中位置在原文上标记。
 */
@Getter
@Setter
public class ScanRequest {
    @NotNull(message = "待扫描文本不能为空")
    @Size(max = 1000)
    private String text;
}
