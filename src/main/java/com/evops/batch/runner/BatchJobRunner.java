package com.evops.batch.runner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.batch.entity.BatchJob;
import com.evops.batch.entity.BatchJobItem;
import com.evops.batch.mapper.BatchJobItemMapper;
import com.evops.batch.mapper.BatchJobMapper;
import com.evops.common.RequestContext;
import com.evops.order.entity.ProcessOrder;
import com.evops.sensitive.OrderRecheckResult;
import com.evops.sensitive.SensitiveRecheckService;
import com.evops.sensitive.SensitiveWordManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量任务执行器。
 * 接单 PENDING→RUNNING 用条件更新兜底（防重复派发）；逐单独立事务处理，
 * 单据边界检查取消标志；每单结果落明细表，重跑跳过 SUCCESS（断点续跑）；
 * 进度按 5% 一档；终态汇总报告并写过期时间（finished_time + 保留天数）。
 * 所有任务行更新走 UpdateWrapper（不经乐观锁），避免与取消操作互踢。
 */
@Component
public class BatchJobRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchJobRunner.class);

    private static final int FAIL_REASON_MAX = 900;
    private static final int ERROR_MESSAGE_MAX = 900;

    @Resource
    private BatchJobMapper jobMapper;
    @Resource
    private BatchJobItemMapper itemMapper;
    @Resource
    private SensitiveRecheckService recheckService;
    @Resource
    private SensitiveWordManager wordManager;
    @Resource
    private ObjectMapper objectMapper;
    @Resource(name = "batchJobExecutor")
    private ThreadPoolTaskExecutor executor;

    @Value("${evops.batch.retain-days:21}")
    private int retainDays;

    /** 单单之间的节流毫秒：生产可限速；默认 0 不空等。测试可调大以稳定制造取消窗口 */
    @Value("${evops.batch.item-sleep-ms:0}")
    private long itemSleepMs;

    private void throttle() {
        if (itemSleepMs <= 0) {
            return;
        }
        try {
            Thread.sleep(itemSleepMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 提交到后台线程池执行，不占请求线程 */
    public void dispatch(Long jobId) {
        executor.execute(() -> {
            try {
                run(jobId);
            } catch (Throwable t) {
                // run 内部已兜底整体异常，这里防工作线程因未捕获异常静默死掉
                log.error("批量任务执行未预期终止 jobId={}", jobId, t);
            }
        });
    }

    /**
     * 事务提交后再派发：任务行落库可见后后台线程才能 CAS 接单；
     * 无活动事务时（如启动恢复/测试直调）立即派发。
     */
    public void dispatchAfterCommit(Long jobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(jobId);
                }
            });
        } else {
            dispatch(jobId);
        }
    }

    /** 供测试/启动恢复同步调用：在当前线程跑完整个任务 */
    public void run(Long jobId) {
        BatchJob job = jobMapper.selectById(jobId);
        if (job == null) {
            return;
        }
        // CAS 接单：仅 PENDING 可变 RUNNING，重复派发（启动恢复/重复提交）只有一个工作者能接到
        int claimed = jobMapper.update(null, new LambdaUpdateWrapper<BatchJob>()
                .eq(BatchJob::getId, jobId)
                .eq(BatchJob::getJobStatus, BatchJob.PENDING)
                .set(BatchJob::getJobStatus, BatchJob.RUNNING)
                .set(BatchJob::getStartedTime, LocalDateTime.now()));
        if (claimed == 0) {
            return;
        }

        job = jobMapper.selectById(jobId);
        // 后台写出的 RECHECK 留痕等审计字段挂到提交人与任务号上，可追溯
        RequestContext.set(job.getCreatedBy(), "JOB:" + job.getJobNo(), null);
        try {
            execute(jobId, job);
        } catch (Exception ex) {
            log.error("批量任务整体异常 jobNo={}", job.getJobNo(), ex);
            finishTerminal(jobId, BatchJob.FAILED, null,
                    "任务执行异常：" + truncate(ex.getMessage(), ERROR_MESSAGE_MAX));
        } finally {
            RequestContext.clear();
        }
    }

    private void execute(Long jobId, BatchJob job) {
        String storeCode = parseStoreCode(job.getParamsJson());

        // 本次核对快照（按 id 升序），任务范围内以此为准
        List<ProcessOrder> targets = recheckService.loadTargets(storeCode);
        int total = targets.size();
        jobMapper.update(null, new LambdaUpdateWrapper<BatchJob>()
                .eq(BatchJob::getId, jobId)
                .set(BatchJob::getTotalCount, total)
                .set(BatchJob::getProgressPct, total == 0 ? 100 : 0));

        // 已有明细（断点续跑依据）：SUCCESS 跳过，FAILED 重试并更新原行
        Map<Long, BatchJobItem> existing = new HashMap<>();
        List<BatchJobItem> oldItems = itemMapper.selectList(
                new LambdaQueryWrapper<BatchJobItem>().eq(BatchJobItem::getJobId, jobId));
        int success = 0;
        int failed = 0;
        for (BatchJobItem item : oldItems) {
            existing.put(item.getOrderId(), item);
            if (BatchJobItem.SUCCESS.equals(item.getItemStatus())) {
                success++;
            } else {
                failed++;
            }
        }

        int lastBucket = -1;
        for (ProcessOrder order : targets) {
            // 单据边界协作式取消：正在处理的当前单不打断
            if (isCancelRequested(jobId)) {
                finishTerminal(jobId, BatchJob.CANCELLED, buildReport(jobId, total),
                        "任务已取消，已处理结果保留，可重跑断点续跑");
                return;
            }

            BatchJobItem item = existing.get(order.getId());
            boolean previouslyFailed = item != null && BatchJobItem.FAILED.equals(item.getItemStatus());
            if (item != null && BatchJobItem.SUCCESS.equals(item.getItemStatus())) {
                continue; // 断点：上次已成功，不重复扫、不重复写 RECHECK 留痕
            }

            try {
                OrderRecheckResult result = recheckService.recheckOneInNewTx(order.getId());
                if (item == null) {
                    item = newItem(jobId, order);
                }
                item.setItemStatus(BatchJobItem.SUCCESS);
                item.setFailReason(null);
                item.setResultJson(writeResultJson(result));
                saveItem(item);
                success++;
                if (previouslyFailed) {
                    failed--; // 上次失败的单这次成功
                }
            } catch (Exception ex) {
                // 单单失败独立事务已回滚该单 RECHECK 留痕：记 FAILED 明细，任务继续跑后续单
                log.warn("批量任务单单失败 jobNo={} orderNo={}", job.getJobNo(), order.getOrderNo(), ex);
                if (item == null) {
                    item = newItem(jobId, order);
                }
                item.setItemStatus(BatchJobItem.FAILED);
                item.setFailReason(truncate(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                        FAIL_REASON_MAX));
                saveItem(item);
                if (!previouslyFailed) {
                    failed++; // 新失败的单；上次就失败的单计数不变
                }
            }
            existing.put(order.getId(), item);

            // 计数每单精确落库；progress_pct 只在跨 5% 档位时变化
            int processed = success + failed;
            int bucket = total == 0 ? 100 : Math.min(100, (processed * 100 / total) / 5 * 5);
            LambdaUpdateWrapper<BatchJob> uw = new LambdaUpdateWrapper<BatchJob>()
                    .eq(BatchJob::getId, jobId)
                    .set(BatchJob::getProcessedCount, processed)
                    .set(BatchJob::getSuccessCount, success)
                    .set(BatchJob::getFailedCount, failed);
            if (bucket != lastBucket) {
                uw.set(BatchJob::getProgressPct, bucket);
                lastBucket = bucket;
            }
            jobMapper.update(null, uw);
            throttle();
        }

        boolean cancelled = isCancelRequested(jobId);
        String report = buildReport(jobId, total);
        if (cancelled) {
            finishTerminal(jobId, BatchJob.CANCELLED, report, "任务已取消，已处理结果保留，可重跑断点续跑");
        } else {
            int finalFailed = failed;
            jobMapper.update(null, new LambdaUpdateWrapper<BatchJob>()
                    .eq(BatchJob::getId, jobId)
                    .set(BatchJob::getProcessedCount, success + finalFailed)
                    .set(BatchJob::getSuccessCount, success)
                    .set(BatchJob::getFailedCount, finalFailed)
                    .set(BatchJob::getProgressPct, 100));
            finishTerminal(jobId, finalFailed > 0 ? BatchJob.FAILED : BatchJob.SUCCEEDED,
                    report, finalFailed > 0 ? "部分加工单核对失败，可重跑断点续跑失败单" : null);
        }
    }

    /** 写终态：状态、结束时间与过期时间（完成后保留 21 天） */
    private void finishTerminal(Long jobId, String status, String resultJson, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        jobMapper.update(null, new LambdaUpdateWrapper<BatchJob>()
                .eq(BatchJob::getId, jobId)
                .set(BatchJob::getJobStatus, status)
                .set(BatchJob::getResultJson, resultJson)
                .set(BatchJob::getErrorMessage, errorMessage)
                .set(BatchJob::getFinishedTime, now)
                .set(BatchJob::getExpireTime, now.plusDays(retainDays)));
    }

    /** 汇总全部明细产出最终报告：结构对齐 RecheckReport，另附失败单明细 */
    private String buildReport(Long jobId, int totalOrders) {
        List<BatchJobItem> items = itemMapper.selectList(
                new LambdaQueryWrapper<BatchJobItem>()
                        .eq(BatchJobItem::getJobId, jobId)
                        .orderByAsc(BatchJobItem::getId));

        ArrayNode neverScanned = objectMapper.createArrayNode();
        ArrayNode changed = objectMapper.createArrayNode();
        ArrayNode failedOrders = objectMapper.createArrayNode();
        int matchedFields = 0;

        for (BatchJobItem item : items) {
            if (BatchJobItem.FAILED.equals(item.getItemStatus())) {
                ObjectNode fail = objectMapper.createObjectNode();
                fail.put("orderNo", item.getOrderNo());
                fail.put("reason", item.getFailReason());
                failedOrders.add(fail);
                continue;
            }
            JsonNode result = readTree(item.getResultJson());
            if (result == null) {
                continue;
            }
            if (result.path("neverScanned").asBoolean(false)) {
                neverScanned.add(item.getOrderNo());
            } else {
                JsonNode drifts = result.get("drifts");
                if (drifts != null) {
                    drifts.forEach(changed::add);
                }
                matchedFields += result.path("matchedFields").asInt(0);
            }
        }

        ObjectNode report = objectMapper.createObjectNode();
        report.put("dictVersion", wordManager.dictVersion());
        if (totalOrders >= 0) {
            report.put("totalOrders", totalOrders);
        }
        report.set("neverScanned", neverScanned);
        report.set("changed", changed);
        report.put("matchedFields", matchedFields);
        report.set("failedOrders", failedOrders);
        return report.toString();
    }

    private boolean isCancelRequested(Long jobId) {
        BatchJob latest = jobMapper.selectById(jobId);
        return latest != null && BatchJob.YES.equals(latest.getCancelRequested());
    }

    private BatchJobItem newItem(Long jobId, ProcessOrder order) {
        BatchJobItem item = new BatchJobItem();
        item.setJobId(jobId);
        item.setOrderId(order.getId());
        item.setOrderNo(order.getOrderNo());
        return item;
    }

    private void saveItem(BatchJobItem item) {
        if (item.getId() == null) {
            itemMapper.insert(item);
        } else {
            itemMapper.updateById(item);
        }
    }

    private String writeResultJson(OrderRecheckResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception ex) {
            // 明细结果序列化失败不致命：该单留痕已写，报丢失不阻断任务
            return null;
        }
    }

    private JsonNode readTree(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }

    private String parseStoreCode(String paramsJson) {
        JsonNode params = readTree(paramsJson);
        if (params == null) {
            return null;
        }
        JsonNode storeCode = params.get("storeCode");
        return storeCode == null || storeCode.isNull() ? null : storeCode.asText();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
