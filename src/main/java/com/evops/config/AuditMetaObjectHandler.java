package com.evops.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.evops.common.RequestContext;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 写入时自动填充审计字段：操作人、请求号、业务时间、创建/更新时间、初始版本号。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime bizTime = RequestContext.bizTime();
        Long operatorId = RequestContext.operatorId();
        String requestNo = RequestContext.requestNo();

        strictFillIfNull(metaObject, "createdTime", now);
        strictFillIfNull(metaObject, "updatedTime", now);
        strictFillIfNull(metaObject, "createdBy", operatorId);
        strictFillIfNull(metaObject, "updatedBy", operatorId);
        strictFillIfNull(metaObject, "requestNo", requestNo);
        strictFillIfNull(metaObject, "bizTime", bizTime);
        strictFillIfNull(metaObject, "versionNo", 0L);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // 更新场景实体通常已带旧值，必须强制覆盖，才能让每次写入都留下最新的操作人/请求号/业务时间
        if (metaObject.hasSetter("updatedTime")) {
            metaObject.setValue("updatedTime", LocalDateTime.now());
        }
        if (metaObject.hasSetter("updatedBy")) {
            metaObject.setValue("updatedBy", RequestContext.operatorId());
        }
        if (metaObject.hasSetter("requestNo")) {
            metaObject.setValue("requestNo", RequestContext.requestNo());
        }
        if (metaObject.hasSetter("bizTime")) {
            metaObject.setValue("bizTime", RequestContext.bizTime());
        }
    }

    private void strictFillIfNull(MetaObject metaObject, String fieldName, Object value) {
        if (metaObject.hasSetter(fieldName) && metaObject.getValue(fieldName) == null) {
            metaObject.setValue(fieldName, value);
        }
    }
}
