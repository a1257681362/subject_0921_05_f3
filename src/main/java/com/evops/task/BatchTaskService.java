package com.evops.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BizCode;
import com.evops.common.BizException;
import com.evops.task.config.BatchTaskProperties;
import com.evops.task.entity.BatchTask;
import com.evops.task.entity.BatchTaskItem;
import com.evops.task.mapper.BatchTaskItemMapper;
import com.evops.task.mapper.BatchTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 批量任务门面：创建（圈定处理项）、提交后台、取消、断点重跑、进度/结果查询、
 * 进程重启后恢复未完成任务、终态任务超保留期（默认 21 天）归档。
 */
@Service
public class BatchTaskService {

    private static final Logger log = LoggerFactory.getLogger(BatchTaskService.class);
    private static final DateTimeFormatter NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Resource
    private BatchTaskMapper taskMapper;
    @Resource
    private BatchTaskItemMapper itemMapper;
    @Resource
    private BatchTaskExecutor executor;
    @Resource
    private BatchTaskProperties props;
    @Resource
    private BatchTaskRegistry registry;

    /** 创建任务：圈定处理项落库，随后异步执行。同请求号重复提交由任务号/请求审计追溯。 */
    @Transactional(rollbackFor = Exception.class)
    public BatchTask create(String taskType, String paramsJson) {
        BatchTaskHandler handler = registry.require(taskType);
        List<TaskItemSeed> seeds = handler.plan(paramsJson);

        BatchTask task = new BatchTask();
        task.setTaskNo(generateTaskNo());
        task.setTaskType(taskType);
        task.setParams(paramsJson);
        task.setStatus(TaskStatus.PENDING);
        task.setTotalCount(seeds.size());
        task.setProcessedCount(0);
        task.setSuccessCount(0);
        task.setFailCount(0);
        task.setProgressPct(0);
        task.setStatusSetTime(LocalDateTime.now());
        task.setExpireDays(props.getKeepDays());
        task.setExpireStatus("ACTIVE");
        taskMapper.insert(task);

        for (TaskItemSeed seed : seeds) {
            BatchTaskItem item = new BatchTaskItem();
            item.setTaskId(task.getId());
            item.setItemRef(seed.getItemRef());
            item.setTargetId(seed.getTargetId());
            item.setItemStatus("PENDING");
            item.setAttempt(0);
            itemMapper.insert(item);
        }
        // 事务提交后再提交线程池，避免异步线程读不到未提交的任务/处理项
        submitAfterCommit(task.getId());
        return task;
    }

    /** 请求取消：置 CANCEL_REQUESTED，执行循环在当前项/当前块收尾后落 CANCELLED */
    @Transactional(rollbackFor = Exception.class)
    public BatchTask cancel(String taskNo) {
        BatchTask task = requireByTaskNo(taskNo);
        if (!TaskStatus.isCancellable(task.getStatus())) {
            throw new BizException(BizCode.TASK_NOT_CANCELLABLE);
        }
        LocalDateTime now = LocalDateTime.now();
        int affected = taskMapper.update(null, new LambdaUpdateWrapper<BatchTask>()
                .eq(BatchTask::getId, task.getId())
                .in(BatchTask::getStatus, TaskStatus.PENDING, TaskStatus.RUNNING, TaskStatus.FAILED)
                .set(BatchTask::getStatus, TaskStatus.CANCEL_REQUESTED)
                .set(BatchTask::getCancelRequestedTime, now)
                .set(BatchTask::getStatusSetTime, now));
        if (affected == 0) {
            throw new BizException(BizCode.TASK_NOT_CANCELLABLE);
        }
        // 收尾统一走执行循环：已在本进程（含已排队未开跑）的任务会在领取前/逐项检查时识别标记后落 CANCELLED；
        // 未排队（如 FAILED 态手工取消）则补投一次，由 run 识别 CANCEL_REQUESTED 收尾。
        if (!executor.isRunningInProcess(task.getId())) {
            executor.submit(task.getId());
        }
        return taskMapper.selectById(task.getId());
    }

