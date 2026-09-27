package com.evops;

import com.evops.sensitive.SensitiveWordManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.junit.jupiter.api.BeforeAll;
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

import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 加工单文本敏感词管控集成测试：
 * 归一化命中（全角/分隔符插空）、命中位置返回、分级处置（拦截/打码）、
 * 词库热更新、以加工单为准的反向核对。
 * 词库为落盘文件（application-test.yml 指定），本类 @BeforeAll 写入、用例结束复原。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SensitiveWordIntegrationTest.TestShiroBypass.class)
class SensitiveWordIntegrationTest {

    @TestConfiguration
    static class TestShiroBypass {
        @Bean
        ShiroFilterChainDefinition shiroFilterChainDefinition() {
            DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
            chain.addPathDefinition("/**", "anon");
            return chain;
        }
    }

    private static final Path DICT = Paths.get("./target/test-dict/sensitive-words.txt");
    private static final String DICT_CONTENT =
            "# 测试词库\n"
                    + "测试违禁词|BLOCK\n"
                    + "测试打码词|MASK\n"
                    + "vip|MASK\n";

    @Resource
    private MockMvc mockMvc;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;
    @Resource
    private SensitiveWordManager wordManager;

    private long operatorSeq = 2000;

    @BeforeAll
    static void writeDict() throws IOException {
        Files.createDirectories(DICT.getParent());
        Files.write(DICT, DICT_CONTENT.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void reloadDict() {
        // 每个用例都从磁盘重载，热更新用例改过的词库在此复原
        writeDictQuietly();
        wordManager.reload();
    }

    private static void writeDictQuietly() {
        writeDictQuietly(DICT_CONTENT);
    }

    private static void writeDictQuietly(String content) {
        try {
            Files.write(DICT, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---------------- 试扫：归一化命中与位置返回 ----------------

    @Test
    void scan_marks_original_positions_with_separator_evasion() throws Exception {
        // 分隔符插空也能命中；返回位置为原文坐标，供前端标记
        JsonNode data = scan("客户备注测-试-违-禁-词插空");
        assertTrue(data.get("blocked").asBoolean());
        assertEquals("客户备注*********插空", data.get("maskedText").asText());
        JsonNode hits = data.get("hits");
        assertEquals(1, hits.size());
        assertEquals(4, hits.get(0).get("start").asInt());
        assertEquals(13, hits.get(0).get("end").asInt());
        assertEquals("BLOCK", hits.get(0).get("level").asText());
        assertEquals("测-试-违-禁-词", hits.get(0).get("word").asText());
    }

    @Test
    void scan_full_width_and_case_folded() throws Exception {
        // 全角 ＶＩＰ 归一为 vip 命中
        JsonNode data = scan("ＶＩＰ客户");
        assertTrue(!data.get("blocked").asBoolean());
        assertEquals("***客户", data.get("maskedText").asText());
        JsonNode hits = data.get("hits");
        assertEquals(1, hits.size());
        assertEquals(0, hits.get(0).get("start").asInt());
        assertEquals(3, hits.get(0).get("end").asInt());
        assertEquals("MASK", hits.get(0).get("level").asText());
    }

    // ---------------- 分级处置：打码 / 拦截 ----------------

    @Test
    void mask_word_masked_on_create_and_logged() throws Exception {
        long lensId = createLens("L-SENS-M", "S10");
        JsonNode order = createOrder("O-SENS-MASK", lensId, "S11", "张三", "镜片测试打码词备注");
        long orderId = order.get("data").get("id").asLong();

        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.remark").value("镜片*****备注"));

        String action = jdbcTemplate.queryForObject(
                "SELECT action FROM t_sensitive_scan_log WHERE order_no='O-SENS-MASK' AND field_name='remark'",
                String.class);
        assertEquals("MASK", action);
        Integer nameHits = jdbcTemplate.queryForObject(
                "SELECT hit_count FROM t_sensitive_scan_log WHERE order_no='O-SENS-MASK' AND field_name='customerName'",
                Integer.class);
        assertEquals(0, nameHits);
        String dictVersion = jdbcTemplate.queryForObject(
                "SELECT dict_version FROM t_sensitive_scan_log WHERE order_no='O-SENS-MASK' AND field_name='remark'",
                String.class);
        assertNotEquals(SensitiveWordManager.EMPTY_VERSION, dictVersion);
    }

    @Test
    void block_word_rejected_on_create_and_attempt_logged() throws Exception {
        long lensId = createLens("L-SENS-B", "S20");
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "2001").header("X-Request-No", "S21")
                        .content(orderBody("O-SENS-BLOCK", lensId, "张三测试违禁词", null)))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("SENSITIVE_WORD_BLOCKED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("customerName")));

        // 整单不落库
        Integer orders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_process_order WHERE order_no='O-SENS-BLOCK'", Integer.class);
        assertEquals(0, orders);
        // 拦截尝试仍留痕（独立事务）
        Integer blockedLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_sensitive_scan_log WHERE order_no='O-SENS-BLOCK' AND action='BLOCK'",
                Integer.class);
        assertEquals(1, blockedLogs);
    }

    @Test
    void update_path_masks_and_blocks() throws Exception {
        long lensId = createLens("L-SENS-U", "S30");
        JsonNode order = createOrder("O-SENS-UPD", lensId, "S31", "李四", "普通备注");
        long orderId = order.get("data").get("id").asLong();
        long version = order.get("data").get("versionNo").asLong();

        // MASK：修改后落库为打码文本
        mockMvc.perform(put("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "2002").header("X-Request-No", "S32")
                        .content(updateBody(orderId, version, "备注测试打码词")))
                .andExpect(jsonPath("$.code").value("OK"));
        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(jsonPath("$.data.order.remark").value("备注*****"));

        // BLOCK：整单拒绝，库中文本不变
        mockMvc.perform(put("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "2003").header("X-Request-No", "S33")
                        .content(updateBody(orderId, version + 1, "测试违禁词")))
                .andExpect(jsonPath("$.code").value("SENSITIVE_WORD_BLOCKED"));
        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(jsonPath("$.data.order.remark").value("备注*****"));
    }

    // ---------------- 词库热更新 ----------------

    @Test
    void dict_hot_reload_via_endpoint() throws Exception {
        String versionBefore = wordManager.dictVersion();
        assertEquals(0, scan("测试新词").get("hits").size());

        try {
            // 加词：reload 后立即生效，版本变化
            writeDictQuietly(DICT_CONTENT + "测试新词|MASK\n");
            JsonNode reloaded = performJson(post("/api/sensitive/words/reload")
                    .header("X-Operator-Id", "2004").header("X-Request-No", "S41"));
            assertEquals(4, reloaded.get("data").get("wordCount").asInt());
            assertNotEquals(versionBefore, reloaded.get("data").get("dictVersion").asText());
            assertEquals(1, scan("测试新词").get("hits").size());

            // 减词：reload 后不再命中
            writeDictQuietly("测试违禁词|BLOCK\nvip|MASK\n");
            performJson(post("/api/sensitive/words/reload")
                    .header("X-Operator-Id", "2005").header("X-Request-No", "S42"));
            assertEquals(0, scan("测试打码词").get("hits").size());
        } finally {
            writeDictQuietly(DICT_CONTENT);
            wordManager.reload();
        }
    }

    // ---------------- 反向核对：以加工单为准 ----------------

    @Test
    void recheck_finds_unscanned_orders_and_dict_drift() throws Exception {
        long lensId = createLens("L-SENS-R", "S50");
        // A：走接口建档，文本当前干净（PASS 留痕）
        createOrder("O-SENS-DRIFT", lensId, "S51", "张三核对甲", null);
        // B：直插库（模拟历史数据），无扫描留痕
        jdbcTemplate.update(
                "INSERT INTO t_process_order (order_no, customer_name, store_code, lens_id, order_status, request_no)"
                        + " VALUES ('O-SENS-RAW', '直插客户', 'S01', " + lensId + ", 'CREATED', 'RAW-S52')");

        // 第一遍核对：B 未覆盖；A 无差异
        JsonNode first = recheck();
        List<String> neverScanned = toTextList(first.get("data").get("neverScanned"));
        assertTrue(neverScanned.contains("O-SENS-RAW"), "直插无留痕的加工单应列入 neverScanned");
        assertTrue(!driftOrderNos(first).contains("O-SENS-DRIFT"), "文本干净时不应报差异");

        // 词库变严：新词命中 A 的存量文本 → 第二遍核对报处置加重
        try {
            writeDictQuietly(DICT_CONTENT + "核对甲|MASK\n");
            wordManager.reload();

            JsonNode second = recheck();
            JsonNode drift = driftOf(second, "O-SENS-DRIFT", "customerName");
            assertTrue(drift != null, "词库变严后 A 的存量文本应报 drift");
            assertEquals("PASS", drift.get("recordedAction").asText());
            assertEquals("MASK", drift.get("currentAction").asText());
            assertEquals(1, drift.get("currentHits").asInt());

            // 核对留痕：每单每字段写 RECHECK 快照
            Integer recheckLogs = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM t_sensitive_scan_log WHERE order_no='O-SENS-DRIFT' AND oper_type='RECHECK'",
                    Integer.class);
            assertTrue(recheckLogs >= 2, "每次核对应按字段写 RECHECK 留痕");
        } finally {
            writeDictQuietly(DICT_CONTENT);
            wordManager.reload();
        }
    }

    // ---------------- 辅助方法 ----------------

    private JsonNode scan(String text) throws Exception {
        JsonNode resp = performJson(post("/api/sensitive/scan")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Operator-Id", String.valueOf(++operatorSeq))
                .header("X-Request-No", "S-SCAN-" + operatorSeq)
                .content("{\"text\":\"" + text + "\"}"));
        assertEquals("OK", resp.get("code").asText());
        return resp.get("data");
    }

    private JsonNode recheck() throws Exception {
        JsonNode resp = performJson(post("/api/sensitive/recheck")
                .header("X-Operator-Id", String.valueOf(++operatorSeq))
                .header("X-Request-No", "S-RECHECK-" + operatorSeq));
        assertEquals("OK", resp.get("code").asText());
        return resp;
    }

    private List<String> driftOrderNos(JsonNode recheckResp) {
        List<String> orderNos = new ArrayList<>();
        for (JsonNode drift : recheckResp.get("data").get("changed")) {
            orderNos.add(drift.get("orderNo").asText());
        }
        return orderNos;
    }

    private JsonNode driftOf(JsonNode recheckResp, String orderNo, String field) {
        for (JsonNode drift : recheckResp.get("data").get("changed")) {
            if (orderNo.equals(drift.get("orderNo").asText()) && field.equals(drift.get("field").asText())) {
                return drift;
            }
        }
        return null;
    }

    private List<String> toTextList(JsonNode array) {
        List<String> list = new ArrayList<>();
        array.forEach(node -> list.add(node.asText()));
        return list;
    }

    private long createLens(String code, String reqNo) throws Exception {
        String body = "{\"lensCode\":\"" + code + "\",\"lensName\":\"镜片" + code + "\","
                + "\"lensType\":\"单光\",\"price\":399.00}";
        JsonNode node = performJson(post("/api/lens")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Operator-Id", String.valueOf(++operatorSeq))
                .header("X-Request-No", reqNo)
                .content(body));
        return node.get("data").get("id").asLong();
    }

    private JsonNode createOrder(String orderNo, long lensId, String reqNo,
                                 String customerName, String remark) throws Exception {
        return performJson(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Operator-Id", String.valueOf(++operatorSeq))
                .header("X-Request-No", reqNo)
                .content(orderBody(orderNo, lensId, customerName, remark)));
    }

    private String orderBody(String orderNo, long lensId, String customerName, String remark) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"orderNo\":\"").append(orderNo).append("\",\"lensId\":").append(lensId)
                .append(",\"customerName\":\"").append(customerName).append("\",\"storeCode\":\"S01\",");
        if (remark != null) {
            sb.append("\"remark\":\"").append(remark).append("\",");
        }
        sb.append("\"prescriptions\":[")
                .append("{\"eye\":\"L\",\"sphere\":-1.00},")
                .append("{\"eye\":\"R\",\"sphere\":-1.00}]}");
        return sb.toString();
    }

    private String updateBody(long orderId, long versionNo, String remark) {
        return "{\"id\":" + orderId + ",\"versionNo\":" + versionNo + ",\"remark\":\"" + remark + "\","
                + "\"prescriptions\":["
                + "{\"eye\":\"L\",\"sphere\":-1.00},"
                + "{\"eye\":\"R\",\"sphere\":-1.00}]}";
    }

    private JsonNode performJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        String resp = mockMvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp);
    }
}
