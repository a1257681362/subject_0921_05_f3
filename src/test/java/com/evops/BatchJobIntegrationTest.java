package com.evops;

import com.evops.batch.maintenance.BatchJobMaintenance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 加工单批量后台任务集成测试：
 * 提交即返回任务号、5% 粒度进度、终态报告（neverScanned）、协作式取消、
 * 断点重跑（已成功单不重复处理/不重复留痕）、重复提交兜底、21 天到期物理清理、非法状态流转。
 * 测试上下文不设词库（空词库，文本全部 PASS）；单单节流 50ms 以稳定制造取消窗口。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "evops.batch.item-sleep-ms=50")
@Import(BatchJobIntegrationTest.TestShiroBypass.class)
class BatchJobIntegrationTest {

    @TestConfiguration
    static class TestShiroBypass {
        @Bean
        ShiroFilterChainDefinition shiroFilterChainDefinition() {
            DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
            chain.addPathDefinition("/**", "anon");
            return chain;
        }
    }

    @Resource
    private MockMvc mockMvc;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private BatchJobMaintenance maintenance;

    @Value("${evops.batch.retain-days:21}")
    private int retainDays;

    // 同一测试类共享一个 Spring 上下文（内存库），计数器必须跨用例实例自增，避免审计请求号撞唯一约束
    private static final AtomicLong OPERATOR_SEQ = new AtomicLong(3000);
    private static final AtomicLong REQ_SEQ = new AtomicLong(6000);

    // ---------------- 提交 + 5% 进度 + 终态报告 ----------------

    @Test
    void job_runs_in_background_with_progress_and_report() throws Exception {
        long lensId = createLens("L-BATCH-A");
        createOrder("O-BATCH-A1", lensId, "批量客户甲", null, "S10");
        // 历史直插数据：无写路径扫描留痕，应进 neverScanned
        jdbcTemplate.update(
                "INSERT INTO t_process_order (order_no, customer_name, store_code, lens_id, order_status, request_no)"
                        + " VALUES ('O-BATCH-RAW', '直插客户', 'S10', " + lensId + ", 'CREATED', 'BATCH-RAW-1')");

        String jobNo = submitJob("S10", "BATCH-SUBMIT-1");
        JsonNode data = waitForStatus(jobNo, "SUCCEEDED", 15_000);

        assertEquals(2, data.get("totalCount").asInt());
        assertEquals(2, data.get("processedCount").asInt());
        assertEquals(2, data.get("successCount").asInt());
        assertEquals(0, data.get("failedCount").asInt());
        assertEquals(100, data.get("progressPct").asInt());
        assertTrue(data.get("expireTime") != null && !data.get("expireTime").isNull(),
                "终态任务应写过期时间（完成 + " + retainDays + " 天）");

        JsonNode result = data.get("result");
        assertEquals(2, result.get("totalOrders").asInt());
        assertTrue(contains(result.get("neverScanned"), "O-BATCH-RAW"),
                "直插无留痕的加工单应列入 neverScanned");
        assertEquals(0, result.get("changed").size(), "空词库重扫无差异");
        assertEquals(2, result.get("matchedFields").asInt());
    }

    // ---------------- 取消 + 断点重跑 ----------------

    @Test
    void job_cancellable_then_rerun_resumes_from_checkpoint() throws Exception {
        long lensId = createLens("L-BATCH-C");
        int total = 40;
        for (int i = 1; i <= total; i++) {
            createOrder("O-BATCH-C" + i, lensId, "批量取消客户" + i, null, "S20");
        }

        String jobNo = submitJob("S20", "BATCH-CANCEL-1");

        // 等任务跑到一半：记录轮询过程中的进度档位，全部应为 5 的整数倍
        List<Integer> buckets = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 10_000;
        JsonNode running = null;
        while (System.currentTimeMillis() < deadline) {
            running = getJob(jobNo);
            int processed = running.get("processedCount").asInt();
            int pct = running.get("progressPct").asInt();
            buckets.add(pct);
            assertEquals(0, pct % 5, "进度必须是 5 的整数倍");
            if (processed > 0 && processed < total) {
                break;
            }
            Thread.sleep(20);
        }
        assertTrue(running.get("processedCount").asInt() > 0, "任务应已开始处理");
        assertTrue(running.get("processedCount").asInt() < total, "应在完成前捕获到执行中状态");

        // 协作式取消
        mockMvc.perform(post("/api/batch/jobs/" + jobNo + "/cancel")
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", "BATCH-CANCEL-C"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.cancelRequested").value(true));

