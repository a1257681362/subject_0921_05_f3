package com.evops.common;

/**
 * 业务码：接口对外统一返回的明确错误标识（success=false 时 code 不为 OK）。
 */
public enum BizCode {
    OK("OK", "成功"),

    VALIDATION_FAILED("VALIDATION_FAILED", "请求参数校验未通过"),
    REQUEST_DUPLICATED("REQUEST_DUPLICATED", "重复提交，请求号已处理"),
    DATA_DUPLICATED("DATA_DUPLICATED", "数据违反唯一约束"),
    VERSION_CONFLICT("VERSION_CONFLICT", "数据已被他人修改，请刷新后重试"),

    LENS_NOT_FOUND("LENS_NOT_FOUND", "镜片不存在"),
    LENS_CODE_DUPLICATED("LENS_CODE_DUPLICATED", "镜片编码已存在"),
    LENS_ALREADY_DISCARDED("LENS_ALREADY_DISCARDED", "镜片已作废，无需重复操作"),
    LENS_NOT_DISCARDED("LENS_NOT_DISCARDED", "镜片未作废，无需恢复"),

    ORDER_NOT_FOUND("ORDER_NOT_FOUND", "加工单不存在"),
    ORDER_NO_DUPLICATED("ORDER_NO_DUPLICATED", "加工单号已存在"),
    ORDER_STATUS_ILLEGAL("ORDER_STATUS_ILLEGAL", "当前状态不允许该操作"),
    ORDER_ALREADY_CANCELLED("ORDER_ALREADY_CANCELLED", "加工单已冲销"),
    ORDER_PICKED_UP("ORDER_PICKED_UP", "加工单已取镜，不可再变更"),
    LENS_NOT_ACTIVE("LENS_NOT_ACTIVE", "镜片已作废，不能开加工单"),

    RX_EYE_REQUIRED("RX_EYE_REQUIRED", "左右眼处方必须各一条"),
    RX_EYE_DUPLICATED("RX_EYE_DUPLICATED", "同一眼别出现多条处方"),
    RX_DEGREE_STEP("RX_DEGREE_STEP", "度数必须按二十五度一档（0.25 的整数倍）"),
    RX_AXIS_RANGE("RX_AXIS_RANGE", "轴位必须在 1 到 180 之间"),
    RX_PD_RANGE("RX_PD_RANGE", "瞳距必须在 20 到 90 之间"),

    SENSITIVE_WORD_BLOCKED("SENSITIVE_WORD_BLOCKED", "文本包含须拦截的敏感词"),

    JOB_NOT_FOUND("JOB_NOT_FOUND", "后台任务不存在"),
    JOB_TYPE_UNSUPPORTED("JOB_TYPE_UNSUPPORTED", "不支持的任务类型"),
    JOB_ALREADY_RUNNING("JOB_ALREADY_RUNNING", "已有同类型任务在执行或排队，请勿重复提交"),
    JOB_STATUS_ILLEGAL("JOB_STATUS_ILLEGAL", "当前任务状态不允许该操作"),

    SYSTEM_ERROR("SYSTEM_ERROR", "系统处理失败");

    private final String code;
    private final String message;

    BizCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }
}
