package com.evops.order.controller;

import com.evops.common.ApiResponse;
import com.evops.order.dto.OrderActionRequest;
import com.evops.order.dto.OrderCreateRequest;
import com.evops.order.dto.OrderUpdateRequest;
import com.evops.order.entity.ProcessOrder;
import com.evops.order.entity.ProcessOrderRx;
import com.evops.order.entity.ProcessOrderStatusLog;
import com.evops.order.service.ProcessOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 加工单接口。写接口请求头携带 X-Operator-Id / X-Request-No / X-Biz-Time。
 */
@RestController
@RequestMapping("/api/orders")
public class ProcessOrderController {

    private final ProcessOrderService orderService;

    public ProcessOrderController(ProcessOrderService orderService) {
        this.orderService = orderService;
    }

    /** 建档：主表+左右眼处方同一事务 */
    @PostMapping
    public ApiResponse<ProcessOrder> create(@Valid @RequestBody OrderCreateRequest request) {
        return ApiResponse.ok(orderService.create(request));
    }

    /** 修改：仅已建档状态可改，处方整组替换 */
    @PutMapping
    public ApiResponse<ProcessOrder> update(@Valid @RequestBody OrderUpdateRequest request) {
        return ApiResponse.ok(orderService.update(request));
    }

    /** 按加工单号检索（主单 + 左右眼有效处方 + 状态流转记录） */
    @GetMapping("/by-no/{orderNo}")
    public ApiResponse<Map<String, Object>> getByOrderNo(@PathVariable String orderNo) {
        ProcessOrder order = orderService.getByOrderNo(orderNo);
        return ApiResponse.ok(assembleDetail(order));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable Long id) {
        ProcessOrder order = orderService.get(id);
        return ApiResponse.ok(assembleDetail(order));
    }

    @GetMapping
    public ApiResponse<List<ProcessOrder>> list(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) String storeCode) {
        return ApiResponse.ok(orderService.list(status, storeCode));
    }

    @PostMapping("/{id}/start")
    public ApiResponse<ProcessOrder> start(@PathVariable Long id,
                                           @RequestBody(required = false) OrderActionRequest request) {
        return ApiResponse.ok(orderService.start(id, action(request)));
    }

    @PostMapping("/{id}/finish")
    public ApiResponse<ProcessOrder> finish(@PathVariable Long id,
                                            @RequestBody(required = false) OrderActionRequest request) {
        return ApiResponse.ok(orderService.finish(id, action(request)));
    }

    @PostMapping("/{id}/pickup")
    public ApiResponse<ProcessOrder> pickup(@PathVariable Long id,
                                            @RequestBody(required = false) OrderActionRequest request) {
        return ApiResponse.ok(orderService.pickup(id, action(request)));
    }

    /** 冲销（禁止物理删除，留流转记录） */
    @PostMapping("/{id}/cancel")
    public ApiResponse<ProcessOrder> cancel(@PathVariable Long id,
                                            @RequestBody(required = false) OrderActionRequest request) {
        return ApiResponse.ok(orderService.cancel(id, action(request)));
    }

    /** 冲销恢复 */
    @PostMapping("/{id}/restore")
    public ApiResponse<ProcessOrder> restore(@PathVariable Long id,
                                             @RequestBody(required = false) OrderActionRequest request) {
        return ApiResponse.ok(orderService.restore(id, action(request)));
    }

    private OrderActionRequest action(OrderActionRequest request) {
        return request == null ? new OrderActionRequest() : request;
    }

    private Map<String, Object> assembleDetail(ProcessOrder order) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("order", order);
        List<ProcessOrderRx> prescriptions = orderService.listActivePrescriptions(order.getId());
        detail.put("prescriptions", prescriptions);
        List<ProcessOrderStatusLog> statusLogs = orderService.listStatusLogs(order.getId());
        detail.put("statusLogs", statusLogs);
        return detail;
    }
}
