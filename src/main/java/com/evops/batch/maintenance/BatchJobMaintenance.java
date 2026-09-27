package com.evops.batch.maintenance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.batch.entity.BatchJob;
import com.evops.batch.entity.BatchJobItem;
import com.evops.batch.mapper.BatchJobItemMapper;
import com.evops.batch.mapper.BatchJobMapper;
import com.evops.batch.runner.BatchJobRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 任务记录保留与异常恢复：
 * 1) 每日定时物理删除 expire_time 已过的终态任务及明细（默认保留 21 天）；
 * 2) 启动恢复：重启时 RUNNING 的任务实际已中断，置 FAILED（明细保留，可重跑断点续跑）；
 *    遗留 PENDING 重新派发到后台线程。
 */
@Component
public class BatchJobMaintenance implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchJobMaintenance.class);
    private static final List<String> TERMINAL_STATUSES =
            Arrays.asList(BatchJob.SUCCEEDED, BatchJob.FAILED, BatchJob.CANCELLED);

    @Resource
    private BatchJobMapper jobMapper;
    @Resource
    private BatchJobItemMapper itemMapper;
    @Resource
    private BatchJobRunner runner;

    @Value("${evops.batch.retain-days:21}")
    private int retainDays;

    /** 每日 03:17 清理到期任务记录（避开整点） */
    @Scheduled(cron = "${evops.batch.cleanup-cron:0 17 3 * * ?}")
    public void scheduledPurge() {
        int removed = purgeExpired();
        if (removed > 0) {
            log.info("批量任务记录清理：物理删除到期任务 {} 个", removed);
        }
    }

    /**
     * 物理删除 expire_time 已过的终态任务及其明细。
     * 这是全库"禁止物理删除"约定的唯一例外：任务运行记录属运维临时数据，
     * 业务底账（RECHECK 留痕等）不在此表、不受影响。
     * @return 删除的任务数
     */
    public int purgeExpired() {
        List<BatchJob> expired = jobMapper.selectList(new LambdaQueryWrapper<BatchJob>()
                .in(BatchJob::getJobStatus, TERMINAL_STATUSES)
                .isNotNull(BatchJob::getExpireTime)
                .le(BatchJob::getExpireTime, LocalDateTime.now()));
        if (expired.isEmpty()) {
            return 0;
        }
        List<Long> jobIds = expired.stream().map(BatchJob::getId).collect(Collectors.toList());
        itemMapper.delete(new LambdaQueryWrapper<BatchJobItem>().in(BatchJobItem::getJobId, jobIds));
        jobMapper.deleteBatchIds(jobIds);
        return jobIds.size();
    }

    @Override
    public void run(ApplicationArguments args) {
        LocalDateTime now = LocalDateTime.now();

        // 重启时 RUNNING 任务随旧进程已死：置 FAILED，保留明细供断点重跑
        int interrupted = jobMapper.update(null, new LambdaUpdateWrapper<BatchJob>()
                .eq(BatchJob::getJobStatus, BatchJob.RUNNING)
                .set(BatchJob::getJobStatus, BatchJob.FAILED)
                .set(BatchJob::getErrorMessage, "服务重启导致中断，可重跑从断点继续")
                .set(BatchJob::getFinishedTime, now)
                .set(BatchJob::getExpireTime, now.plusDays(retainDays)));
        if (interrupted > 0) {
            log.warn("启动恢复：{} 个执行中的批量任务因重启置为失败，可重跑断点续跑", interrupted);
        }

        // 遗留 PENDING（提交后未及派发即宕机）重新派发
        List<BatchJob> pending = jobMapper.selectList(new LambdaQueryWrapper<BatchJob>()
                .eq(BatchJob::getJobStatus, BatchJob.PENDING));
        for (BatchJob job : pending) {
            log.info("启动恢复：重新派发待执行批量任务 jobNo={}", job.getJobNo());
            runner.dispatch(job.getId());
        }
    }
}
