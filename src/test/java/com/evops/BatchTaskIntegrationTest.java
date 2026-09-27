package com.evops;

import com.evops.task.BatchTaskHandler;
import com.evops.task.TaskItemSeed;
import com.evops.task.entity.BatchTask;
import com.evops.task.entity.BatchTaskItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台批量任务框架集成测试：
 * 异步不堵请求线程、进度量化到 5% 一档、取消（当前项收尾）、失败断点重跑、
 * 终态超保留期归档（不物理删除）。
 * 用两个测试任务处理器：SLOW（每项 sleep，稳定制造取消窗口）、FLAKY（指定项前两次失败第三次成功）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({BatchTaskIntegrationTest.TestShiroBypass.class, BatchTaskIntegrationTest.TestHandlers.class})
class BatchTaskIntegrationTest {

    static final String TYPE_SLOW = "TEST_SLOW";
    static final String TYPE_FLAKY = "TEST_FLAKY";
    static final List<String> TERMINAL_OK = java.util.Arrays.asList("SUCCESS", "FAILED", "CANCELLED");

    @TestConfiguration
    static class TestShiroBypass {
        @Bean
        ShiroFilterChainDefinition shiroFilterChainDefinition() {
            DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
            chain.addPathDefinition("/**", "anon");
            return chain;
        }
    }

    /** 可复位的测试处理器集合，随测试 context 注册，不影响生产任务类型 */
    @TestConfiguration
    static class TestHandlers {
        @Bean
        BatchTaskHandler slowHandler() {
            return new BatchTaskHandler() {
                private volatile long sleepMs = 30L;
                @Override public String type() { return TYPE_SLOW; }
                @Override public List<TaskItemSeed> plan(String paramsJson) {
                    int n = 30;
                    if (paramsJson != null && paramsJson.contains("\"count\"")) {
                        // 简单解析，测试用
                        java.util.regex.Matcher m = java.util.regex.Pattern
                                .compile("\"count\"\\s*:\\s*(\\d+)").matcher(paramsJson);
                        if (m.find()) { n = Integer.parseInt(m.group(1)); }
                    }
                    List<TaskItemSeed> seeds = new ArrayList<>();
                    for (int i = 1; i <= n; i++) {
                        seeds.add(new TaskItemSeed("SLOW-" + i, (long) i));
                    }
                    return seeds;
                }
                @Override public ItemResult process(BatchTask task, BatchTaskItem item) {
                    try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    return ItemResult.success();
                }
            };
        }

        @Bean
        BatchTaskHandler flakyHandler() {
            return new BatchTaskHandler() {
                // ITEM-3 前两次处理抛错，第三次起成功（验证 attempt 与断点重跑）
                final AtomicInteger fail3 = new AtomicInteger(2);
                @Override public String type() { return TYPE_FLAKY; }
                @Override public List<TaskItemSeed> plan(String paramsJson) {
                    List<TaskItemSeed> seeds = new ArrayList<>();
                    for (int i = 1; i <= 5; i++) {
                        seeds.add(new TaskItemSeed("ITEM-" + i, (long) (100 + i)));
                    }
                    return seeds;
                }
                @Override public ItemResult process(BatchTask task, BatchTaskItem item) {
                    if ("ITEM-3".equals(item.getItemRef()) && fail3.getAndDecrement() > 0) {
                        throw new IllegalStateException("模拟第3项暂时失败");
                    }
                    return ItemResult.success();
                }
            };
        }
    }

    @Resource
    private MockMvc mockMvc;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private com.evops.task.BatchTaskService batchTaskService;

    private long seq = 9000;

    @BeforeEach
    void cleanTasks() {
        jdbcTemplate.update("DELETE FROM t_batch_task_item");
        jdbcTemplate.update("DELETE FROM t_batch_task");
    }

    // ---------------- 异步 + 5% 进度 + 跑完 ----------------

    @Test
    void slow_task_runs_async_with_progress_buckets_and_succeeds() throws Exception {
        // 提交接口立即返回（任务未必跑完），证明不堵请求线程
        String taskNo = createTask(TYPE_SLOW, "{\"count\":40}");
        JsonNode task = getTask(taskNo);
        assertTrue(task.get("totalCount").asInt() == 40);
        int seenFiveMultipleProgress = 0;

        String status;
        long deadline = System.currentTimeMillis() + 15000;
        do {
            Thread.sleep(20);
            task = getTask(taskNo);
            status = task.get("status").asText();
            int pct = task.get("progressPct").asInt();
            assertEquals(0, pct % 5, "进度必须量化到 5% 一档");
            if (pct > 0 && pct < 100) {
                seenFiveMultipleProgress++;
            }
        } while (!TERMINAL_OK.contains(status) && System.currentTimeMillis() < deadline);

        assertEquals("SUCCESS", status);
        assertEquals(100, task.get("progressPct").asInt());
        assertEquals(40, task.get("successCount").asInt());
        assertEquals(0, task.get("failCount").asInt());
        assertTrue(seenFiveMultipleProgress >= 1, "40 项任务执行中应观测到非0/非100的中间进度档");
    }

    // ---------------- 取消：当前项收尾，已处理项保留 ----------------

