package com.evops.sensitive;

import com.evops.common.ApiResponse;
import com.evops.sensitive.dto.RecheckReport;
import com.evops.sensitive.dto.RecheckRequest;
import com.evops.sensitive.dto.ScanRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 敏感词管控接口。试扫给前端标记用；reload/info 管词库；recheck 反向核对给别组校验数据。
 */
@RestController
@RequestMapping("/api/sensitive")
public class SensitiveWordController {

    private final SensitiveWordService wordService;
    private final SensitiveWordManager wordManager;
    private final SensitiveRecheckService recheckService;

    public SensitiveWordController(SensitiveWordService wordService,
                                   SensitiveWordManager wordManager,
                                   SensitiveRecheckService recheckService) {
        this.wordService = wordService;
        this.wordManager = wordManager;
        this.recheckService = recheckService;
    }

    /** 试扫：返回命中位置（原文坐标，end 开区间）、打码结果、是否拦截、词库版本 */
    @PostMapping("/scan")
    public ApiResponse<ScanOutcome> scan(@Valid @RequestBody ScanRequest request) {
        return ApiResponse.ok(wordService.scan(request.getText()));
    }

    /** 立即重载词库（热更新） */
    @PostMapping("/words/reload")
    public ApiResponse<SensitiveWordManager.DictStatus> reload() {
        return ApiResponse.ok(wordManager.reload());
    }

    /** 当前词库状态（不输出词条原文） */
    @GetMapping("/words/info")
    public ApiResponse<SensitiveWordManager.DictStatus> info() {
        return ApiResponse.ok(wordManager.status());
    }

    /** 反向核对：以加工单为准重扫比对，输出未覆盖与处置加重差异 */
    @PostMapping("/recheck")
    public ApiResponse<RecheckReport> recheck(@RequestBody(required = false) RecheckRequest request) {
        String storeCode = request == null ? null : request.getStoreCode();
        return ApiResponse.ok(recheckService.recheck(storeCode));
    }
}
