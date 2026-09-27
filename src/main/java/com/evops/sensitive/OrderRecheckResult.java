package com.evops.sensitive;

import com.evops.sensitive.dto.RecheckReport;

import java.util.Collections;
import java.util.List;

/**
 * 单张加工单的反向核对结果（后台批量任务按单处理的产出）。
 * neverScanned：整单无任何写路径留痕（如历史直插数据）；
 * drifts：有留痕前提下各字段的处置加重差异（无留痕字段以 recordedAction=NONE 列出）；
 * matchedFields：留痕与现词库重扫一致的字段数。
 */
public class OrderRecheckResult {

    private final Long orderId;
    private final String orderNo;
    private final boolean neverScanned;
    private final List<RecheckReport.FieldDrift> drifts;
    private final int matchedFields;

    public OrderRecheckResult(Long orderId, String orderNo, boolean neverScanned,
                              List<RecheckReport.FieldDrift> drifts, int matchedFields) {
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.neverScanned = neverScanned;
        this.drifts = drifts;
        this.matchedFields = matchedFields;
    }

    public Long getOrderId() { return orderId; }
    public String getOrderNo() { return orderNo; }
    public boolean isNeverScanned() { return neverScanned; }
    public List<RecheckReport.FieldDrift> getDrifts() {
        return drifts == null ? Collections.emptyList() : drifts;
    }
    public int getMatchedFields() { return matchedFields; }
}