    /** 将已请求取消、无执行循环在跑的任务按 item 实际计数落 CANCELLED（供启动恢复调用） */
    private void settleCancelled(long taskId, LocalDateTime when) {
        long success = itemMapper.selectCount(new LambdaQueryWrapper<BatchTaskItem>()
                .eq(BatchTaskItem::getTaskId, taskId)
                .in(BatchTaskItem::getItemStatus, "SUCCESS", "SKIPPED"));
        long fail = itemMapper.selectCount(new LambdaQueryWrapper<BatchTaskItem>()
                .eq(BatchTaskItem::getTaskId, taskId)
                .eq(BatchTaskItem::getItemStatus, "FAILED"));
        BatchTask t = taskMapper.selectById(taskId);
        int total = t.getTotalCount() == null ? 0 : t.getTotalCount();
        taskMapper.update(null, new LambdaUpdateWrapper<BatchTask>()
                .eq(BatchTask::getId, taskId)
                .eq(BatchTask::getStatus, TaskStatus.CANCEL_REQUESTED)
                .set(BatchTask::getStatus, TaskStatus.CANCELLED)
                .set(BatchTask::getProcessedCount, (int) (success + fail))
                .set(BatchTask::getSuccessCount, (int) success)
                .set(BatchTask::getFailCount, (int) fail)
                .set(BatchTask::getProgressPct,
                        total <= 0 ? 0 : Math.min(100, (int) ((success + fail) * 100 / total / 5) * 5))
                .set(BatchTask::getFinishedTime, when)
                .set(BatchTask::getStatusSetTime, when));
    }

    /** 断点重跑：只继续 PENDING/FAILED 项；SUCCESS 跳过。 */
    @Transactional(rollbackFor = Exception.class)
    public BatchTask rerun(String taskNo) {
        BatchTask task = requireByTaskNo(taskNo);
        if (!TaskStatus.isRunnable(task.getStatus())) {
            throw new BizException(BizCode.TASK_NOT_RUNNABLE);
        }
        long pending = itemMapper.selectCount(new LambdaQueryWrapper<BatchTaskItem>()
                .eq(BatchTaskItem::getTaskId, task.getId())
                .in(BatchTaskItem::getItemStatus, "PENDING", "FAILED"));
        if (pending == 0) {
            throw new BizException(BizCode.TASK_NO_PENDING_ITEMS);
        }
        // 清掉上次的任务级错误，回到待执行；计数在执行器里按 item 实际状态重算
        LocalDateTime now = LocalDateTime.now();
        taskMapper.update(null, new LambdaUpdateWrapper<BatchTask>()
                .eq(BatchTask::getId, task.getId())
                .in(BatchTask::getStatus, TaskStatus.PENDING, TaskStatus.FAILED, TaskStatus.CANCELLED)
                .set(BatchTask::getStatus, TaskStatus.PENDING)
                .set(BatchTask::getErrorMessage, null)
                .set(BatchTask::getFinishedTime, null)
                .set(BatchTask::getStatusSetTime, now));
        long taskId = task.getId();
        submitAfterCommit(taskId);
        return taskMapper.selectById(task.getId());
    }

    public BatchTask getByTaskNo(String taskNo) {
        return requireByTaskNo(taskNo);
    }

    public List<BatchTaskItem> listItems(String taskNo, String status, int limit) {
        BatchTask task = requireByTaskNo(taskNo);
        LambdaQueryWrapper<BatchTaskItem> qw = new LambdaQueryWrapper<BatchTaskItem>()
                .eq(BatchTaskItem::getTaskId, task.getId())
                .orderByAsc(BatchTaskItem::getId);
        if (status != null && !status.trim().isEmpty()) {
            qw.eq(BatchTaskItem::getItemStatus, status.trim());
        }
        if (limit > 0) {
            qw.last("LIMIT " + Math.min(limit, 1000));
        }
        return itemMapper.selectList(qw);
    }

    /** 任务列表（默认只看未归档），按新建倒序 */
    public List<BatchTask> list(String taskType, String status, boolean includeArchived) {
        LambdaQueryWrapper<BatchTask> qw = new LambdaQueryWrapper<BatchTask>()
                .orderByDesc(BatchTask::getId);
        if (taskType != null && !taskType.trim().isEmpty()) {
            qw.eq(BatchTask::getTaskType, taskType.trim());
        }
        if (status != null && !status.trim().isEmpty()) {
            qw.eq(BatchTask::getStatus, status.trim());
        }
        if (!includeArchived) {
            qw.eq(BatchTask::getExpireStatus, "ACTIVE");
        }
        qw.last("LIMIT 200");
        return taskMapper.selectList(qw);
    }

