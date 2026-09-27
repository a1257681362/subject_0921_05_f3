package com.evops.task;

import com.evops.common.BizCode;
import com.evops.common.BizException;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务处理器注册中心：按 {@link BatchTaskHandler#type()} 建索引（而非 Spring Bean 名），
 * 框架据 t_batch_task.task_type 取对应处理器。
 */
@Component
public class BatchTaskRegistry {

    @Resource
    private List<BatchTaskHandler> handlerBeans;

    private final Map<String, BatchTaskHandler> byType = new HashMap<>();

    @PostConstruct
    void init() {
        for (BatchTaskHandler handler : handlerBeans) {
            BatchTaskHandler old = byType.put(handler.type(), handler);
            if (old != null) {
                throw new IllegalStateException("重复的批量任务类型: " + handler.type());
            }
        }
    }

    /** 取不到抛明确业务码 */
    public BatchTaskHandler require(String taskType) {
        BatchTaskHandler handler = byType.get(taskType);
        if (handler == null) {
            throw new BizException(BizCode.TASK_TYPE_UNKNOWN);
        }
        return handler;
    }

    public BatchTaskHandler find(String taskType) {
        return byType.get(taskType);
    }
}
