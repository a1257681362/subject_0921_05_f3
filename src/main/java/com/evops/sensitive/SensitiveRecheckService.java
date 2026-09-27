package com.evops.sensitive;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.order.entity.ProcessOrder;
import com.evops.order.mapper.ProcessOrderMapper;
import com.evops.sensitive.dto.RecheckReport;
import com.evops.sensitive.entity.SensitiveScanLog;
import com.evops.sensitive.mapper.SensitiveScanLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 反向核对：以 t_process_order 为准，用当前词库重扫全量加工单文本，
 * 与每单每字段最新一条写路径留痕（CREATE/UPDATE）比对，差异输出给别组校验。
 * 每单写字段级 RECHECK 留痕，形成核对时刻的完整快照。
 * <p>
 * drift 判定（处置加重才报）：PASS &lt; MASK &lt; BLOCK，现扫描处置重于留痕处置即差异
 * （多为词库变严或文本绕开守门被改）；已打码文本重扫为 PASS 属正常，不算差异。
 */
@Service
public class SensitiveRecheckService {

    private static final List<String> WRITE_OPER_TYPES = Arrays.asList("CREATE", "UPDATE");
    private static final List<String> GUARDED_FIELDS = Arrays.asList(
            SensitiveWordService.FIELD_CUSTOMER_NAME, SensitiveWordService.FIELD_REMARK);

    @Resource
    private ProcessOrderMapper orderMapper;
    @Resource
    private SensitiveScanLogMapper scanLogMapper;
    @Resource
    private SensitiveWordService wordService;
    @Resource
    private SensitiveScanLogService scanLogService;

    @Transactional(rollbackFor = Exception.class)
    public RecheckReport recheck(String storeCode) {
        LambdaQueryWrapper<ProcessOrder> qw = new LambdaQueryWrapper<>();
        if (storeCode != null && !storeCode.trim().isEmpty()) {
            qw.eq(ProcessOrder::getStoreCode, storeCode.trim());
        }
        qw.orderByAsc(ProcessOrder::getId);
        List<ProcessOrder> orders = orderMapper.selectList(qw);

        // 每 (order_no, field) 最新一条写路径留痕：按 id 升序遍历，后者覆盖前者
        Map<String, SensitiveScanLog> latestByField = new HashMap<>();
        List<SensitiveScanLog> writeLogs = scanLogMapper.selectList(
                new LambdaQueryWrapper<SensitiveScanLog>()
                        .in(SensitiveScanLog::getOperType, WRITE_OPER_TYPES)
                        .orderByAsc(SensitiveScanLog::getId));
        for (SensitiveScanLog log : writeLogs) {
            latestByField.put(key(log.getOrderNo(), log.getFieldName()), log);
        }

        List<String> neverScanned = new ArrayList<>();
        List<RecheckReport.FieldDrift> changed = new ArrayList<>();
        int matchedFields = 0;

        for (ProcessOrder order : orders) {
            boolean anyRecorded = false;
            List<RecheckReport.FieldDrift> orderDrifts = new ArrayList<>();
            int orderMatched = 0;
            for (String field : GUARDED_FIELDS) {
                String text = fieldValue(order, field);
                ScanOutcome outcome = wordService.scan(text);
                scanLogService.logInCallerTx(order.getId(), order.getOrderNo(), field, outcome, "RECHECK");

                SensitiveScanLog recorded = latestByField.get(key(order.getOrderNo(), field));
                if (recorded == null) {
                    orderDrifts.add(new RecheckReport.FieldDrift(order.getOrderNo(), field,
                            "NONE", -1, outcome.action(), outcome.getHits().size()));
                    continue;
                }
                anyRecorded = true;
                if (severity(outcome.action()) > severity(recorded.getAction())) {
                    orderDrifts.add(new RecheckReport.FieldDrift(order.getOrderNo(), field,
                            recorded.getAction(), recorded.getHitCount(),
                            outcome.action(), outcome.getHits().size()));
                } else {
                    orderMatched++;
                }
            }
            if (!anyRecorded) {
                // 整单无写路径留痕（历史直插数据）：只记 neverScanned，不重复列字段差异
                neverScanned.add(order.getOrderNo());
            } else {
                changed.addAll(orderDrifts);
                matchedFields += orderMatched;
            }
        }
        return new RecheckReport(wordService.dictVersion(), orders.size(),
                neverScanned, changed, matchedFields);
    }

    private static int severity(String action) {
        if ("BLOCK".equals(action)) {
            return 2;
        }
        if ("MASK".equals(action)) {
            return 1;
        }
        return 0;
    }

    private static String key(String orderNo, String field) {
        return orderNo + "|" + field;
    }

    private static String fieldValue(ProcessOrder order, String field) {
        if (SensitiveWordService.FIELD_CUSTOMER_NAME.equals(field)) {
            return order.getCustomerName();
        }
        return order.getRemark();
    }
}
