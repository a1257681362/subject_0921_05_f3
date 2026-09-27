package com.evops.sensitive;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.order.entity.ProcessOrder;
import com.evops.order.mapper.ProcessOrderMapper;
import com.evops.task.BatchTaskHandler;
import com.evops.task.TaskItemSeed;
import com.evops.task.entity.BatchTask;
import com.evops.task.entity.BatchTaskItem;
import com.evops.sensitive.entity.SensitiveScanLog;
import com.evops.sensitive.mapper.SensitiveScanLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 反向核对任务（SENSITIVE_RECHECK）：以 t_process_order 为准，用当前词库逐单重扫文本，
 * 与每单每字段最新一条写路径留痕（CREATE/UPDATE）比对。
 * <p>
 * 改造为后台批量任务：plan 圈定全量（可按门店收窄）加工单，逐项独立事务处理
 * （一项失败只回滚该项，可断点重跑），结果写入任务项，任务级给汇总。
 * <p>
 * drift 判定（处置加重才报）：PASS &lt; MASK &lt; BLOCK，现扫描处置重于留痕处置即差异
 * （多为词库变严或文本绕开守门被改）；已打码文本重扫为 PASS 属正常，不算差异。
 */
@Service
public class SensitiveRecheckService implements BatchTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(SensitiveRecheckService.class);

    public static final String TASK_TYPE = "SENSITIVE_RECHECK";

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
    @Resource
    private ObjectMapper objectMapper;

    @Override
    public String type() {
        return TASK_TYPE;
    }

    /** 圈定待核对加工单（按 id 升序，稳定顺序），决定任务总数与进度基数 */
    @Override
    public List<TaskItemSeed> plan(String paramsJson) {
        LambdaQueryWrapper<ProcessOrder> qw = new LambdaQueryWrapper<ProcessOrder>()
                .orderByAsc(ProcessOrder::getId);
        String storeCode = parseStoreCode(paramsJson);
        if (storeCode != null) {
            qw.eq(ProcessOrder::getStoreCode, storeCode);
        }
        List<ProcessOrder> orders = orderMapper.selectList(qw);
        List<TaskItemSeed> seeds = new ArrayList<>(orders.size());
        for (ProcessOrder order : orders) {
            seeds.add(new TaskItemSeed(order.getOrderNo(), order.getId()));
        }
        return seeds;
    }

    /** 处理单张加工单（运行在任务项独立事务里）：两字段重扫 + 写 RECHECK 留痕 + 比对留痕 */
    @Override
    public ItemResult process(BatchTask task, BatchTaskItem item) {
        ProcessOrder order = item.getTargetId() == null ? null : orderMapper.selectById(item.getTargetId());
        if (order == null) {
            return ItemResult.failed("加工单不存在: " + item.getItemRef());
        }

        Map<String, SensitiveScanLog> latestByField = latestWriteLogs(order.getOrderNo());
        boolean anyRecorded = !latestByField.isEmpty();
        List<Map<String, Object>> drifts = new ArrayList<>();
        int matchedFields = 0;

        for (String field : GUARDED_FIELDS) {
            String text = fieldValue(order, field);
            ScanOutcome outcome = wordService.scan(text);
            scanLogService.logInCallerTx(order.getId(), order.getOrderNo(), field, outcome, "RECHECK");

            SensitiveScanLog recorded = latestByField.get(field);
            if (recorded == null) {
                if (anyRecorded) {
                    drifts.add(drift(field, "NONE", -1, outcome.action(), outcome.getHits().size()));
                }
                continue;
            }
            if (severity(outcome.action()) > severity(recorded.getAction())) {
                drifts.add(drift(field, recorded.getAction(), recorded.getHitCount(),
                        outcome.action(), outcome.getHits().size()));
            } else {
                matchedFields++;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("orderNo", order.getOrderNo());
        result.put("neverScanned", !anyRecorded);
        result.put("matchedFields", matchedFields);
        result.put("drifts", drifts);
        return ItemResult.success(toJson(result));
    }

    /** 任务级汇总：词库版本、总数、一致字段数、未覆盖单数、差异条数（解析各任务项结果） */
    @Override
    public String summarize(BatchTask task, List<BatchTaskItem> items) {
        int neverScanned = 0;
        int driftCount = 0;
        int matchedFields = 0;
        for (BatchTaskItem item : items) {
            if (!"SUCCESS".equals(item.getItemStatus()) || item.getResultJson() == null) {
                continue;
            }
            try {
                Map<?, ?> r = objectMapper.readValue(item.getResultJson(), Map.class);
                if (Boolean.TRUE.equals(r.get("neverScanned"))) {
                    neverScanned++;
                }
                Object mf = r.get("matchedFields");
                if (mf instanceof Number) {
                    matchedFields += ((Number) mf).intValue();
                }
                Object ds = r.get("drifts");
                if (ds instanceof List) {
                    driftCount += ((List<?>) ds).size();
                }
            } catch (Exception ex) {
                log.warn("解析任务项结果失败 item={}", item.getId(), ex);
            }
        }
        Map<String, Object> summary = new HashMap<>();
        summary.put("dictVersion", wordService.dictVersion());
        summary.put("totalOrders", items.size());
        summary.put("matchedFields", matchedFields);
        summary.put("neverScannedOrders", neverScanned);
        summary.put("driftCount", driftCount);
        return toJson(summary);
    }

    // ---------------- 内部 ----------------

    private String parseStoreCode(String paramsJson) {
        if (paramsJson == null || paramsJson.trim().isEmpty()) {
            return null;
        }
        try {
            Map<?, ?> params = objectMapper.readValue(paramsJson, Map.class);
            Object storeCode = params.get("storeCode");
            if (storeCode != null && !storeCode.toString().trim().isEmpty()) {
                return storeCode.toString().trim();
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("任务参数不是合法 JSON: " + paramsJson);
        }
        return null;
    }

    /** 该加工单每字段最新一条写路径留痕：按 id 升序，后者覆盖前者 */
    private Map<String, SensitiveScanLog> latestWriteLogs(String orderNo) {
        Map<String, SensitiveScanLog> latest = new HashMap<>();
        List<SensitiveScanLog> logs = scanLogMapper.selectList(new LambdaQueryWrapper<SensitiveScanLog>()
                .eq(SensitiveScanLog::getOrderNo, orderNo)
                .in(SensitiveScanLog::getOperType, WRITE_OPER_TYPES)
                .orderByAsc(SensitiveScanLog::getId));
        for (SensitiveScanLog logRow : logs) {
            latest.put(logRow.getFieldName(), logRow);
        }
        return latest;
    }

    private Map<String, Object> drift(String field, String recordedAction, int recordedHits,
                                      String currentAction, int currentHits) {
        Map<String, Object> d = new HashMap<>();
        d.put("field", field);
        d.put("recordedAction", recordedAction);
        d.put("recordedHits", recordedHits);
        d.put("currentAction", currentAction);
        d.put("currentHits", currentHits);
        return d;
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

    private static String fieldValue(ProcessOrder order, String field) {
        if (SensitiveWordService.FIELD_CUSTOMER_NAME.equals(field)) {
            return order.getCustomerName();
        }
        return order.getRemark();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            log.warn("任务结果序列化失败", ex);
            return null;
        }
    }
}
