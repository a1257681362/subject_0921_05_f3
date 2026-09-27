package com.evops.sensitive;

import com.evops.common.ApiResponse;
import com.evops.sensitive.dto.RecheckRequest;
import com.evops.sensitive.dto.ScanRequest;
import com.evops.task.BatchTaskService;
import com.evops.task.entity.BatchTask;
import com.evops.sensitive.SensitiveWordManager.DictStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 敏感词管控接口。试扫给前端标记用；reload/info 管词库；
 * recheck 反向核对已改为后台批量任务：提交即返回任务号，进度/结果走 /api/tasks/{taskNo}。
 */
@RestController
@RequestMapping("/api/sensitive")
public class SensitiveWordController {

    private final SensitiveWordService wordService;
    private final SensitiveWordManager wordManager;
    private final BatchTaskService taskService;

    public SensitiveWordController(SensitiveWordService wordService,
                                   SensitiveWordManager wordManager,
                                   BatchTaskService taskService) {
        this.wordService = wordService;
        this.wordManager = wordManager;
        this.taskService = taskService;
    }

    /** 试扫：返回命中位置（原文坐标，end 开区间）、打码结果、是否拦截、词库版本 */
    @PostMapping("/scan")
    public ApiResponse<ScanOutcome> scan(@Valid @RequestBody ScanRequest request) {
        return ApiResponse.ok(wordService.scan(request.getText()));
    }

    /** 立即重载词库（热更新） */
    @PostMapping("/words/reload")
    public ApiResponse<DictStatus> reload() {
        return ApiResponse.ok(wordManager.reload());
    }

    /** 当前词库状态（不输出词条原文） */
    @GetMapping("/words/info")
    public ApiResponse<DictStatus> info() {
        return ApiResponse.ok(wordManager.status());
    }

    /**
     * 反向核对：提交后台任务（不堵请求线程），立即返回任务号。
     * body 可传 {"storeCode":"S01"} 收窄范围。
     * 进度用 GET /api/tasks/{taskNo}，处理项/差异用 GET /api/tasks/{taskNo}/items，
     * 任务跑完后 resultJson 为汇总（词库版本/总数/未覆盖/差异数）。
     */
    @PostMapping("/recheck")
    public ApiResponse<BatchTask> recheck(@RequestBody(required = false) RecheckRequest request) {
        String params = request == null || request.getStoreCode() == null
                ? null : "{\"storeCode\":\"" + request.getStoreCode().trim() + "\"}";
        return ApiResponse.ok(taskService.create(SensitiveRecheckService.TASK_TYPE, params));
    }
}
