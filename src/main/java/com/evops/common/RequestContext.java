package com.evops.common;

import java.time.LocalDateTime;

/**
 * 单次请求的写入上下文：操作人、请求号、业务时间。
 * 由 RequestContextInterceptor 从请求头解析后放入 ThreadLocal：
 * X-Operator-Id（操作人ID）、X-Request-No（幂等请求号）、X-Biz-Time（业务时间 ISO）。
 */
public final class RequestContext {

    public static final String HEADER_OPERATOR = "X-Operator-Id";
    public static final String HEADER_REQUEST_NO = "X-Request-No";
    public static final String HEADER_BIZ_TIME = "X-Biz-Time";

    private static final Long SYSTEM_OPERATOR = 0L;

    private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();

    private RequestContext() {}

    public static void set(Long operatorId, String requestNo, LocalDateTime bizTime) {
        HOLDER.set(new Ctx(operatorId, requestNo, bizTime));
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static Long operatorId() {
        Ctx ctx = HOLDER.get();
        return ctx == null || ctx.operatorId == null ? SYSTEM_OPERATOR : ctx.operatorId;
    }

    public static String requestNo() {
        Ctx ctx = HOLDER.get();
        return ctx == null ? null : ctx.requestNo;
    }

    public static LocalDateTime bizTime() {
        Ctx ctx = HOLDER.get();
        return ctx == null || ctx.bizTime == null ? LocalDateTime.now() : ctx.bizTime;
    }

    private static class Ctx {
        final Long operatorId;
        final String requestNo;
        final LocalDateTime bizTime;

        Ctx(Long operatorId, String requestNo, LocalDateTime bizTime) {
            this.operatorId = operatorId;
            this.requestNo = requestNo;
            this.bizTime = bizTime;
        }
    }
}
