package com.evops.common;

/**
 * 对外统一返回结构：success / code / message / data。
 * 成功 code=OK；业务失败返回 BizCode 中的明确业务码。
 */
public class ApiResponse<T> {
    private boolean success;
    private String code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = true;
        response.code = BizCode.OK.getCode();
        response.message = BizCode.OK.getMessage();
        response.data = data;
        return response;
    }

    public static <T> ApiResponse<T> fail(BizCode bizCode) {
        return fail(bizCode, bizCode.getMessage());
    }

    public static <T> ApiResponse<T> fail(BizCode bizCode, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.success = false;
        response.code = bizCode.getCode();
        response.message = message;
        return response;
    }

    public boolean isSuccess() { return success; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public T getData() { return data; }
}
