package com.evops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 修复材料退料幂等与事务边界验收（museum-3 加固）：
 * 把退料单置于高风险场景（仓管重复点击、接口异常重放、并发重试风暴）下观察
 * 「修复材料退料重复冲销」——任何情况下已生效退料不得重复加回库存，
 * 相关库存流水（t_relic_ledger 的 MATERIAL_RETURN）同步受限：一单一条。
 *
 * <ol>
 * <li>重复点击：同一退料单号重发多次，回放同一退料单，库存只加回一次、
 *     台账只写一条，原始操作人与请求摘要保持首次提交的事实；</li>
 * <li>异常重放：台账写入异常导致整单回滚（库存不加回、不留半成品），
 *     仓管用同一单号重试可安全成功且只生效一次；</li>
 * <li>并发重试：8 并发同一退料单号全部命中同一条退料单；8 并发不同单号
 *     退同一明细行，行锁串行化后累计退料不得超过实际用量。</li>
 * </ol>
 * 使用独立内存库，不调用起步接口。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:museum-return-idem;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MaterialReturnIdempotencyTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    /** 间谍台账服务：仅用于异常重放用例注入台账写入失败，验证事务完整回滚 */
    @SpyBean
    private LedgerService ledgerService;

    @BeforeEach
    void resetSpy() {
        // 每个用例开始前清除台账间谍的异常桩，恢复真实写入
        Mockito.reset(ledgerService);
    }

    // ------------------------------------------------------------------
    // 1) 重复点击：同一退料单号重发，回放原单，库存/台账只生效一次
    // ------------------------------------------------------------------

    @Test
    void duplicate_click_same_return_no_replays_without_double_credit() throws Exception {
        Fixture f = newFixture("A", "20", "20");

        // 首次退料 5g：库存 80 -> 85
        String body = returnJson("MR-DUP-A", f.usageId, f.lineId, "5", "随色余量退回", "仓管员甲");
        JsonNode first = dataOf(postOk("/api/material-returns", body));
        long returnId = first.get("id").asLong();
        assertEquals(85.0, stockOf(f.materialId, f.batchNo), 1e-3);

        // 重复点击：同一报文重发两次，都回放同一条退料单
        assertEquals(returnId, dataOf(postOk("/api/material-returns", body)).get("id").asLong());
        assertEquals(returnId, dataOf(postOk("/api/material-returns", body)).get("id").asLong());
        // 接口重试换了提交人：仍回放原单，原始操作人不被覆盖
        JsonNode replayed = dataOf(postOk("/api/material-returns",
                returnJson("MR-DUP-A", f.usageId, f.lineId, "5", "随色余量退回", "仓管员乙")));
        assertEquals(returnId, replayed.get("id").asLong());
        assertEquals("仓管员甲", replayed.get("returnedBy").asText(), "重放必须保留原始操作人");

        // 只形成一条退料记录、库存只加回一次
        JsonNode detail = dataOf(getJson("/api/material-usages/" + f.usageId));
        assertEquals(1, detail.get("returns").size(), "同一退料单号只能生效一次");
        JsonNode stored = detail.get("returns").get(0);
        assertEquals("仓管员甲", stored.get("returnedBy").asText(), "原始操作人必须保留");
        assertEquals("随色余量退回", stored.get("reason").asText(), "原请求摘要必须保留");
        assertEquals(85.0, stockOf(f.materialId, f.batchNo), 1e-3, "重复点击不得重复加回库存");

        // 库存流水同步受限：同一退料单只写一条 MATERIAL_RETURN 台账，摘要保留首次事实
        List<JsonNode> returnLedgers = returnLedgers(f.relicId);
        assertEquals(1, returnLedgers.size(), "同一退料单号只能写一条库存流水");
        assertTrue(returnLedgers.get(0).get("summary").asText().contains("退回 5"),
                "台账摘要必须保留首次提交的退料事实");
        assertTrue(returnLedgers.get(0).get("summary").asText().contains("修复师甲"));

        // 单号挪用：同单号不同数量，拒绝重复生效且无副作用
        expectFail(postJson("/api/material-returns",
                returnJson("MR-DUP-A", f.usageId, f.lineId, "7", "挪用单号", "仓管员乙")),
                "不能重复生效");
        assertEquals(1, dataOf(getJson("/api/material-usages/" + f.usageId)).get("returns").size());
        assertEquals(85.0, stockOf(f.materialId, f.batchNo), 1e-3);
        assertEquals(1, returnLedgers(f.relicId).size());

        // 对账一致：库存与流水始终对平
        assertTrue(reconcile(f.materialId, f.batchNo).get("consistent").asBoolean());
    }

    // ------------------------------------------------------------------
    // 2) 异常重放：事务内任一步失败整体回滚，同一单号重试安全成功
    // ------------------------------------------------------------------

    @Test
    void exception_replay_rolls_back_then_same_no_retries_safely() throws Exception {
        Fixture f = newFixture("B", "20", "20");

        // 注入台账写入异常：退料单已插入、库存已加回之后事务失败，必须整体回滚
        doThrow(new IllegalStateException("模拟台账写入异常"))
                .when(ledgerService)
                .append(any(), any(), any(), eq("MATERIAL_RETURN"), any(), any(), any());
        expectFail(postJson("/api/material-returns",
                returnJson("MR-FAIL-B", f.usageId, f.lineId, "4", "接口重试前失败", "仓管员甲")),
                "系统处理失败");

        // 完整回滚：库存不加回、不留退料半成品、不写台账、领用单状态不变
        assertEquals(80.0, stockOf(f.materialId, f.batchNo), 1e-3,
                "失败的退料必须完整回滚，库存不得加回");
        assertEquals(0, dataOf(getJson("/api/material-usages/" + f.usageId)).get("returns").size(),
                "失败的退料不能留下半成品记录");
        assertEquals(0, returnLedgers(f.relicId).size(), "失败的退料不能留下库存流水");
        assertEquals("ISSUED", dataOf(getJson("/api/material-usages/" + f.usageId))
                .get("usage").get("status").asText());

        // 仓管用同一退料单号重试：异常重放安全成功，只生效一次
        Mockito.reset(ledgerService);
        String retryBody = returnJson("MR-FAIL-B", f.usageId, f.lineId, "4", "接口重试前失败", "仓管员甲");
        long returnId = dataOf(postOk("/api/material-returns", retryBody)).get("id").asLong();
        assertEquals(84.0, stockOf(f.materialId, f.batchNo), 1e-3);
        // 重试成功后再重发：回放同一条，不二次冲销
        assertEquals(returnId, dataOf(postOk("/api/material-returns", retryBody)).get("id").asLong());

        assertEquals(1, dataOf(getJson("/api/material-usages/" + f.usageId)).get("returns").size());
        assertEquals(84.0, stockOf(f.materialId, f.batchNo), 1e-3, "异常重放不得重复加回库存");
        assertEquals(1, returnLedgers(f.relicId).size(), "异常重放后库存流水仍只一条");
        assertTrue(reconcile(f.materialId, f.batchNo).get("consistent").asBoolean());
    }

    // ------------------------------------------------------------------
    // 3) 并发重试：同单号风暴命中同一条；不同单号超退被行锁挡住
    // ------------------------------------------------------------------

    @Test
    void concurrent_retries_hit_same_record_and_never_over_return() throws Exception {
        Fixture f1 = newFixture("C1", "20", "20");
        Fixture f2 = newFixture("C2", "10", "10", f1.relicId, f1.projectId, f1.materialId);

        // ---- 8 并发同一退料单号（重复点击/接口重试风暴）：全部命中同一条 ----
        List<Outcome> storm = parallel(8, i ->
                returnJson("MR-CONC-C", f1.usageId, f1.lineId, "3", "并发重试", "仓管员甲"));
        Set<Long> ids = new HashSet<>();
        for (Outcome o : storm) {
            assertTrue(o.success, "同一退料单号的并发重试都应回放成功，实际: " + o.message);
            ids.add(o.id);
        }
        assertEquals(1, ids.size(), "同一退料单号并发只能形成一条退料单");
        assertEquals(1, dataOf(getJson("/api/material-usages/" + f1.usageId)).get("returns").size());
        assertEquals(83.0, stockOf(f1.materialId, f1.batchNo), 1e-3,
                "并发重试库存只加回一次，不得重复冲销");
        assertEquals(1, returnLedgers(f1.relicId).size(), "并发重试库存流水只写一条");

        // ---- 8 并发不同退料单号退同一明细行（实际用量 10，各退 4）----
        // 行锁串行化后按最新累计量判定：恰好 2 单生效（4+4<=10），其余拒绝超退
        List<Outcome> race = parallel(8, i ->
                returnJson("MR-RACE-C" + i, f2.usageId, f2.lineId, "4", "并发分次退", "仓管员甲"));
        int ok = 0;
        for (Outcome o : race) {
            if (o.success) {
                ok++;
            } else {
                assertTrue(o.message.contains("累计退料不能超过"),
                        "失败原因应为累计退料超限，实际: " + o.message);
            }
        }
        assertEquals(2, ok, "并发分次退料不得超过该行实际用量");
        assertEquals(2, dataOf(getJson("/api/material-usages/" + f2.usageId)).get("returns").size());
        assertEquals(98.0, stockOf(f2.materialId, f2.batchNo), 1e-3,
                "库存加回必须等于实际生效的退料总量");
        assertEquals(3, returnLedgers(f1.relicId).size(), "库存流水与生效退料单一一对应");
        assertTrue(reconcile(f1.materialId, f1.batchNo).get("consistent").asBoolean());
        assertTrue(reconcile(f2.materialId, f2.batchNo).get("consistent").asBoolean());
    }

    // ------------------------------------------------------------------

    /** 线程返回：success + 退料单 id（失败为 null） */
    private static final class Outcome {
        boolean success;
        Long id;
        String message;
    }

    private static final class Fixture {
        long relicId;
        long projectId;
        long materialId;
        long usageId;
        long lineId;
        String batchNo;
    }

    /** 独立文物/项目/材料/批号建档并领用，返回退料所需的全部锚点 */
    private Fixture newFixture(String tag, String planQty, String actualQty) throws Exception {
        long relicId = createRelic("{\"relicNo\":\"M-RI-" + tag + "\",\"name\":\"退料幂等测试瓷" + tag
                + "\",\"category\":\"瓷器\",\"material\":\"瓷\",\"era\":\"清\","
                + "\"source\":\"考古移交\",\"preservationGrade\":\"TWO\"}");
        postOk("/api/relics/" + relicId + "/intake", "{\"intakeNo\":\"MI-RI-" + tag
                + "\",\"location\":\"三号库·瓷器区·R-01\","
                + "\"photos\":[{\"photoUrl\":\"photos/ri" + tag + "/a.jpg\"}]}");
        long projectId = dataOf(postOk("/api/repair-projects", "{\"projectNo\":\"MP-RI-" + tag
                + "\",\"relicId\":" + relicId
                + ",\"client\":\"藏品部\",\"plan\":\"补配随色\"}")).get("id").asLong();
        countersign(projectId);
        postEmpty("/api/repair-projects/" + projectId + "/start");
        long materialId = dataOf(postOk("/api/materials", "{\"materialNo\":\"MM-RI-" + tag
                + "\",\"name\":\"退料测试颜料" + tag + "\",\"category\":\"PIGMENT\",\"unit\":\"g\"}"))
                .get("id").asLong();
        String batchNo = "B-RI-" + tag;
        postOk("/api/materials/inbound", "{\"materialNo\":\"MM-RI-" + tag
                + "\",\"batchNo\":\"" + batchNo + "\",\"qty\":100,\"expireDate\":\"2027-12-31\","
                + "\"supplier\":\"修复材料中心\",\"inboundBy\":\"库管员\"}");
        return issueFixture(tag, relicId, projectId, materialId, batchNo, planQty, actualQty);
    }

    /** 在既有文物/项目/材料上另起批号与领用单（同一台账文物，便于汇总流水） */
    private Fixture newFixture(String tag, String planQty, String actualQty,
                               long relicId, long projectId, long materialId) throws Exception {
        String batchNo = "B-RI-" + tag;
        JsonNode material = null;
        for (JsonNode m : dataOf(getJson("/api/materials"))) {
            if (m.get("id").asLong() == materialId) {
                material = m;
            }
        }
        postOk("/api/materials/inbound", "{\"materialNo\":\"" + material.get("materialNo").asText()
                + "\",\"batchNo\":\"" + batchNo + "\",\"qty\":100,\"expireDate\":\"2027-12-31\","
                + "\"supplier\":\"修复材料中心\",\"inboundBy\":\"库管员\"}");
        return issueFixture(tag, relicId, projectId, materialId, batchNo, planQty, actualQty);
    }

    private Fixture issueFixture(String tag, long relicId, long projectId, long materialId,
                                 String batchNo, String planQty, String actualQty) throws Exception {
        Fixture f = new Fixture();
        f.relicId = relicId;
        f.projectId = projectId;
        f.materialId = materialId;
        f.batchNo = batchNo;
        f.usageId = dataOf(postOk("/api/material-usages", "{\"usageNo\":\"MU-RI-" + tag
                + "\",\"projectId\":" + projectId + ",\"lines\":[{\"batchNo\":\"" + batchNo
                + "\",\"planQty\":" + planQty + ",\"actualQty\":" + actualQty
                + ",\"applyPart\":\"口沿补配\",\"operator\":\"修复师甲\"}]}")).get("id").asLong();
        f.lineId = dataOf(getJson("/api/material-usages/" + f.usageId))
                .get("lines").get(0).get("id").asLong();
        return f;
    }

    /** 二级文物会签顺序：馆内专家 -> 外部专家 -> 保管人员，全部同意方可开工 */
    private void countersign(long projectId) throws Exception {
        long versionId = dataOf(postOk("/api/repair-projects/" + projectId + "/plan-versions", "{"
                + "\"planContent\":\"补配随色\",\"internalExpert\":\"馆内专家甲\","
                + "\"externalExpert\":\"外部专家乙\",\"custodian\":\"保管员丙\","
                + "\"submittedBy\":\"项目负责人\"}")).get("version").get("id").asLong();
        postOk("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"INTERNAL\",\"signerName\":\"馆内专家甲\",\"decision\":\"APPROVE\"}");
        postOk("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"EXTERNAL\",\"signerName\":\"外部专家乙\",\"decision\":\"APPROVE\"}");
        postOk("/api/plan-versions/" + versionId + "/sign",
                "{\"signerRole\":\"CUSTODIAN\",\"signerName\":\"保管员丙\",\"decision\":\"APPROVE\"}");
    }

    private String returnJson(String returnNo, long usageId, long lineId,
                              String qty, String reason, String returnedBy) {
        return "{\"returnNo\":\"" + returnNo + "\",\"usageId\":" + usageId
                + ",\"lineId\":" + lineId + ",\"returnQty\":" + qty
                + ",\"reason\":\"" + reason + "\",\"returnedBy\":\"" + returnedBy + "\"}";
    }

    /** 该文物台账中的全部 MATERIAL_RETURN 库存流水 */
    private List<JsonNode> returnLedgers(long relicId) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode e : dataOf(getJson("/api/relics/" + relicId + "/ledger"))) {
            if ("MATERIAL_RETURN".equals(e.get("action").asText())) {
                result.add(e);
            }
        }
        return result;
    }

    private JsonNode reconcile(long materialId, String batchNo) throws Exception {
        return dataOf(getJson("/api/materials/batches/" + batchIdOf(materialId, batchNo) + "/reconcile"));
    }

    private double stockOf(long materialId, String batchNo) throws Exception {
        for (JsonNode b : dataOf(getJson("/api/materials/batches?materialId=" + materialId))) {
            if (batchNo.equals(b.get("batchNo").asText())) {
                return b.get("stockQty").asDouble();
            }
        }
        throw new IllegalStateException("批号未找到: " + batchNo);
    }

    private long batchIdOf(long materialId, String batchNo) throws Exception {
        for (JsonNode b : dataOf(getJson("/api/materials/batches?materialId=" + materialId))) {
            if (batchNo.equals(b.get("batchNo").asText())) {
                return b.get("id").asLong();
            }
        }
        throw new IllegalStateException("批号未找到: " + batchNo);
    }

    /** 用同一退料单号模板并发发起退料 */
    private List<Outcome> parallel(int threads,
                                   java.util.function.Function<Integer, String> bodyBuilder)
            throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Outcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                MvcResult result = mockMvc.perform(post("/api/material-returns")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bodyBuilder.apply(idx)))
                        .andExpect(status().isOk()).andReturn();
                JsonNode root = objectMapper.readTree(result.getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
                Outcome o = new Outcome();
                o.success = root.get("success").asBoolean();
                o.message = root.get("message").asText();
                if (o.success) {
                    o.id = root.get("data").get("id").asLong();
                }
                return o;
            }));
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(20, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private long createRelic(String body) throws Exception {
        return dataOf(postJson("/api/relics", body)).get("id").asLong();
    }

    private MvcResult postJson(String uri, String body) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult postOk(String uri, String body) throws Exception {
        MvcResult result = postJson(uri, body);
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(root.get("success").asBoolean(),
                "期望成功，实际: " + root.get("message").asText());
        return result;
    }

    private MvcResult postEmpty(String uri) throws Exception {
        return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk()).andReturn();
    }

    private MvcResult getJson(String uri) throws Exception {
        return mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn();
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");
    }

    private void expectFail(MvcResult result, String messageContains) throws Exception {
        JsonNode root = objectMapper.readTree(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(root.get("success").asBoolean());
        assertTrue(root.get("message").asText().contains(messageContains),
                "错误信息应包含「" + messageContains + "」，实际: " + root.get("message").asText());
    }
}
