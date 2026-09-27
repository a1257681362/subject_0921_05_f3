package com.evops.config;

import com.evops.common.RequestContext;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 从请求头解析写入上下文：操作人 / 请求号 / 业务时间。
 */
@Component
public class RequestContextInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String operator = request.getHeader(RequestContext.HEADER_OPERATOR);
        String requestNo = request.getHeader(RequestContext.HEADER_REQUEST_NO);
        String bizTime = request.getHeader(RequestContext.HEADER_BIZ_TIME);

        Long operatorId = null;
        if (operator != null && !operator.trim().isEmpty()) {
            try {
                operatorId = Long.valueOf(operator.trim());
            } catch (NumberFormatException ignore) {
                // 非法操作人头按系统操作人处理，不阻断请求
            }
        }
        LocalDateTime bizDateTime = null;
        if (bizTime != null && !bizTime.trim().isEmpty()) {
            try {
                bizDateTime = LocalDateTime.parse(bizTime.trim());
            } catch (DateTimeParseException ignore) {
                bizDateTime = LocalDateTime.now();
            }
        }
        RequestContext.set(operatorId, requestNo, bizDateTime);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        RequestContext.clear();
    }
}
