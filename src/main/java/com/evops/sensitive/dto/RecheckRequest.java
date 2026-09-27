package com.evops.sensitive.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 反向核对请求：可按门店收窄范围，空为全量。
 */
@Getter
@Setter
public class RecheckRequest {
    private String storeCode;
}
