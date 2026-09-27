package com.evops.common;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException ex) {
        return ApiResponse.fail(ex.getBizCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleValid(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        return ApiResponse.fail(BizCode.VALIDATION_FAILED,
                BizCode.VALIDATION_FAILED.getMessage() + "：" + detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleUnreadable(HttpMessageNotReadableException ex) {
        return ApiResponse.fail(BizCode.VALIDATION_FAILED, "请求体格式不正确");
    }

    /**
     * 库层唯一约束兜底。只按约束/索引名判定：异常消息含整条 SQL，
     * 按表名列名匹配会误判（SQL 中所有列名同时出现）。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ApiResponse<Void> handleDuplicateKey(DuplicateKeyException ex) {
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toUpperCase();
        if (msg.contains("UK_ORDER_REQUEST")) { // 请求号唯一（重复提交），先于单号判定
            return ApiResponse.fail(BizCode.REQUEST_DUPLICATED);
        }
        if (msg.contains("UK_ORDER_NO")) {
            return ApiResponse.fail(BizCode.ORDER_NO_DUPLICATED);
        }
        if (msg.contains("UK_LENS_CODE")) {
            return ApiResponse.fail(BizCode.LENS_CODE_DUPLICATED);
        }
        if (msg.contains("UK_ORDER_RX_ACTIVE")) {
            return ApiResponse.fail(BizCode.RX_EYE_DUPLICATED);
        }
        return ApiResponse.fail(BizCode.DATA_DUPLICATED);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.fail(BizCode.VALIDATION_FAILED, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception ex) {
        return ApiResponse.fail(BizCode.SYSTEM_ERROR, "系统处理失败");
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }
}