        JsonNode cancelled = waitForStatus(jobNo, "CANCELLED", 15_000);
        int doneBeforeRerun = cancelled.get("processedCount").asInt();
        assertTrue(doneBeforeRerun < total, "取消时应仍有未处理单");

        // 断点重跑：已成功的单不重复处理
        mockMvc.perform(post("/api/batch/jobs/" + jobNo + "/rerun")
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", "BATCH-RERUN-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.jobStatus").value("PENDING"));

        JsonNode succeeded = waitForStatus(jobNo, "SUCCEEDED", 20_000);
        assertEquals(total, succeeded.get("totalCount").asInt());
        assertEquals(total, succeeded.get("successCount").asInt());
        assertEquals(100, succeeded.get("progressPct").asInt());

        // 明细：每单恰好一行
        Integer itemRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_batch_job_item WHERE job_id="
                        + "(SELECT id FROM t_batch_job WHERE job_no='" + jobNo + "')", Integer.class);
        assertEquals(total, itemRows, "断点重跑不应产生重复明细行");

        // RECHECK 留痕：每张单恰好 2 条（左右两个守门字段），未被重跑重复扫描
        Integer loggedOrders = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT order_no) FROM t_sensitive_scan_log"
                        + " WHERE oper_type='RECHECK' AND order_no LIKE 'O-BATCH-C%'", Integer.class);
        assertEquals(total, loggedOrders);
        Integer duplicated = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT order_no FROM t_sensitive_scan_log"
                        + " WHERE oper_type='RECHECK' AND order_no LIKE 'O-BATCH-C%'"
                        + " GROUP BY order_no HAVING COUNT(*) <> 2)", Integer.class);
        assertEquals(0, duplicated, "每张单应恰好 2 条 RECHECK 留痕（成功单未重扫）");
    }

    // ---------------- 重复提交兜底 ----------------

    @Test
    void duplicate_request_no_rejected() throws Exception {
        // 同一 X-Request-No 第二次提交：库层唯一约束兜底
        String resp = mockMvc.perform(post("/api/batch/jobs").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", "BATCH-DUP-1")
                        .content("{\"jobType\":\"RECHECK\",\"storeCode\":\"S99\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
                .andReturn().getResponse().getContentAsString();
        // 等首个任务跑完（S99 无单，瞬间完成），避免活动任务窗口影响其他用例
        waitForStatus(objectMapper.readTree(resp).get("data").get("jobNo").asText(), "SUCCEEDED", 15_000);

        mockMvc.perform(post("/api/batch/jobs").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", "BATCH-DUP-1")
                        .content("{\"jobType\":\"RECHECK\",\"storeCode\":\"S99\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("REQUEST_DUPLICATED"));
    }

    // ---------------- 21 天到期物理清理 ----------------

    @Test
    void expired_jobs_physically_purged_with_items() throws Exception {
        long lensId = createLens("L-BATCH-P");
        createOrder("O-BATCH-P1", lensId, "批量清理客户", null, "S40");
        String jobNo = submitJob("S40", "BATCH-PURGE-1");
        waitForStatus(jobNo, "SUCCEEDED", 15_000);

        long jobId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_batch_job WHERE job_no='" + jobNo + "'", Long.class);
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_batch_job_item WHERE job_id=" + jobId, Integer.class) > 0);

        // 把过期时间回拨到过去，触发清理
        jdbcTemplate.update("UPDATE t_batch_job SET expire_time=TIMESTAMPADD('DAY', -1, CURRENT_TIMESTAMP)"
                + " WHERE id=" + jobId);
        int removed = maintenance.purgeExpired();
        assertTrue(removed >= 1);

        Integer jobRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_batch_job WHERE id=" + jobId, Integer.class);
        Integer itemRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_batch_job_item WHERE job_id=" + jobId, Integer.class);
        assertEquals(0, jobRows, "到期任务记录应物理删除（运维数据 21 天保留的唯一例外）");
        assertEquals(0, itemRows, "任务明细随任务一起物理删除");
    }

    // ---------------- 非法状态 / 未知任务 ----------------

    @Test
    void illegal_transitions_and_unknown_job_rejected() throws Exception {
        String jobNo = submitJob("S99", "BATCH-ILLEGAL-1"); // 无该门店 → 0 单，瞬间完成
        JsonNode done = waitForStatus(jobNo, "SUCCEEDED", 15_000);
        assertEquals(0, done.get("totalCount").asInt());

        mockMvc.perform(post("/api/batch/jobs/" + jobNo + "/cancel")
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", "BATCH-ILLEGAL-C"))
                .andExpect(jsonPath("$.code").value("JOB_STATUS_ILLEGAL"));

        mockMvc.perform(post("/api/batch/jobs/" + jobNo + "/rerun")
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", "BATCH-ILLEGAL-R"))
                .andExpect(jsonPath("$.code").value("JOB_STATUS_ILLEGAL"));

        mockMvc.perform(get("/api/batch/jobs/NO-SUCH-JOB"))
                .andExpect(jsonPath("$.code").value("JOB_NOT_FOUND"));

        mockMvc.perform(post("/api/batch/jobs").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", "BATCH-ILLEGAL-T")
                        .content("{\"jobType\":\"WHATEVER\"}"))
                .andExpect(jsonPath("$.code").value("JOB_TYPE_UNSUPPORTED"));
    }

    // ---------------- 辅助 ----------------

    private String submitJob(String storeCode, String requestNo) throws Exception {
        String body = storeCode == null
                ? "{\"jobType\":\"RECHECK\"}"
                : "{\"jobType\":\"RECHECK\",\"storeCode\":\"" + storeCode + "\"}";
        String resp = mockMvc.perform(post("/api/batch/jobs").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", requestNo)
                        .content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"))
                .andReturn().getResponse().getContentAsString();
        String jobNo = objectMapper.readTree(resp).get("data").get("jobNo").asText();
        assertTrue(jobNo.startsWith("RECHECK-"), "任务号应以 RECHECK- 开头");
        return jobNo;
    }

    private JsonNode getJob(String jobNo) throws Exception {
        String resp = mockMvc.perform(get("/api/batch/jobs/" + jobNo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("data");
    }

    private JsonNode waitForStatus(String jobNo, String status, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            JsonNode data = getJob(jobNo);
            if (status.equals(data.get("jobStatus").asText())) {
                return data;
            }
            Thread.sleep(30);
        }
        fail("等待任务[" + jobNo + "]状态[" + status + "]超时，当前=" + getJob(jobNo));
        return null;
    }

    private boolean contains(JsonNode array, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.asText())) {
                return true;
            }
        }
        return false;
    }

    private long createLens(String code) throws Exception {
        String body = "{\"lensCode\":\"" + code + "\",\"lensName\":\"镜片" + code + "\","
                + "\"lensType\":\"单光\",\"price\":399.00}";
        String resp = mockMvc.perform(post("/api/lens").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", "L-" + code)
                        .content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("data").get("id").asLong();
    }

    private void createOrder(String orderNo, long lensId, String customerName, String remark, String storeCode)
            throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"orderNo\":\"").append(orderNo).append("\",\"lensId\":").append(lensId)
                .append(",\"customerName\":\"").append(customerName).append("\",\"storeCode\":\"")
                .append(storeCode).append("\",");
        if (remark != null) {
            sb.append("\"remark\":\"").append(remark).append("\",");
        }
        sb.append("\"prescriptions\":[")
                .append("{\"eye\":\"L\",\"sphere\":-1.00},")
                .append("{\"eye\":\"R\",\"sphere\":-1.00}]}");
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", String.valueOf(OPERATOR_SEQ.incrementAndGet()))
                        .header("X-Request-No", "REQ-" + REQ_SEQ.incrementAndGet())
                        .content(sb.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"));
    }
}
