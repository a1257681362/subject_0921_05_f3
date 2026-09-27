package com.evops.sensitive;

import com.evops.sensitive.entity.SensitiveScanLog;
import com.evops.sensitive.mapper.SensitiveScanLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

/**
 * 扫描留痕写入。PASS/MASK 随调用方事务（与加工单写入同生共死）；
 * BLOCK 用 REQUIRES_NEW 独立提交——加工单被拦截回滚后，拦截尝试仍留痕可查。
 */
@Service
public class SensitiveScanLogService {

    @Resource
    private SensitiveScanLogMapper scanLogMapper;
    @Resource
    private ObjectMapper objectMapper;

    /** 随调用方事务写入（PASS / MASK / RECHECK） */
    public void logInCallerTx(Long orderId, String orderNo, String fieldName,
                              ScanOutcome outcome, String operType) {
        scanLogMapper.insert(build(orderId, orderNo, fieldName, outcome, operType));
    }

    /** 独立事务写入（BLOCK）：外层加工单事务回滚不影响拦截留痕 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logBlockedNewTx(Long orderId, String orderNo, String fieldName,
                                ScanOutcome outcome, String operType) {
        scanLogMapper.insert(build(orderId, orderNo, fieldName, outcome, operType));
    }

    private SensitiveScanLog build(Long orderId, String orderNo, String fieldName,
                                   ScanOutcome outcome, String operType) {
        SensitiveScanLog log = new SensitiveScanLog();
        log.setOrderId(orderId);
        log.setOrderNo(orderNo);
        log.setFieldName(fieldName);
        log.setAction(outcome.action());
        log.setHitCount(outcome.getHits().size());
        log.setHitDetail(toJson(outcome));
        log.setDictVersion(outcome.getDictVersion());
        log.setOperType(operType);
        return log;
    }

    /** hit_detail 列宽 4000，留 100 余量截断；命中数由 hit_count 准确记录 */
    private static final int HIT_DETAIL_MAX = 3900;

    private String toJson(ScanOutcome outcome) {
        if (outcome.getHits().isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(outcome.getHits());
            if (json.length() > HIT_DETAIL_MAX) {
                return json.substring(0, HIT_DETAIL_MAX) + "...";
            }
            return json;
        } catch (Exception ex) {
            // 留痕序列化失败不阻断主流程，命中数仍在
            return null;
        }
    }
}
