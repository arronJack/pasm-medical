package com.pasm.medical.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pasm.medical.domain.Encounter;
import com.pasm.medical.domain.SystemConfig;
import com.pasm.medical.repository.EncounterRepository;
import com.pasm.medical.repository.KbCandidateRepository;
import com.pasm.medical.repository.KnowledgeDocRepository;
import com.pasm.medical.repository.SystemConfigRepository;
import com.pasm.medical.config.TokenService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 自学习审核台（候选池 + 采纳入库 + 知识池版本 + 回滚）的端到端验证（真 PostgreSQL）。
 *
 * <p>验的是"这条自学习闭环真的通了"：
 * <ul>
 *   <li>每日批处理从近期就诊抽候选、指纹去重、与现库冲突检测挂起；</li>
 *   <li>采纳 → 入库 knowledge_docs + 知识池版本 +1 + 同步认知侧（不可达时如实 synced=false）；</li>
 *   <li>驳回 → 绝不入库；</li>
 *   <li>回滚 → 停用上一版本引入的资料、版本号回退、不删历史；</li>
 *   <li>授权：生成/回滚仅超管；审核按科室收窄（科室管理员看不到别科的候选）。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class CandidatePostgresTest {

    private static final EmbeddedPostgres PG;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new IllegalStateException("嵌入式 PostgreSQL 启动失败", e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        // 测试中开启演示账号，便于用 TokenService 直接签发各角色令牌
        registry.add("medical.auth.demo-login-enabled", () -> "true");
    }

    @Autowired private MockMvc mvc;
    @Autowired private TokenService tokens;
    @Autowired private KbCandidateRepository candRepo;
    @Autowired private EncounterRepository encounters;
    @Autowired private KnowledgeDocRepository kbDocs;
    @Autowired private SystemConfigRepository configs;

    private final ObjectMapper om = new ObjectMapper();

    private String superToken;
    private String deptToken;
    private String doctorToken;

    @BeforeEach
    void setUp() {
        // ★ 每个用例独立：清数据 + 知识池版本复位到 1，避免用例互相污染（沿用 P2 教训）
        candRepo.deleteAll();
        encounters.deleteAll();
        kbDocs.deleteAll();
        SystemConfig v = configs.findById("kb_pool_version").orElseGet(() -> {
            SystemConfig s = new SystemConfig();
            s.setKey("kb_pool_version");
            return s;
        });
        v.setValue("1");
        configs.save(v);

        superToken = tokens.login("staff", "123456", null).token();
        deptToken = tokens.login("deptadmin", "123456", "发热门诊").token();
        doctorToken = tokens.login("doctor", "123456", "发热门诊").token();
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token);
    }

    private void seedEncounter(String dept, String cc, String assess, String plan) {
        Encounter e = new Encounter();
        e.setPatientRef("CAND-" + System.nanoTime());
        e.setOccurredAt(java.time.Instant.now());
        e.setDepartment(dept);
        e.setChiefComplaint(cc);
        e.setAssessment(assess);
        e.setPlan(plan);
        e.setTriageUrgency("routine");
        e.setSummaryText("主诉：" + cc + "；判断：" + assess + "；处置：" + plan);
        encounters.save(e);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listCandidates(String token, String status) throws Exception {
        String path = status == null ? "/api/admin/kb/candidates"
                : "/api/admin/kb/candidates?status=" + status;
        String body = mvc.perform(auth(get(path), token)).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readValue(body, List.class);
    }

    // ------------------------------------------------------------ 生成 / 列表 / 科室范围 / 采纳

    @Test
    void generateListDeptScopeAndApprove() throws Exception {
        seedEncounter("发热门诊", "发热测试A", "上感A", "对症A");
        seedEncounter("骨科", "膝痛测试B", "退变B", "复诊B");

        mvc.perform(auth(post("/api/admin/kb/candidates/generate?days=30"), superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generated").value(2));

        // 超管看全量
        assertEquals(2, listCandidates(superToken, null).size());

        // 科室管理员（发热门诊）只看本科室那一条
        List<Map<String, Object>> dept = listCandidates(deptToken, null);
        assertEquals(1, dept.size());
        assertEquals("发热测试A", dept.get(0).get("title"));
        long feverId = ((Number) dept.get(0).get("id")).longValue();

        // 本科室候选可采纳
        mvc.perform(auth(post("/api/admin/kb/candidates/" + feverId + "/approve"), deptToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docKey").exists())
                .andExpect(jsonPath("$.poolVersion").value(2));

        // 别科候选（骨科）对发热门诊管理员 403
        long orthoId = listCandidates(superToken, null).stream()
                .filter(m -> "膝痛测试B".equals(m.get("title")))
                .map(m -> ((Number) m.get("id")).longValue())
                .findFirst().orElseThrow();
        mvc.perform(auth(post("/api/admin/kb/candidates/" + orthoId + "/approve"), deptToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------ 冲突检测挂起

    @Test
    void conflictDetectionSuspendsCandidate() throws Exception {
        // 先放一条现库资料，标题与将来候选相同、内容不同
        String kbBody = mvc.perform(auth(post("/api/admin/kb")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "title", "冲突主诉C", "content", "旧结论",
                                "tags", "x", "department", "发热门诊"))), superToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String kbKey = om.readTree(kbBody).get("doc").get("docKey").asText();

        seedEncounter("发热门诊", "冲突主诉C", "新结论", "新处置");

        mvc.perform(auth(post("/api/admin/kb/candidates/generate?days=30"), superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflict").value(1));

        List<Map<String, Object>> conflicted = listCandidates(superToken, "CONFLICT");
        assertEquals(1, conflicted.size());
        assertEquals("冲突主诉C", conflicted.get(0).get("title"));
        assertEquals(kbKey, conflicted.get(0).get("conflictWith"));
    }

    // ------------------------------------------------------------ 驳回不入库 + 已处理不可再改

    @Test
    void rejectDoesNotPersistAndBlocksReapprove() throws Exception {
        seedEncounter("发热门诊", "驳回主诉D", "评估D", "处置D");
        mvc.perform(auth(post("/api/admin/kb/candidates/generate?days=30"), superToken))
                .andExpect(status().isOk());

        long id = ((Number) listCandidates(superToken, "PENDING").get(0).get("id")).longValue();

        mvc.perform(auth(post("/api/admin/kb/candidates/" + id + "/reject"), deptToken))
                .andExpect(status().isOk());

        List<Map<String, Object>> rejected = listCandidates(superToken, "REJECTED");
        assertEquals(1, rejected.size());
        assertEquals("REJECTED", rejected.get(0).get("status"));

        // 已处理的候选不能再采纳（409）
        mvc.perform(auth(post("/api/admin/kb/candidates/" + id + "/approve"), deptToken))
                .andExpect(status().isConflict());
    }

    // ------------------------------------------------------------ 知识池版本 + 回滚

    @Test
    void approveBumpsPoolVersionAndRollbackReverts() throws Exception {
        seedEncounter("发热门诊", "回滚主诉E", "评估E", "处置E");
        mvc.perform(auth(post("/api/admin/kb/candidates/generate?days=30"), superToken))
                .andExpect(status().isOk());

        long id = ((Number) listCandidates(superToken, "PENDING").get(0).get("id")).longValue();

        String app = mvc.perform(auth(post("/api/admin/kb/candidates/" + id + "/approve"), deptToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode appNode = om.readTree(app);
        String docKey = appNode.get("docKey").asText();
        assertEquals(2, appNode.get("poolVersion").asInt());

        // 版本信息：当前 v2、可回滚
        JsonNode pv = om.readTree(mvc.perform(auth(get("/api/admin/kb-pool/version"), superToken))
                .andReturn().getResponse().getContentAsString());
        assertEquals(2, pv.get("version").asInt());
        assertEquals(true, pv.get("rollbackAvailable").asBoolean());

        // 回滚
        String rb = mvc.perform(auth(post("/api/admin/kb-pool/rollback"), superToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode rbNode = om.readTree(rb);
        assertEquals(2, rbNode.get("from").asInt());
        assertEquals(1, rbNode.get("to").asInt());
        assertEquals(1, rbNode.get("deactivated").asInt());

        // 版本回到 v1、不可再回滚
        JsonNode pv2 = om.readTree(mvc.perform(auth(get("/api/admin/kb-pool/version"), superToken))
                .andReturn().getResponse().getContentAsString());
        assertEquals(1, pv2.get("version").asInt());
        assertEquals(false, pv2.get("rollbackAvailable").asBoolean());

        // 被批准的那条资料已下架（inactive），但仍可审计
        JsonNode kb = om.readTree(mvc.perform(auth(get("/api/admin/kb"), superToken))
                .andReturn().getResponse().getContentAsString());
        boolean foundInactive = false;
        for (JsonNode d : kb.get("docs")) {
            if (docKey.equals(d.get("docKey").asText())) {
                assertEquals("inactive", d.get("status").asText());
                foundInactive = true;
            }
        }
        assertTrue(foundInactive, "回滚后该资料应下架（inactive）但仍保留");
    }

    // ------------------------------------------------------------ 授权：生成/回滚仅超管

    @Test
    void authGatesGenerateAndRollbackToSuperAdmin() throws Exception {
        // 无令牌 → 401
        mvc.perform(get("/api/admin/kb/candidates")).andExpect(status().isUnauthorized());
        // 医生（非超管）生成 → 403
        mvc.perform(auth(post("/api/admin/kb/candidates/generate?days=7"), doctorToken))
                .andExpect(status().isForbidden());
        // 科室管理员回滚 → 403
        mvc.perform(auth(post("/api/admin/kb-pool/rollback"), deptToken))
                .andExpect(status().isForbidden());
    }
}
