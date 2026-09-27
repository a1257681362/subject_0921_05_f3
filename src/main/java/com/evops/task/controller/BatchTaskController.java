package com.evops.task.controller;

import com.evops.common.ApiResponse;
import com.evops.task.BatchTaskService;
import com.evops.task.dto.TaskCreateRequest;
import com.evops.task.entity.BatchTask;
import com.evops.task.entity.BatchTaskItem;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台批量任务接口。写接口同样带 X-Operator-Id / X-Request-No / X-Biz-Time。
 * 创建立即返回任务号；用任务号查进度（progressPct 为 5% 一档）、取消、断点重跑、取处理项明细。
 */
@RestController
@RequestMapping("/api/tasks")
public class BatchTaskController {

    private final BatchTaskService taskService;

    public BatchTaskController(BatchTaskService taskService) {
        this.taskService = taskService;
    }

    /** 创建并后台执行，立即返回任务（含任务号、初始进度），不堵请求线程 */
    @PostMapping
    public ApiResponse<BatchTask> create(@Valid @RequestBody TaskCreateRequest request) {
        String paramsJson = request.getParams() == null ? null : request.getParams().toString();
        return ApiResponse.ok(taskService.create(request.getTaskType().trim(), paramsJson));
    }

    /** 按任务号查进度 */
    @GetMapping("/{taskNo}")
    public ApiResponse<BatchTask> get(@PathVariable String taskNo) {
        return ApiResponse.ok(taskService.getByTaskNo(taskNo));
    }

    /** 任务列表（默认不含已归档，最多返回最近 200 条） */
    @GetMapping
    public ApiResponse<List<BatchTask>> list(@RequestParam(required = false) String taskType,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false, defaultValue = "false")
                                             boolean includeArchived) {
        return ApiResponse.ok(taskService.list(taskType, status, includeArchived));
    }

    /** 请求取消（执行循环在当前项收尾后落 CANCELLED，已处理项保留） */
    @PostMapping("/{taskNo}/cancel")
    public ApiResponse<BatchTask> cancel(@PathVariable String taskNo) {
        return ApiResponse.ok(taskService.cancel(taskNo));
    }

    /** 断点重跑：只继续未完成/失败项，已成功项跳过 */
    @PostMapping("/{taskNo}/rerun")
    public ApiResponse<BatchTask> rerun(@PathVariable String taskNo) {
        return ApiResponse.ok(taskService.rerun(taskNo));
    }

    /** 处理项明细（可按 itemStatus 过滤），含每单失败原因/结果摘要 */
    @GetMapping("/{taskNo}/items")
    public ApiResponse<Map<String, Object>> items(@PathVariable String taskNo,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(required = false, defaultValue = "200") int limit) {
        BatchTask task = taskService.getByTaskNo(taskNo);
        List<BatchTaskItem> items = taskService.listItems(taskNo, status, limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", task);
        data.put("items", items);
        return ApiResponse.ok(data);
    }
}
