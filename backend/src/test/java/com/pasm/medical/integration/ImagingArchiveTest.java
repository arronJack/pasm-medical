package com.pasm.medical.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pasm.medical.repository.ImagingStudyRepository;
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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 影像「归档 + 转交」的端到端验证（真 PostgreSQL）。
 *
 * <p>★ 重点不在「能存」，而在「哪些事坚决不做」：
 * <ul>
 *   <li>归档成功 → 响应带 {@code noAnalysis=true}，且<b>没有任何</b>印象/所见/结论字段；</li>
 *   <li>入参带结论类字段（impression / airesult / report …）→ <b>400 ANALYSIS_FORBIDDEN</b>；
 *       这是「本系统不做影像分析」的硬边界，不是文档里的一句话；</li>
 *   <li>患者只能碰自己的记录：看别人的 ref → 403，拿别人的 id → 404（不当存在性预言机）；</li>
 *   <li>转交仅医护侧、且不能重复转交；患者转交 → 403。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ImagingArchiveTest {

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
        registry.add("medical.auth.demo-login-enabled", () -> "true");
        // 测试把归档落临时目录，避免污染仓库工作树
        registry.add("medical.imaging.dir",
                () -> System.getProperty("java.io.tmpdir") + "/pasm-imaging-test");
        registry.add("medical.imaging.max-bytes", () -> "1048576");
    }

    @Autowired private MockMvc mvc;
    @Autowired private TokenService tokens;
    @Autowired private ImagingStudyRepository imagingRepo;

    private final ObjectMapper om = new ObjectMapper();

    private String patientToken;
    private String doctorToken;
    private String staffToken;

    @BeforeEach
    void setUp() {
        imagingRepo.deleteAll();
        patientToken = tokens.login("patient", "123456", null).token();
        doctorToken = tokens.login("doctor", "123456", "发热门诊").token();
        staffToken = tokens.login("staff", "123456", null).token();
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header("Authorization", "Bearer " + token);
    }

    private static byte[] sampleBytes() {
        // 一段无害的伪影像字节（不解析、不解码）
        byte[] raw = new byte[2048];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i & 0xFF);
        }
        return raw;
    }

    private Map<String, Object> archiveBody(String patientRef) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (patientRef != null) {
            body.put("patientRef", patientRef);
        }
        body.put("modality", "CT");
        body.put("bodyPart", "头部");
        body.put("fileName", "head-ct.dcm");
        body.put("contentType", "application/dicom");
        body.put("contentBase64", Base64.getEncoder().encodeToString(sampleBytes()));
        body.put("studyDate", "2026-11-06");
        body.put("accessionNo", "ACC-0001");
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> archive(String token, Map<String, Object> body) throws Exception {
        String r = mvc.perform(auth(post("/api/imaging/studies")
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)), token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readValue(r, Map.class);
    }

    // ------------------------------------------------------------ 正例：归档 + 不分析声明

    @Test
    void patientArchiveSucceedsAndDeclaresNoAnalysis() throws Exception {
        Map<String, Object> saved = archive(patientToken, archiveBody(null));

        assertEquals(true, saved.get("noAnalysis"));
        assertEquals("ARCHIVED", saved.get("status"));
        assertEquals(2048, saved.get("byteSize"));
        String sha = String.valueOf(saved.get("sha256"));
        assertEquals(64, sha.length());                 // SHA-256 十六进制
        // ★ 白名单视图里绝不能出现结论字段
        for (String leak : new String[]{"impression", "findings", "conclusion",
                "aiResult", "report", "analysis"}) {
            assertEquals(false, saved.containsKey(leak), "响应泄漏了结论字段：" + leak);
        }

        long id = ((Number) saved.get("id")).longValue();
        // 患者本人能列、能看详情、能下原始字节
        mvc.perform(auth(get("/api/imaging/studies"), patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(auth(get("/api/imaging/studies/" + id), patientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(auth(get("/api/imaging/studies/" + id + "/content"), patientToken))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------ 反例：结论字段一律拒绝

    @Test
    void analysisFieldRejectedWith400() throws Exception {
        for (String bad : new String[]{"impression", "airesult", "report", "findings",
                "conclusion", "analysis"}) {
            Map<String, Object> body = archiveBody("demo-patient-001");
            body.put(bad, "疑似占位");
            mvc.perform(auth(post("/api/imaging/studies")
                            .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)),
                            doctorToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("ANALYSIS_FORBIDDEN"));
        }
        // 边界真的生效：没有一条被写进库
        assertEquals(0, imagingRepo.count());
    }

    // ------------------------------------------------------------ 反例：患者越权隔离

    @Test
    void patientCannotViewOtherRefAndOtherIdIs404() throws Exception {
        // 医护为「他人」归档一条
        Map<String, Object> other = archive(doctorToken, archiveBody("OTHER-PATIENT-001"));
        long otherId = ((Number) other.get("id")).longValue();

        // 患者看别人的 ref → 403（不发「这个 ref 有数据」的信号）
        mvc.perform(auth(get("/api/imaging/studies?ref=OTHER-PATIENT-001"), patientToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_PATIENT"));

        // 患者拿别人的 id → 404（不区分「不存在」与「不属于你」，防自增 id 预言机）
        mvc.perform(auth(get("/api/imaging/studies/" + otherId), patientToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(auth(get("/api/imaging/studies/" + otherId + "/content"), patientToken))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ 转交：医护侧、幂等、拒绝患者

    @Test
    void handoffByClinicalIdempotentAndRejectsPatient() throws Exception {
        Map<String, Object> saved = archive(doctorToken, archiveBody("demo-patient-001"));
        long id = ((Number) saved.get("id")).longValue();

        // 转交给已启用的科室 → 200
        mvc.perform(auth(post("/api/imaging/studies/" + id + "/handoff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "department", "发热门诊", "note", "请阅片"))), doctorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HANDED_OFF"))
                .andExpect(jsonPath("$.handoffDepartment").value("发热门诊"));

        // 重复转交 → 409
        mvc.perform(auth(post("/api/imaging/studies/" + id + "/handoff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("department", "骨科"))), doctorToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_HANDED_OFF"));

        // 转交给不存在的科室 → 400
        long id2 = ((Number) archive(doctorToken, archiveBody("demo-patient-001")).get("id")).longValue();
        mvc.perform(auth(post("/api/imaging/studies/" + id2 + "/handoff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("department", "不存在的科室"))), doctorToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_DEPARTMENT"));

        // 患者转交 → 403
        mvc.perform(auth(post("/api/imaging/studies/" + id + "/handoff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("department", "发热门诊"))), patientToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CLINICAL_ONLY"));
    }

    // ------------------------------------------------------------ 队列：只显示待转交

    @Test
    void queueShowsOnlyArchived() throws Exception {
        Map<String, Object> a = archive(doctorToken, archiveBody("demo-patient-001"));
        long id = ((Number) a.get("id")).longValue();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> before = om.readValue(mvc.perform(auth(get("/api/imaging/queue"), doctorToken))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8), List.class);
        assertEquals(1, before.size());

        // 转交后不在「待转交」队列
        mvc.perform(auth(post("/api/imaging/studies/" + id + "/handoff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("department", "发热门诊"))), doctorToken))
                .andExpect(status().isOk());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> after = om.readValue(mvc.perform(auth(get("/api/imaging/queue"), doctorToken))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8), List.class);
        assertEquals(0, after.size());
    }

    @Test
    void queueForbiddenForPatient() throws Exception {
        mvc.perform(auth(get("/api/imaging/queue"), patientToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CLINICAL_ONLY"));
    }
}
