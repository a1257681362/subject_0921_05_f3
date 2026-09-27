package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import javax.annotation.Resource;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验光与加工底账关键路径集成测试：
 * 建档、度数档位、取镜流转、冲销恢复、重复提交、禁物理删除、主子表原子性。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OpticianIntegrationTest.TestShiroBypass.class)
class OpticianIntegrationTest {

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
    private org.springframework.test.web.servlet.MockMvc mockMvc;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private JdbcTemplate jdbcTemplate;

    private long operatorSeq = 1000;

    // ---------------- 镜片 ----------------

    @Test
    void lens_create_discard_restore_and_logs() throws Exception {
        long lensId = createLens("L-DR-1", "R1");
        assertEquals("ACTIVE", lensStatus(lensId));

        mockMvc.perform(post("/api/lens/" + lensId + "/discard")
                        .header("X-Operator-Id", "1001").header("X-Request-No", "R2")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"remark\":\"停售\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"));
        assertEquals("DISCARDED", lensStatus(lensId));

        // 重复作废
        mockMvc.perform(post("/api/lens/" + lensId + "/discard")
                        .header("X-Operator-Id", "1001").header("X-Request-No", "R3"))
                .andExpect(jsonPath("$.code").value("LENS_ALREADY_DISCARDED"));

        // 恢复
        mockMvc.perform(post("/api/lens/" + lensId + "/restore")
                        .header("X-Operator-Id", "1001").header("X-Request-No", "R4"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("OK"));
        assertEquals("ACTIVE", lensStatus(lensId));

        Integer logs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_lens_oper_log WHERE lens_id=? AND oper_type IN ('CREATE','DISCARD','RESTORE')",
                Integer.class, lensId);
        assertEquals(3, logs);
    }

    @Test
    void lens_duplicate_code_rejected() throws Exception {
        createLens("L-DUP-1", "R10");
        String body = "{\"lensCode\":\"L-DUP-1\",\"lensName\":\"重复编码片\"}";
        mockMvc.perform(post("/api/lens").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "1002").header("X-Request-No", "R11")
                        .content(body))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("LENS_CODE_DUPLICATED"));
    }

    @Test
    void discarded_lens_cannot_open_order() throws Exception {
        long lensId = createLens("L-DEAD-1", "R20");
        mockMvc.perform(post("/api/lens/" + lensId + "/discard")
                .header("X-Operator-Id", "1003").header("X-Request-No", "R21")).andExpect(status().isOk());

        String body = orderBody("O-DEAD-1", lensId, "-2.00", "-1.75");
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "1003").header("X-Request-No", "R22")
                        .content(body))
                .andExpect(jsonPath("$.code").value("LENS_NOT_ACTIVE"));
    }

    // ---------------- 加工单主流程 ----------------

    @Test
    void order_full_pickup_flow_and_cancel_restore() throws Exception {
        long lensId = createLens("L-FLOW-1", "R30");
        long orderId = createOrder("O-FLOW-1", lensId, "R31", "-3.25", "-2.50");
        assertEquals("CREATED", orderStatus(orderId));

        act(orderId, "start", "R32", "PROCESSING");
        act(orderId, "finish", "R33", "READY");
        act(orderId, "pickup", "R34", "PICKED_UP");

        // 已取镜不可冲销
        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .header("X-Operator-Id", "1004").header("X-Request-No", "R35"))
                .andExpect(jsonPath("$.code").value("ORDER_PICKED_UP"));
    }

    @Test
    void order_cancel_then_restore_keeps_status_logs() throws Exception {
        long lensId = createLens("L-CAN-1", "R40");
        long orderId = createOrder("O-CAN-1", lensId, "R41", "-1.00", "-0.50");

        act(orderId, "cancel", "R42", "CANCELLED");
        // 重复冲销
        mockMvc.perform(post("/api/orders/" + orderId + "/cancel")
                        .header("X-Operator-Id", "1005").header("X-Request-No", "R43"))
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_CANCELLED"));
        act(orderId, "restore", "R44", "CREATED");

        mockMvc.perform(get("/api/orders/by-no/O-CAN-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.order.orderStatus").value("CREATED"))
                .andExpect(jsonPath("$.data.prescriptions.length()").value(2))
                .andExpect(jsonPath("$.data.statusLogs[*].operType").value(
                        org.hamcrest.Matchers.hasItems("CREATE", "CANCEL", "RESTORE")));
    }

    @Test
    void order_illegal_transition_rejected() throws Exception {
        long lensId = createLens("L-ILL-1", "R50");
        long orderId = createOrder("O-ILL-1", lensId, "R51", "-1.00", "-1.00");

        // 已建档不能直接完成加工
        mockMvc.perform(post("/api/orders/" + orderId + "/finish")
                        .header("X-Operator-Id", "1006").header("X-Request-No", "R52"))
                .andExpect(jsonPath("$.code").value("ORDER_STATUS_ILLEGAL"));
        assertEquals("CREATED", orderStatus(orderId));
    }

    // ---------------- 度数校验 ----------------

    @Test
    void degree_must_be_quarter_step() throws Exception {
        long lensId = createLens("L-STEP-1", "R60");
        String body = orderBody("O-STEP-BAD", lensId, "-3.30", "-2.50");
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "1007").header("X-Request-No", "R61")
                        .content(body))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("RX_DEGREE_STEP"));

        // 合法档位（含 0.25、0）可通过
        long ok = createOrderRaw("O-STEP-OK", lensId, "R62", "0.00", "-0.25");
        assertEquals("CREATED", orderStatus(ok));
    }

    @Test
    void prescriptions_must_have_both_eyes() throws Exception {
        long lensId = createLens("L-EYE-1", "R70");
        String body = "{\"orderNo\":\"O-EYE-BAD\",\"lensId\":" + lensId + ","
                + "\"prescriptions\":[{\"eye\":\"L\",\"sphere\":-1.00}]}";
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "1008").header("X-Request-No", "R71")
                        .content(body))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---------------- 重复提交 ----------------

    @Test
    void duplicate_request_no_blocked_by_db_constraint() throws Exception {
        long lensId = createLens("L-IDEM-1", "R80");
        String body1 = orderBody("O-IDEM-1", lensId, "-1.00", "-1.00");
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "1009").header("X-Request-No", "IDEM-R1")
                        .content(body1))
                .andExpect(jsonPath("$.code").value("OK"));

        String body2 = orderBody("O-IDEM-2", lensId, "-1.00", "-1.00");
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "1009").header("X-Request-No", "IDEM-R1")
                        .content(body2))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("REQUEST_DUPLICATED"));
    }

    @Test
    void duplicate_order_no_rejected() throws Exception {
        long lensId = createLens("L-NODUP-1", "R85");
        createOrderRaw("O-NODUP-1", lensId, "R86", "-1.00", "-1.00");
        String body = orderBody("O-NODUP-1", lensId, "-2.00", "-2.00");
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "1010").header("X-Request-No", "R87")
                        .content(body))
                .andExpect(jsonPath("$.code").value("ORDER_NO_DUPLICATED"));
    }

    // ---------------- 原子性与禁物理删除 ----------------

    @Test
    void order_create_is_atomic_when_rx_invalid() throws Exception {
        long lensId = createLens("L-TX-1", "R90");
        Long before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_process_order", Long.class);

        // 左眼别写错（X），主表插入后处方阶段失败，整体回滚
        String body = "{\"orderNo\":\"O-TX-1\",\"lensId\":" + lensId + ","
                + "\"prescriptions\":[{\"eye\":\"X\",\"sphere\":-1.00},"
                + "{\"eye\":\"R\",\"sphere\":-1.00}]}";
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "1011").header("X-Request-No", "R91")
                        .content(body))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        Long after = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_process_order", Long.class);
        assertEquals(before, after, "主子表必须同生死：处方失败主表不应留下");
    }

    @Test
    void updating_prescriptions_voids_old_rows_never_deletes() throws Exception {
        long lensId = createLens("L-VOID-1", "R95");
        JsonNode order = createOrderNode("O-VOID-1", lensId, "R96", "-1.00", "-1.00");
        long orderId = order.get("data").get("id").asLong();
        long version = order.get("data").get("versionNo").asLong();

        Long rowsAfterCreate = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_process_order_rx WHERE order_id=?", Long.class, orderId);
        assertEquals(2L, rowsAfterCreate);

        String updateBody = "{\"id\":" + orderId + ",\"versionNo\":" + version + ","
                + "\"prescriptions\":["
                + "{\"eye\":\"L\",\"sphere\":-1.50,\"cylinder\":-0.50,\"axis\":90},"
                + "{\"eye\":\"R\",\"sphere\":-1.25}]}";
        mockMvc.perform(put("/api/orders").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "1012").header("X-Request-No", "R97")
                        .content(updateBody))
                .andExpect(jsonPath("$.code").value("OK"));

        Long totalRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_process_order_rx WHERE order_id=?", Long.class, orderId);
        Long voidRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_process_order_rx WHERE order_id=? AND record_status='VOID'",
                Long.class, orderId);
        Long activeRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_process_order_rx WHERE order_id=? AND record_status='ACTIVE'",
                Long.class, orderId);
        assertEquals(4L, totalRows, "旧处方行必须保留，禁止物理删除");
        assertEquals(2L, voidRows);
        assertEquals(2L, activeRows);

        // 版本号递增、最新球镜生效
        mockMvc.perform(get("/api/orders/" + orderId))
                .andExpect(jsonPath("$.data.order.versionNo").value(version + 1))
                .andExpect(jsonPath("$.data.prescriptions[0].sphere").value(
                        org.hamcrest.Matchers.closeTo(-1.50, 0.0001)));
    }

    @Test
    void stale_version_update_rejected() throws Exception {
        long lensId = createLens("L-OPT-1", "R100");
        JsonNode order = createOrderNode("O-OPT-1", lensId, "R101", "-1.00", "-1.00");
        long orderId = order.get("data").get("id").asLong();
        long version = order.get("data").get("versionNo").asLong();

        // 先推进一次版本
        act(orderId, "start", "R102", "PROCESSING");
        // CANCELLED? cancel from PROCESSING allowed; but for stale test use restore path instead:
        // start 之后直接用旧版本号 finish 应成功（动作不校验版本除非传入）；
        // 这里显式传旧版本号校验冲突
        mockMvc.perform(post("/api/orders/" + orderId + "/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator-Id", "1013").header("X-Request-No", "R103")
                        .content("{\"versionNo\":" + version + "}"))
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertTrue(true);
    }

    // ---------------- 辅助方法 ----------------

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

    private long createOrder(String orderNo, long lensId, String reqNo, String lSphere, String rSphere)
            throws Exception {
        return createOrderNode(orderNo, lensId, reqNo, lSphere, rSphere)
                .get("data").get("id").asLong();
    }

    private long createOrderRaw(String orderNo, long lensId, String reqNo, String lSphere, String rSphere)
            throws Exception {
        return createOrder(orderNo, lensId, reqNo, lSphere, rSphere);
    }

    private JsonNode createOrderNode(String orderNo, long lensId, String reqNo,
                                     String lSphere, String rSphere) throws Exception {
        return performJson(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Operator-Id", String.valueOf(++operatorSeq))
                .header("X-Request-No", reqNo)
                .content(orderBody(orderNo, lensId, lSphere, rSphere)));
    }

    private String orderBody(String orderNo, long lensId, String lSphere, String rSphere) {
        return "{\"orderNo\":\"" + orderNo + "\",\"lensId\":" + lensId
                + ",\"customerName\":\"张三\",\"storeCode\":\"S01\","
                + "\"prescriptions\":["
                + "{\"eye\":\"L\",\"sphere\":" + lSphere + "},"
                + "{\"eye\":\"R\",\"sphere\":" + rSphere + "}]}";
    }

    private void act(long orderId, String action, String reqNo, String expectedStatus) throws Exception {
        mockMvc.perform(post("/api/orders/" + orderId + "/" + action)
                        .header("X-Operator-Id", String.valueOf(++operatorSeq))
                        .header("X-Request-No", reqNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.orderStatus").value(expectedStatus));
    }

    private JsonNode performJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        String resp = mockMvc.perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp);
    }

    private String lensStatus(long id) {
        return jdbcTemplate.queryForObject("SELECT lens_status FROM t_lens WHERE id=?", String.class, id);
    }

    private String orderStatus(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT order_status FROM t_process_order WHERE id=?", String.class, id);
    }
}
