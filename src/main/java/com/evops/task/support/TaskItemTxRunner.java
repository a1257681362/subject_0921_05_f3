package com.evops.task.support;

import com.evops.task.BatchTaskHandler;
import com.evops.task.entity.BatchTask;
import com.evops.task.entity.BatchTaskItem;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务单项事务边界。独立成 Bean，避免异步循环里自调用导致 @Transactional 失效。
 * 每项 REQUIRES_NEW：单项处理（含其内部留痕写入）独立提交/回滚，
 * 一项失败只回滚该项，已成功项不丢——这是失败断点重跑的基础。
 */
@Component
public class TaskItemTxRunner {

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public BatchTaskHandler.ItemResult runInNewTx(BatchTaskHandler handler, BatchTask task, BatchTaskItem item) {
        return handler.process(task, item);
    }
}
