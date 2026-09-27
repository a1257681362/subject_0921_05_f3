package com.evops.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BizCode;
import com.evops.common.BizException;
import com.evops.task.config.BatchTaskConfig;
import com.evops.task.config.BatchTaskProperties;
import com.evops.task.entity.BatchTask;
import com.evops.task.entity.BatchTaskItem;
import com.evops.task.mapper.BatchTaskItemMapper;
import com.evops.task.mapper.BatchTaskMapper;
import com.evops.task.support.TaskItemTxRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 批量任务执行核心：
 * 在独立线程池异步跑（不堵请求线程），逐项独立事务（失败只回滚该项），
 * 分块捞取 PENDING/FAILED 项实现断点续跑，每块/每项检查取消请求，
 * 进度按已处理占比量化到 5% 一档落库。
 */
@Component
public class BatchTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(BatchTaskExecutor.class);

    /**
     * 正常领取只接受 PENDING/FAILED（条件 UPDATE 原子置 RUNNING，防同任务并发执行）。
     * CANCEL_REQUESTED 在领取前单独识别（直接收尾，不抹掉取消标记）；
     * CANCELLED 永不在此领取（只能由显式 rerun 先复位）；
     * 崩溃残留 RUNNING 仅由启动恢复复位为 PENDING（见 BatchTaskService.recoverOnStartup）。
     */
    private static final List<String> CLAIMABLE =
            Arrays.asList(TaskStatus.PENDING, TaskStatus.FAILED);
    private static final List<String> ITEM_TODO = Arrays.asList("PENDING", "FAILED");

    @Resource
    private BatchTaskMapper taskMapper;
    @Resource
    private BatchTaskItemMapper itemMapper;
    @Resource
    private TaskItemTxRunner itemTxRunner;
    @Resource
    private BatchTaskProperties props;
    @Resource
    private BatchTaskRegistry registry;

    /** 本进程正在执行的任务，防止重复提交 */
    private final java.util.Set<Long> runningTaskIds = ConcurrentHashMap.newKeySet();

    @Resource
    @Qualifier(BatchTaskConfig.EXECUTOR)
    private Executor executor;

    /** 提交任务到后台线程池（已在跑则忽略） */
    public void submit(long taskId) {
        if (!runningTaskIds.add(taskId)) {
            return;
        }
        executor.execute(() -> {
            try {
                run(taskId);
            } catch (Exception ex) {
                log.error("批量任务[{}]执行异常", taskId, ex);
                markTaskError(taskId, ex);
            } finally {
                runningTaskIds.remove(taskId);
            }
        });
    }

    public boolean isRunningInProcess(long taskId) {
        return runningTaskIds.contains(taskId);
    }

    // ---------------- 执行主循环 ----------------

    private void run(long taskId) {
        BatchTask current = taskMapper.selectById(taskId);
        if (current == null) {
            return;
        }
        BatchTaskHandler handler = registry.find(current.getTaskType());
        if (handler == null) {
            markTaskError(taskId, new BizException(BizCode.TASK_TYPE_UNKNOWN));
            return;
        }

        // 取消标记先于领取：补投收尾（cancel 时无 run 在跑）或排队期间已被取消——直接落 CANCELLED
        if (TaskStatus.CANCEL_REQUESTED.equals(current.getStatus())) {
            finalizeCancelled(taskId, loadCounters(taskId), handler);
            return;
        }

        BatchTask task = claim(taskId);
        if (task == null) {
            return; // 已被并发领取，或已是终态（含 CANCELLED——只能由显式 rerun 复位）
        }

        // 计数以 item 表实际状态为准（续跑/重试时不重复累加）：processed = success + fail
        Counters counters = loadCounters(taskId);
        int lastBucket = task.getProgressPct() == null ? -1 : task.getProgressPct();

        while (true) {
            BatchTask fresh = taskMapper.selectById(taskId);
            if (fresh == null || TaskStatus.CANCEL_REQUESTED.equals(fresh.getStatus())) {
                finalizeCancelled(taskId, counters, handler);
                return;
            }

            List<BatchTaskItem> chunk = itemMapper.selectList(new LambdaQueryWrapper<BatchTaskItem>()
                    .eq(BatchTaskItem::getTaskId, taskId)
                    .in(BatchTaskItem::getItemStatus, ITEM_TODO)
                    .orderByAsc(BatchTaskItem::getId)
                    .last("LIMIT " + props.getItemChunk()));
            if (chunk.isEmpty()) {
                finalizeDone(taskId, counters, handler);
                return;
            }

            for (BatchTaskItem item : chunk) {
                fresh = taskMapper.selectById(taskId);
                if (fresh != null && TaskStatus.CANCEL_REQUESTED.equals(fresh.getStatus())) {
                    persistProgress(taskId, counters, null, lastBucket);
                    finalizeCancelled(taskId, counters, handler);
                    return;
                }

                String before = item.getItemStatus();
                processOne(handler, task, item);
                item = itemMapper.selectById(item.getId());
                adjustCounters(counters, before, item.getItemStatus());

                int bucket = bucket(counters.success + counters.fail, counters.total);
                if (bucket != lastBucket) {
                    persistProgress(taskId, counters, item.getTargetId(), bucket);
                    lastBucket = bucket;
                } else {
                    persistCursor(taskId, item.getTargetId());
                }
                task = fresh;
            }
        }
    }

    /** 按状态迁移增减计数，保证重试（FAILED→SUCCESS）不会重复累计 */
    private void adjustCounters(Counters c, String before, String after) {
        boolean afterOk = "SUCCESS".equals(after) || "SKIPPED".equals(after);
        boolean afterFail = "FAILED".equals(after);
        if ("FAILED".equals(before)) {
            if (afterOk) { c.fail--; c.success++; }
            // 仍 FAILED：不变
            return;
        }
        // before PENDING
        if (afterOk) { c.success++; }
        else if (afterFail) { c.fail++; }
    }

    private void processOne(BatchTaskHandler handler, BatchTask task, BatchTaskItem item) {
        item.setAttempt((item.getAttempt() == null ? 0 : item.getAttempt()) + 1);
        try {
            BatchTaskHandler.ItemResult result = itemTxRunner.runInNewTx(handler, task, item);
            item.setItemStatus(result.getStatus());
            item.setResultJson(truncate(result.getResultJson(), 1900));
            item.setErrorMessage("SKIPPED".equals(result.getStatus()) ? truncate(result.getErrorMessage(), 900) : null);
        } catch (Exception ex) {
            item.setItemStatus("FAILED");
            item.setErrorMessage(truncate(rootMessage(ex), 900));
        }
        itemMapper.updateById(item);
    }

    /** 领取任务：仅在可执行状态下用条件 UPDATE 原子置 RUNNING */
    private BatchTask claim(long taskId) {
        LocalDateTime now = LocalDateTime.now();
        int affected = taskMapper.update(null, new LambdaUpdateWrapper<BatchTask>()
                .eq(BatchTask::getId, taskId)
                .in(BatchTask::getStatus, CLAIMABLE)
                .set(BatchTask::getStatus, TaskStatus.RUNNING)
                .set(BatchTask::getStartedTime, now)
                .set(BatchTask::getStatusSetTime, now));
        if (affected == 0) {
            return null;
        }
        return taskMapper.selectById(taskId);
    }

    private void finalizeDone(long taskId, Counters counters, BatchTaskHandler handler) {
        BatchTask task = taskMapper.selectById(taskId);
        if (task == null || TaskStatus.CANCEL_REQUESTED.equals(task.getStatus())) {
            finalizeCancelled(taskId, counters, handler);
            return;
        }
        String resultJson = null;
        try {
            List<BatchTaskItem> all = itemMapper.selectList(new LambdaQueryWrapper<BatchTaskItem>()
                    .eq(BatchTaskItem::getTaskId, taskId));
            resultJson = truncate(handler.summarize(task, all), 3900);
        } catch (Exception ex) {
            log.warn("任务[{}]汇总失败", taskId, ex);
        }
        String toStatus = counters.fail > 0 ? TaskStatus.FAILED : TaskStatus.SUCCESS;
        LocalDateTime now = LocalDateTime.now();
        taskMapper.update(null, new LambdaUpdateWrapper<BatchTask>()
                .eq(BatchTask::getId, taskId)
                .set(BatchTask::getStatus, toStatus)
                .set(BatchTask::getProgressPct, 100)
                .set(BatchTask::getProcessedCount, counters.processed())
                .set(BatchTask::getSuccessCount, counters.success)
                .set(BatchTask::getFailCount, counters.fail)
                .set(BatchTask::getResultJson, resultJson)
                .set(BatchTask::getFinishedTime, now)
                .set(BatchTask::getStatusSetTime, now)
                .set(BatchTask::getErrorMessage, null));
    }

    private void finalizeCancelled(long taskId, Counters counters, BatchTaskHandler handler) {
        LocalDateTime now = LocalDateTime.now();
        taskMapper.update(null, new LambdaUpdateWrapper<BatchTask>()
                .eq(BatchTask::getId, taskId)
                .in(BatchTask::getStatus, TaskStatus.RUNNING, TaskStatus.CANCEL_REQUESTED)
                .set(BatchTask::getStatus, TaskStatus.CANCELLED)
                .set(BatchTask::getProcessedCount, counters.processed())
                .set(BatchTask::getSuccessCount, counters.success)
                .set(BatchTask::getFailCount, counters.fail)
                .set(BatchTask::getProgressPct, bucket(counters.processed(), counters.total))
                .set(BatchTask::getFinishedTime, now)
                .set(BatchTask::getStatusSetTime, now));
    }

    private void markTaskError(long taskId, Exception ex) {
        LocalDateTime now = LocalDateTime.now();
        taskMapper.update(null, new LambdaUpdateWrapper<BatchTask>()
                .eq(BatchTask::getId, taskId)
                .set(BatchTask::getStatus, TaskStatus.FAILED)
                .set(BatchTask::getErrorMessage, truncate(rootMessage(ex), 1900))
                .set(BatchTask::getFinishedTime, now)
                .set(BatchTask::getStatusSetTime, now));
    }

    private void persistProgress(long taskId, Counters counters, Long lastOrderId, int bucket) {
        taskMapper.update(null, new LambdaUpdateWrapper<BatchTask>()
                .eq(BatchTask::getId, taskId)
                .set(BatchTask::getProcessedCount, counters.processed())
                .set(BatchTask::getSuccessCount, counters.success)
                .set(BatchTask::getFailCount, counters.fail)
                .set(BatchTask::getProgressPct, bucket)
                .set(lastOrderId != null, BatchTask::getLastOrderId, lastOrderId));
    }

    private void persistCursor(long taskId, Long lastOrderId) {
        if (lastOrderId == null) {
            return;
        }
        taskMapper.update(null, new LambdaUpdateWrapper<BatchTask>()
                .eq(BatchTask::getId, taskId)
                .set(BatchTask::getLastOrderId, lastOrderId));
    }

    // ---------------- 计数 / 进度工具 ----------------

    private Counters loadCounters(long taskId) {
        BatchTask t = taskMapper.selectById(taskId);
        Counters c = new Counters();
        c.total = t.getTotalCount() == null ? 0 : t.getTotalCount();
        // 以 item 表实际状态为准，避免续跑/重试时重复累加
        c.success = itemMapper.selectCount(new LambdaQueryWrapper<BatchTaskItem>()
                .eq(BatchTaskItem::getTaskId, taskId)
                .in(BatchTaskItem::getItemStatus, Arrays.asList("SUCCESS", "SKIPPED"))).intValue();
        c.fail = itemMapper.selectCount(new LambdaQueryWrapper<BatchTaskItem>()
                .eq(BatchTaskItem::getTaskId, taskId)
                .eq(BatchTaskItem::getItemStatus, "FAILED")).intValue();
        return c;
    }

    /** 进度量化到 5% 一档：floor(processed*100/total /5)*5 */
    static int bucket(int processed, int total) {
        if (total <= 0) {
            return 0;
        }
        int pct = processed * 100 / total;
        return Math.min(100, (pct / 5) * 5);
    }

    private static String rootMessage(Throwable ex) {
        Throwable cur = ex;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return cur.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static class Counters {
        int total;
        int success;
        int fail;

        int processed() { return success + fail; }
    }
}
