package com.evops.sensitive.dto;

import java.util.List;

/**
 * 反向核对报告：以 t_process_order 为准重扫全量加工单，与最新写路径留痕比对。
 * drift 判定：当前文本按现词库需要的处置（PASS<MASK<BLOCK）重于留痕时的处置——
 * 多为词库变严或文本绕开守门被改；已打码文本重扫为 PASS 不算 drift。
 */
public class RecheckReport {

    private final String dictVersion;
    private final int totalOrders;
    /** 无任何写路径留痕的加工单号（如历史直插数据） */
    private final List<String> neverScanned;
    /** 处置加重的字段差异 */
    private final List<FieldDrift> changed;
    /** 留痕与重扫一致的字段数 */
    private final int matchedFields;

    public RecheckReport(String dictVersion, int totalOrders, List<String> neverScanned,
                         List<FieldDrift> changed, int matchedFields) {
        this.dictVersion = dictVersion;
        this.totalOrders = totalOrders;
        this.neverScanned = neverScanned;
        this.changed = changed;
        this.matchedFields = matchedFields;
    }

    public String getDictVersion() { return dictVersion; }
    public int getTotalOrders() { return totalOrders; }
    public List<String> getNeverScanned() { return neverScanned; }
    public List<FieldDrift> getChanged() { return changed; }
    public int getMatchedFields() { return matchedFields; }

    /** 单字段差异：留痕处置 vs 当前词库重扫处置 */
    public static class FieldDrift {
        private final String orderNo;
        private final String field;
        private final String recordedAction;
        private final int recordedHits;
        private final String currentAction;
        private final int currentHits;

        public FieldDrift(String orderNo, String field, String recordedAction, int recordedHits,
                          String currentAction, int currentHits) {
            this.orderNo = orderNo;
            this.field = field;
            this.recordedAction = recordedAction;
            this.recordedHits = recordedHits;
            this.currentAction = currentAction;
            this.currentHits = currentHits;
        }

        public String getOrderNo() { return orderNo; }
        public String getField() { return field; }
        public String getRecordedAction() { return recordedAction; }
        public int getRecordedHits() { return recordedHits; }
        public String getCurrentAction() { return currentAction; }
        public int getCurrentHits() { return currentHits; }
    }
}