    // ---------------- 启动恢复 & 保留期清理 ----------------

    /**
     * 进程启动后恢复：崩溃前停在 PENDING/RUNNING 的任务重新领取续跑（按 item 断点，已成功项跳过）。
     * CANCEL_REQUESTED 残留的任务也唤醒一次，由执行循环落 CANCELLED。
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        List<BatchTask> stale = taskMapper.selectList(new LambdaQueryWrapper<BatchTask>()
                .in(BatchTask::getStatus, TaskStatus.PENDING, TaskStatus.RUNNING,
                        TaskStatus.CANCEL_REQUESTED));
        LocalDateTime now = LocalDateTime.now();
        for (BatchTask task : stale) {
            if (TaskStatus.CANCEL_REQUESTED.equals(task.getStatus())) {
                // 崩溃时正在收尾取消：没有执行循环了，按已处理项直接结算
                settleCancelled(task.getId(), now);
            } else {
                if (TaskStatus.RUNNING.equals(task.getStatus())) {
                    // 进程崩溃残留的 RUNNING：复位为 PENDING 后再按 item 断点续跑
                    taskMapper.update(null, new LambdaUpdateWrapper<BatchTask>()
                            .eq(BatchTask::getId, task.getId())
                            .eq(BatchTask::getStatus, TaskStatus.RUNNING)
                            .set(BatchTask::getStatus, TaskStatus.PENDING)
                            .set(BatchTask::getStatusSetTime, now));
                }
                log.info("恢复未完成批量任务 taskNo={} status={}", task.getTaskNo(), task.getStatus());
                executor.submit(task.getId());
            }
        }
    }

    /**
     * 保留期清理：终态（SUCCESS/FAILED/CANCELLED）任务进入当前状态超过保留天数（默认 21 天）
     * 转 ARCHIVED——只改状态位，不物理删除（全库禁删）。其 item 一并标记归档态。
     */
    @Scheduled(cron = "${evops.batch.cleanup-cron:0 30 2 * * ?}")
    public void archiveExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(props.getKeepDays());
        List<BatchTask> expired = taskMapper.selectList(new LambdaQueryWrapper<BatchTask>()
                .eq(BatchTask::getExpireStatus, "ACTIVE")
                .in(BatchTask::getStatus, TaskStatus.SUCCESS, TaskStatus.FAILED, TaskStatus.CANCELLED)
                .lt(BatchTask::getStatusSetTime, cutoff));
        if (expired.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (BatchTask task : expired) {
            taskMapper.update(null, new LambdaUpdateWrapper<BatchTask>()
                    .eq(BatchTask::getId, task.getId())
                    .eq(BatchTask::getExpireStatus, "ACTIVE")
                    .set(BatchTask::getExpireStatus, "ARCHIVED")
                    .set(BatchTask::getStatus, TaskStatus.ARCHIVED)
                    .set(BatchTask::getStatusSetTime, now));
            log.info("批量任务过保留期归档 taskNo={} finishedTime={}", task.getTaskNo(), task.getFinishedTime());
        }
    }

    // ---------------- 内部 ----------------

    /** 事务提交后再把任务投递到后台线程池；无事务上下文则立即投递 */
    private void submitAfterCommit(long taskId) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            executor.submit(taskId);
                        }
                    });
        } else {
            executor.submit(taskId);
        }
    }

    private BatchTask requireByTaskNo(String taskNo) {
        if (taskNo == null || taskNo.trim().isEmpty()) {
            throw new BizException(BizCode.TASK_NOT_FOUND);
        }
        BatchTask task = taskMapper.selectOne(new LambdaQueryWrapper<BatchTask>()
                .eq(BatchTask::getTaskNo, taskNo.trim()));
        if (task == null) {
            throw new BizException(BizCode.TASK_NOT_FOUND);
        }
        return task;
    }

    private String generateTaskNo() {
        return "BT-" + LocalDateTime.now().format(NO_DATE) + "-"
                + String.format("%06d", taskMapper.nextTaskNoSeq());
    }
}