    @Test
    void cancel_stops_after_current_item_and_progress_is_bucketed() throws Exception {
        String taskNo = createTask(TYPE_SLOW, "{\"count\":30}");
        Thread.sleep(120); // 等它跑进执行中
        cancelTask(taskNo);

        JsonNode task = waitUntil(taskNo, java.util.Arrays.asList("CANCELLED"), 15000);
        int processed = task.get("processedCount").asInt();
        assertTrue(processed > 0 && processed < 30, "取消时应已处理一部分但未全部完成，实际=" + processed);
        assertEquals(0, task.get("progressPct").asInt() % 5);

        // 取消后可断点重跑：剩余项继续，最终全部成功
        rerunTask(taskNo);
        task = waitUntil(taskNo, java.util.Arrays.asList("SUCCESS"), 15000);
        assertEquals(100, task.get("progressPct").asInt());
        assertEquals(30, task.get("successCount").asInt());

        Integer pending = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_batch_task_item WHERE task_id=? AND item_status='PENDING'",
                Integer.class, taskIdOf(taskNo));
        assertEquals(0, pending);
    }

    // ---------------- 失败断点重跑 ----------------

    @Test
    void failed_item_retries_on_rerun_and_eventually_succeeds() throws Exception {
        String taskNo = createTask(TYPE_FLAKY, null);
        JsonNode task = waitUntil(taskNo, java.util.Arrays.asList("FAILED"), 15000);
        assertEquals(4, task.get("successCount").asInt());
        assertEquals(1, task.get("failCount").asInt());

        long taskId = taskIdOf(taskNo);
        Integer attemptFirst = jdbcTemplate.queryForObject(
                "SELECT attempt FROM t_batch_task_item WHERE task_id=? AND item_ref='ITEM-3'",
                Integer.class, taskId);
        assertEquals(1, attemptFirst, "首次运行 ITEM-3 应尝试 1 次");

        // 重跑：只重试失败项；第二次仍失败（fail3 初值2：第1、2次失败），任务再次 FAILED
        rerunTask(taskNo);
        task = waitUntil(taskNo, java.util.Arrays.asList("FAILED"), 15000);
        Integer attemptSecond = jdbcTemplate.queryForObject(
                "SELECT attempt FROM t_batch_task_item WHERE task_id=? AND item_ref='ITEM-3'",
                Integer.class, taskId);
        assertEquals(2, attemptSecond);

        // 第三次成功（已成功的 4 项不重复处理，attempt 不变）
        rerunTask(taskNo);
        task = waitUntil(taskNo, java.util.Arrays.asList("SUCCESS"), 15000);
        assertEquals(5, task.get("successCount").asInt());
        assertEquals(0, task.get("failCount").asInt());
    }

    // ---------------- 终态超保留期归档（不物理删除） ----------------

    @Test
    void expired_finished_task_is_archived_not_deleted() throws Exception {
        String taskNo = createTask(TYPE_SLOW, "{\"count\":1}");
        waitUntil(taskNo, java.util.Arrays.asList("SUCCESS"), 15000);

        long taskId = taskIdOf(taskNo);
        // 把进入终态时间回拨到 22 天前（保留期默认 21 天），触发定时清理后必须为 ARCHIVED 且行仍在
        jdbcTemplate.update("UPDATE t_batch_task SET status_set_time=? WHERE id=?",
                LocalDateTime.now().minusDays(22), taskId);

        batchTaskService.archiveExpired();

        JsonNode task = getTask(taskNo);
        assertEquals("ARCHIVED", task.get("status").asText());
        assertEquals("ARCHIVED", task.get("expireStatus").asText());
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_batch_task WHERE id=? AND expire_status='ARCHIVED'", Integer.class, taskId);
        assertEquals(1, rows);
    }

    // ---------------- 辅助 ----------------

    private String createTask(String type, String params) throws Exception {
        String body = params == null
                ? "{\"taskType\":\"" + type + "\"}"
                : "{\"taskType\":\"" + type + "\",\"params\":" + params + "}";
        JsonNode resp = json(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .header("X-Operator-Id", String.valueOf(++seq)).header("X-Request-No", "T-" + seq)
                .content(body));
        return resp.get("data").get("taskNo").asText();
    }

    private void cancelTask(String taskNo) throws Exception {
        json(post("/api/tasks/" + taskNo + "/cancel")
                .header("X-Operator-Id", String.valueOf(++seq)).header("X-Request-No", "T-" + seq));
    }

    private void rerunTask(String taskNo) throws Exception {
        json(post("/api/tasks/" + taskNo + "/rerun")
                .header("X-Operator-Id", String.valueOf(++seq)).header("X-Request-No", "T-" + seq));
    }

    private JsonNode waitUntil(String taskNo, List<String> anyStatus, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        JsonNode task;
        do {
            task = getTask(taskNo);
            if (anyStatus.contains(task.get("status").asText())) {
                return task;
            }
            Thread.sleep(20);
        } while (System.currentTimeMillis() < deadline);
        throw new AssertionError("任务[" + taskNo + "]未在 " + timeoutMs + "ms 内到达 " + anyStatus
                + "，当前=" + task.get("status").asText());
    }

    private long taskIdOf(String taskNo) throws Exception {
        return getTask(taskNo).get("id").asLong();
    }

    private JsonNode getTask(String taskNo) throws Exception {
        return json(get("/api/tasks/" + taskNo));
    }

    private JsonNode json(MockHttpServletRequestBuilder req) throws Exception {
        String resp = mockMvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(resp);
        if (!node.get("success").asBoolean()) {
            throw new AssertionError("接口失败: " + resp);
        }
        return node;
    }
}
