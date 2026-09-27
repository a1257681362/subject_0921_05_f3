package com.evops.batch.controller;

import com.evops.batch.dto.BatchJobCreateRequest;
import com.evops.batch.dto.BatchJobView;
import com.evops.batch.entity.BatchJob;
import com.evops.batch.service.BatchJobService;
import com.evops.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 加工单批量处理后台任务接口。写接口同样携带
 * X-Operator-Id / X-Request-No / X-Biz-Time 审计头（request_no 唯一约束兜底重复提交）。
 * 提交立即返回任务号；凭任务号轮询进度（5% 一档）、取消或断点重跑。
 */
@RestController
@RequestMapping("/api/batch/jobs")
public class BatchJobController {

    private final BatchJobService jobService;
    private final ObjectMapper objectMapper;

    public BatchJobController(BatchJobService jobService, ObjectMapper objectMapper) {
        this.jobService = jobService;
        this.objectMapper = objectMapper;
    }

    /** 提交批量任务（当前仅 RECHECK）：后台执行，立即返回 PENDING 任务（含任务号） */
    @PostMapping
    public ApiResponse<BatchJobView> create(@Valid @RequestBody BatchJobCreateRequest request) {
        BatchJob job = jobService.enqueue(request);
        return ApiResponse.ok(BatchJobView.from(job, objectMapper));
    }

    /** 按任务号查进度与结果 */
    @GetMapping("/{jobNo}")
    public ApiResponse<BatchJobView> get(@PathVariable String jobNo) {
        return ApiResponse.ok(BatchJobView.from(jobService.getByJobNo(jobNo), objectMapper));
    }

    /** 任务列表，可按状态/类型过滤 */
    @GetMapping
    public ApiResponse<List<BatchJobView>> list(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) String jobType) {
        List<BatchJobView> views = jobService.list(status, jobType).stream()
                .map(job -> BatchJobView.from(job, objectMapper))
                .collect(Collectors.toList());
        return ApiResponse.ok(views);
    }

    /** 协作式取消：单据边界生效，已处理结果保留，可再重跑 */
    @PostMapping("/{jobNo}/cancel")
    public ApiResponse<BatchJobView> cancel(@PathVariable String jobNo) {
        return ApiResponse.ok(BatchJobView.from(jobService.cancel(jobNo), objectMapper));
    }

    /** 断点重跑：仅失败/已取消任务，已成功的单不重复处理 */
    @PostMapping("/{jobNo}/rerun")
    public ApiResponse<BatchJobView> rerun(@PathVariable String jobNo) {
        return ApiResponse.ok(BatchJobView.from(jobService.rerun(jobNo), objectMapper));
    }
}
