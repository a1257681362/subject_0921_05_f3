package com.evops.task;

import com.evops.task.entity.BatchTask;
import com.evops.task.entity.BatchTaskItem;

import java.util.List;

/**
 * 批量任务处理器 SPI：一种任务类型一个实现，注册为 Spring Bean。
 * 框架负责异步调度、进度（5% 档）、取消检查、断点捞取与保留期清理；
 * 处理器只负责"圈定处理对象"和"单项处理（各自独立事务）"。
 */
public interface BatchTaskHandler {

    /** 任务类型编码，对应 t_batch_task.task_type，如 SENSITIVE_RECHECK */
    String type();

    /**
     * 任务创建时圈定处理对象（决定总数与进度基数）。
     * @param paramsJson 任务参数（原样透传，可为 null）
     */
    List<TaskItemSeed> plan(String paramsJson);

    /**
     * 处理单项。每次调用运行在独立事务中（单项失败只回滚该项，不影响整体，形成断点）。
     * 抛异常按该项 FAILED 处理并记录异常信息。
     */
    ItemResult process(BatchTask task, BatchTaskItem item);

    /**
     * 任务正常跑完（SUCCESS）时汇总任务级结果；返回 null 表示无汇总。
     * 默认无汇总。
     */
    default String summarize(BatchTask task, List<BatchTaskItem> items) {
        return null;
    }

    /** 单项处理结果 */
    class ItemResult {
        private final String status;   // SUCCESS / FAILED / SKIPPED
        private final String resultJson;
        private final String errorMessage;

        private ItemResult(String status, String resultJson, String errorMessage) {
            this.status = status;
            this.resultJson = resultJson;
            this.errorMessage = errorMessage;
        }

        public static ItemResult success() { return new ItemResult("SUCCESS", null, null); }
        public static ItemResult success(String resultJson) { return new ItemResult("SUCCESS", resultJson, null); }
        public static ItemResult skipped(String reason) { return new ItemResult("SKIPPED", null, reason); }
        public static ItemResult failed(String errorMessage) { return new ItemResult("FAILED", null, errorMessage); }

        public String getStatus() { return status; }
        public String getResultJson() { return resultJson; }
        public String getErrorMessage() { return errorMessage; }
    }
}
