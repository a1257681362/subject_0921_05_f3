package com.evops.lens.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evops.common.ApiResponse;
import com.evops.lens.dto.LensSaveRequest;
import com.evops.lens.dto.LensUpdateRequest;
import com.evops.lens.entity.Lens;
import com.evops.lens.service.LensService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Map;

/**
 * 镜片基础信息接口。写接口请求头携带 X-Operator-Id / X-Request-No / X-Biz-Time。
 */
@RestController
@RequestMapping("/api/lens")
public class LensController {

    private final LensService lensService;

    public LensController(LensService lensService) {
        this.lensService = lensService;
    }

    @PostMapping
    public ApiResponse<Lens> create(@Valid @RequestBody LensSaveRequest request) {
        return ApiResponse.ok(lensService.create(request));
    }

    @PutMapping
    public ApiResponse<Lens> update(@Valid @RequestBody LensUpdateRequest request) {
        return ApiResponse.ok(lensService.update(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<Lens> get(@PathVariable Long id) {
        return ApiResponse.ok(lensService.get(id));
    }

    @GetMapping
    public ApiResponse<Page<Lens>> page(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(lensService.page(current, size, status, keyword));
    }

    /** 作废（禁止物理删除） */
    @PostMapping("/{id}/discard")
    public ApiResponse<Void> discard(@PathVariable Long id,
                                     @RequestBody(required = false) Map<String, String> body) {
        lensService.discard(id, body == null ? null : body.get("remark"));
        return ApiResponse.ok(null);
    }

    /** 恢复 */
    @PostMapping("/{id}/restore")
    public ApiResponse<Void> restore(@PathVariable Long id,
                                     @RequestBody(required = false) Map<String, String> body) {
        lensService.restore(id, body == null ? null : body.get("remark"));
        return ApiResponse.ok(null);
    }
}
