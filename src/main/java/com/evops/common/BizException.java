package com.evops.common;

/**
 * 业务异常：携带明确业务码，由 GlobalExceptionHandler 统一转换为响应结构。
 */
public class BizException extends RuntimeException {
    private final BizCode bizCode;

    public BizException(BizCode bizCode) {
        super(bizCode.getMessage());
        this.bizCode = bizCode;
    }

    public BizException(BizCode bizCode, String detail) {
        super(bizCode.getMessage() + "：" + detail);
        this.bizCode = bizCode;
    }

    public BizCode getBizCode() { return bizCode; }
}
