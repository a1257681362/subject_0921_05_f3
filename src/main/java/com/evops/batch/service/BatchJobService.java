package com.evops.batch.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.batch.dto.BatchJobCreateRequest;
import com.evops.batch.entity.BatchJob;
import com.evops.batch.mapper.BatchJobMapper;
import com.evops.batch.runner.BatchJobRunner;
import com.evops.common.BizCode;
import com.evops.common.BizException;
import com.evops.common.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * 批量任务：提交、查询、取消、重跑。
 * 提交即落库 PENDING 并派发后台线程（请求线程立即返回任务号）；
 * 同类型已有 PENDING/RUNNING 任务时拒绝重复提交；
 * 取消是协作式（置标志，单据边界生效）；重跑保留明细，由执行器断点续跑。
 */
@Service
public class BatchJobService {

    private static final List<String> ACTIVE_STATUSES = Arrays.asList(BatchJob.PENDING, BatchJob.RUNNING);
    private static final List<String> RERUNNABLE_STATUSES = Arrays.asList(BatchJob.FAILED, BatchJob.CANCELLED);
    private static final DateTimeFormatter JOB_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final char[] RANDOM_CHARS = "0123456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Resource
    private BatchJobMapper jobMapper;
    @Resource
    private BatchJobRunner runner;
    @Resource
    private ObjectMapper objectMapper;

    /** 提交任务：落库 PENDING 后派发后台执行，返回带任务号的任务实体 */
    @Transactional(rollbackFor = Exception.class)
    public BatchJob enqueue(BatchJobCreateRequest req) {
        String jobType = req.getJobType() == null ? null : req.getJobType().trim().toUpperCase();
        if (!BatchJob.TYPE_RECHECK.equals(jobType)) {
            throw new BizException(BizCode.JOB_TYPE_UNSUPPORTED, "仅支持 RECHECK 任务类型");
        }
        // 重复提交先在业务层拦一道（库层 uk_batch_job_request 兜底），先于活动任务检查，
        // 避免与"同类型已有活动任务"竞态时返回错误的业务码
        String requestNo = RequestContext.requestNo();
        if (requestNo != null && !requestNo.trim().isEmpty()) {
            Long sameRequest = jobMapper.selectCount(new LambdaQueryWrapper<BatchJob>()
                    .eq(BatchJob::getRequestNo, requestNo));
            if (sameRequest != null && sameRequest > 0) {
                throw new BizException(BizCode.REQUEST_DUPLICATED);
            }
        }
        Long active = jobMapper.selectCount(new LambdaQueryWrapper<BatchJob>()
                .eq(BatchJob::getJobType, jobType)
                .in(BatchJob::getJobStatus, ACTIVE_STATUSES));
        if (active != null && active > 0) {
            throw new BizException(BizCode.JOB_ALREADY_RUNNING);
        }

        BatchJob job = new BatchJob();
        job.setJobNo(generateJobNo(jobType));
        job.setJobType(jobType);
        job.setJobStatus(BatchJob.PENDING);
        job.setTotalCount(0);
        job.setProcessedCount(0);
        job.setSuccessCount(0);
        job.setFailedCount(0);
        job.setProgressPct(0);
        job.setCancelRequested(BatchJob.NO);
        job.setParamsJson(writeParams(req.getStoreCode()));
        jobMapper.insert(job);

        // 事务提交后再派发，避免后台线程早于提交读到 PENDING 行
        runner.dispatchAfterCommit(job.getId());
        return job;
    }

    /** 协作式取消：仅 PENDING/RUNNING 可取消；只置标志，执行器在单据边界收尾为 CANCELLED */
    @Transactional(rollbackFor = Exception.class)
    public BatchJob cancel(String jobNo) {
        BatchJob job = requireJob(jobNo);
        if (!ACTIVE_STATUSES.contains(job.getJobStatus())) {
            throw new BizException(BizCode.JOB_STATUS_ILLEGAL,
                    "仅待执行/执行中的任务可取消，当前状态[" + job.getJobStatus() + "]");
        }
        jobMapper.update(null, new LambdaUpdateWrapper<BatchJob>()
                .eq(BatchJob::getId, job.getId())
                .in(BatchJob::getJobStatus, ACTIVE_STATUSES)
                .set(BatchJob::getCancelRequested, BatchJob.YES));
        return requireJob(jobNo);
    }

