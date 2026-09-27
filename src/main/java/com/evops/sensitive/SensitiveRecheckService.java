package com.evops.sensitive;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizCode;
import com.evops.common.BizException;
import com.evops.order.entity.ProcessOrder;
import com.evops.order.mapper.ProcessOrderMapper;
import com.evops.sensitive.dto.RecheckReport;
import com.evops.sensitive.entity.SensitiveScanLog;
import com.evops.sensitive.mapper.SensitiveScanLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
 * <p>
 * 两种执行方式：
 * 同步 {@link #recheck(String)}（单事务，应急/小批量）；
 * 后台批量任务按单调 {@link #recheckOneInNewTx(Long)}（每单独立事务，失败不影响其他单、支持断点重跑）。
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

    /** 同步核对：全量一个事务（保持历史行为，给别组取数/应急小批量用） */
    @Transactional(rollbackFor = Exception.class)
    public RecheckReport recheck(String storeCode) {
        List<ProcessOrder> orders = loadTargets(storeCode);

        // 每 (order_no, field) 最新一条写路径留痕：按 id 升序遍历，后者覆盖前者
        Map<String, SensitiveScanLog> latestByField = loadAllLatestWriteLogs();

        List<String> neverScanned = new ArrayList<>();
        List<RecheckReport.FieldDrift> changed = new ArrayList<>();
        int matchedFields = 0;

        for (ProcessOrder order : orders) {
            OrderRecheckResult result = processOrder(order, latestByField);
            if (result.isNeverScanned()) {
                // 整单无写路径留痕（历史直插数据）：只记 neverScanned，不重复列字段差异
                neverScanned.add(order.getOrderNo());
            } else {
                changed.addAll(result.getDrifts());
                matchedFields += result.getMatchedFields();
            }
        }
        return new RecheckReport(wordService.dictVersion(), orders.size(),
                neverScanned, changed, matchedFields);
    }

    /** 批量任务用：取核对目标快照（按 id 升序，保证多次跑顺序稳定，断点可对齐） */
    public List<ProcessOrder> loadTargets(String storeCode) {
        LambdaQueryWrapper<ProcessOrder> qw = new LambdaQueryWrapper<>();
        if (storeCode != null && !storeCode.trim().isEmpty()) {
            qw.eq(ProcessOrder::getStoreCode, storeCode.trim());
        }
        qw.orderByAsc(ProcessOrder::getId);
        return orderMapper.selectList(qw);
    }

    /**
     * 批量任务按单核对：每单一个独立事务——单条失败只回滚该单的 RECHECK 留痕，
     * 不影响其他单；重跑时已成功的单可直接跳过（断点续跑）。须经 Spring 代理调用
     * （由 BatchJobRunner 跨 Bean 调用），不可在本类内部自调用。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public OrderRecheckResult recheckOneInNewTx(Long orderId) {
        ProcessOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(BizCode.ORDER_NOT_FOUND);
        }
        return processOrder(order, loadLatestWriteLogs(order.getOrderNo()));
    }

    /** 处理一单：扫 2 个守门字段、写 RECHECK 快照留痕、算 neverScanned/字段差异 */
    private OrderRecheckResult processOrder(ProcessOrder order,
                                            Map<String, SensitiveScanLog> latestByField) {
        boolean anyRecorded = false;
        List<RecheckReport.FieldDrift> drifts = new ArrayList<>();
        int matchedFields = 0;
        for (String field : GUARDED_FIELDS) {
            String text = fieldValue(order, field);
            ScanOutcome outcome = wordService.scan(text);
            scanLogService.logInCallerTx(order.getId(), order.getOrderNo(), field, outcome, "RECHECK");

            SensitiveScanLog recorded = latestByField.get(key(order.getOrderNo(), field));
            if (recorded == null) {
                drifts.add(new RecheckReport.FieldDrift(order.getOrderNo(), field,
                        "NONE", -1, outcome.action(), outcome.getHits().size()));
                continue;
            }
            anyRecorded = true;
            if (severity(outcome.action()) > severity(recorded.getAction())) {
                drifts.add(new RecheckReport.FieldDrift(order.getOrderNo(), field,
                        recorded.getAction(), recorded.getHitCount(),
                        outcome.action(), outcome.getHits().size()));
            } else {
                matchedFields++;
            }
        }
        return new OrderRecheckResult(order.getId(), order.getOrderNo(), !anyRecorded, drifts, matchedFields);
    }

    /** 全量最新写路径留痕（同步路径一次查全） */
    private Map<String, SensitiveScanLog> loadAllLatestWriteLogs() {
        Map<String, SensitiveScanLog> latestByField = new HashMap<>();
        List<SensitiveScanLog> writeLogs = scanLogMapper.selectList(
                new LambdaQueryWrapper<SensitiveScanLog>()
                        .in(SensitiveScanLog::getOperType, WRITE_OPER_TYPES)
                        .orderByAsc(SensitiveScanLog::getId));
        for (SensitiveScanLog log : writeLogs) {
            latestByField.put(key(log.getOrderNo(), log.getFieldName()), log);
        }
        return latestByField;
    }

    /** 单单最新写路径留痕（批量任务逐单查询，按单独立事务内调用） */
    private Map<String, SensitiveScanLog> loadLatestWriteLogs(String orderNo) {
        Map<String, SensitiveScanLog> latestByField = new HashMap<>();
        List<SensitiveScanLog> writeLogs = scanLogMapper.selectList(
                new LambdaQueryWrapper<SensitiveScanLog>()
                        .eq(SensitiveScanLog::getOrderNo, orderNo)
                        .in(SensitiveScanLog::getOperType, WRITE_OPER_TYPES)
                        .orderByAsc(SensitiveScanLog::getId));
        for (SensitiveScanLog log : writeLogs) {
            latestByField.put(key(log.getOrderNo(), log.getFieldName()), log);
        }
        return latestByField;
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
