package com.evops.sensitive;

import com.evops.common.BizCode;
import com.evops.common.BizException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * 敏感词扫描与加工单文本守门。
 * 写路径分级处置：任一字段 BLOCK 命中 → 留痕（独立事务）后整单拒绝；
 * MASK 命中 → 字段替换为打码文本放行；无命中也留 PASS 痕，供反向核对确认覆盖。
 */
@Service
public class SensitiveWordService {

    /** 加工单纳入管控的文本入口 */
    public static final String FIELD_CUSTOMER_NAME = "customerName";
    public static final String FIELD_REMARK = "remark";

    @Resource
    private SensitiveWordManager wordManager;
    @Resource
    private SensitiveScanLogService scanLogService;

    /** 试扫（/api/sensitive/scan）：只扫描归一化匹配，不写留痕 */
    public ScanOutcome scan(String text) {
        DfaScanner scanner = wordManager.scanner();
        NormalizedText normalized = TextNormalizer.normalize(text == null ? "" : text);
        List<SensitiveHit> hits = scanner.scan(normalized, text == null ? "" : text);
        return ScanOutcome.of(text, hits, wordManager.dictVersion());
    }

    /** 当前词库版本 */
    public String dictVersion() {
        return wordManager.dictVersion();
    }

    /**
     * 加工单文本守门（create/update 写路径，与主单同事务）。
     *
     * @return 打码后的字段值（无 MASK 命中时与原值相同）
     * @throws BizException 任一字段 BLOCK 命中：SENSITIVE_WORD_BLOCKED，整单不落库
     */
    public MaskedOrderText guardOrderText(Long orderId, String orderNo,
                                          String customerName, String remark, String operType) {
        ScanOutcome nameOutcome = scan(customerName);
        ScanOutcome remarkOutcome = scan(remark);

        if (nameOutcome.isBlocked() || remarkOutcome.isBlocked()) {
            // 拦截尝试留痕：独立事务提交，加工单回滚后仍可查
            if (nameOutcome.isBlocked()) {
                scanLogService.logBlockedNewTx(orderId, orderNo, FIELD_CUSTOMER_NAME, nameOutcome, operType);
            }
            if (remarkOutcome.isBlocked()) {
                scanLogService.logBlockedNewTx(orderId, orderNo, FIELD_REMARK, remarkOutcome, operType);
            }
            throw new BizException(BizCode.SENSITIVE_WORD_BLOCKED, blockDetail(nameOutcome, remarkOutcome));
        }

        scanLogService.logInCallerTx(orderId, orderNo, FIELD_CUSTOMER_NAME, nameOutcome, operType);
        scanLogService.logInCallerTx(orderId, orderNo, FIELD_REMARK, remarkOutcome, operType);
        return new MaskedOrderText(nameOutcome.getMaskedText(), remarkOutcome.getMaskedText());
    }

    private String blockDetail(ScanOutcome nameOutcome, ScanOutcome remarkOutcome) {
        StringBuilder sb = new StringBuilder();
        appendFieldDetail(sb, FIELD_CUSTOMER_NAME, nameOutcome);
        appendFieldDetail(sb, FIELD_REMARK, remarkOutcome);
        return sb.toString();
    }

    private void appendFieldDetail(StringBuilder sb, String field, ScanOutcome outcome) {
        if (!outcome.isBlocked()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('；');
        }
        sb.append(field).append(' ');
        for (SensitiveHit hit : outcome.getHits()) {
            if (hit.levelEnum() == SensitiveLevel.BLOCK) {
                sb.append('[').append(hit.getStart()).append(',').append(hit.getEnd()).append(')');
            }
        }
        sb.append(" 命中拦截级敏感词");
    }

    /** 守门结果：打码后的两个文本字段 */
    public static class MaskedOrderText {
        private final String customerName;
        private final String remark;

        MaskedOrderText(String customerName, String remark) {
            this.customerName = customerName;
            this.remark = remark;
        }

        public String getCustomerName() {
            return customerName;
        }

        public String getRemark() {
            return remark;
        }
    }
}