    /**
     * 重跑：仅 FAILED/CANCELLED；回到 PENDING、清取消标志与任务级错误，
     * 明细保留（SUCCESS 跳过，只跑未完成/失败单）。
     */
    @Transactional(rollbackFor = Exception.class)
    public BatchJob rerun(String jobNo) {
        BatchJob job = requireJob(jobNo);
        if (!RERUNNABLE_STATUSES.contains(job.getJobStatus())) {
            throw new BizException(BizCode.JOB_STATUS_ILLEGAL,
                    "仅失败/已取消的任务可重跑，当前状态[" + job.getJobStatus() + "]");
        }
        jobMapper.update(null, new LambdaUpdateWrapper<BatchJob>()
                .eq(BatchJob::getId, job.getId())
                .in(BatchJob::getJobStatus, RERUNNABLE_STATUSES)
                .set(BatchJob::getJobStatus, BatchJob.PENDING)
                .set(BatchJob::getCancelRequested, BatchJob.NO)
                .set(BatchJob::getErrorMessage, null)
                .set(BatchJob::getResultJson, null)
                .set(BatchJob::getStartedTime, null)
                .set(BatchJob::getFinishedTime, null)
                .set(BatchJob::getExpireTime, null)
                .set(BatchJob::getTotalCount, 0)
                .set(BatchJob::getProcessedCount, 0)
                .set(BatchJob::getSuccessCount, 0)
                .set(BatchJob::getFailedCount, 0)
                .set(BatchJob::getProgressPct, 0));
        BatchJob rerun = requireJob(jobNo);
        runner.dispatchAfterCommit(rerun.getId());
        return rerun;
    }

    public BatchJob getByJobNo(String jobNo) {
        return requireJob(jobNo);
    }

    public List<BatchJob> list(String status, String jobType) {
        LambdaQueryWrapper<BatchJob> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.trim().isEmpty()) {
            qw.eq(BatchJob::getJobStatus, status.trim().toUpperCase());
        }
        if (jobType != null && !jobType.trim().isEmpty()) {
            qw.eq(BatchJob::getJobType, jobType.trim().toUpperCase());
        }
        qw.orderByDesc(BatchJob::getId);
        return jobMapper.selectList(qw);
    }

    private BatchJob requireJob(String jobNo) {
        if (jobNo == null || jobNo.trim().isEmpty()) {
            throw new BizException(BizCode.JOB_NOT_FOUND);
        }
        BatchJob job = jobMapper.selectOne(new LambdaQueryWrapper<BatchJob>()
                .eq(BatchJob::getJobNo, jobNo.trim()));
        if (job == null) {
            throw new BizException(BizCode.JOB_NOT_FOUND);
        }
        return job;
    }

    /** 任务号：类型-时间-6 位随机；唯一索引兜底极端碰撞（碰撞报 DATA_DUPLICATED 后重试提交即可） */
    private String generateJobNo(String jobType) {
        StringBuilder suffix = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            suffix.append(RANDOM_CHARS[RANDOM.nextInt(RANDOM_CHARS.length)]);
        }
        return jobType + "-" + LocalDateTime.now().format(JOB_NO_TIME) + "-" + suffix;
    }

    private String writeParams(String storeCode) {
        try {
            BatchJobCreateRequest params = new BatchJobCreateRequest();
            params.setJobType(BatchJob.TYPE_RECHECK);
            params.setStoreCode(storeCode == null || storeCode.trim().isEmpty() ? null : storeCode.trim());
            return objectMapper.writeValueAsString(params);
        } catch (Exception ex) {
            throw new BizException(BizCode.VALIDATION_FAILED, "任务参数格式不正确");
        }
    }
}
