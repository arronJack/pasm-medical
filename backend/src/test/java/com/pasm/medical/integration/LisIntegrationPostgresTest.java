package com.pasm.medical.integration;

import com.pasm.medical.domain.LabResult;
import com.pasm.medical.domain.Patient;
import com.pasm.medical.repository.LabResultRepository;
import com.pasm.medical.repository.PatientRepository;
import com.pasm.medical.service.FhirIngestService;
import com.pasm.medical.service.LisIngestService;
import com.pasm.medical.service.SystemConfigService;
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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LIS / FHIR 接入的端到端验证（真 PostgreSQL + 真实 HTTP 层）。
 *
 * <p>验的是"这条线真的通了"，而不是"类能编译"：
 * <ul>
 *   <li>V2 迁移在真 PG 上执行，JPA {@code validate} 通过（否则上下文起不来）；</li>
 *   <li>HL7 ORU 落库、字段正确、<b>重发幂等</b>；</li>
 *   <li>配置为 off / 模式不匹配时<b>明确拒绝</b>；</li>
 *   <li>未知患者拒收，不凭空建档；</li>
 *   <li>HTTP 层：无令牌 401、错令牌 401、对令牌 202；坏消息 400 且不落库。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class LisIntegrationPostgresTest {

    private static final String TOKEN = "test-lis-token";

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
        registry.add("medical.lis.token", () -> TOKEN);
    }

    @Autowired
    private LisIngestService lis;
    @Autowired
    private FhirIngestService fhir;
    @Autowired
    private SystemConfigService config;
    @Autowired
    private PatientRepository patients;
    @Autowired
    private LabResultRepository labs;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private MockMvc mvc;

    private static final String ORU = String.join("\r",
            "MSH|^~\\&|LIS|HOSP|EMR|HOSP|20261104120000+0800||ORU^R01|MSG-IT-001|P|2.5.1",
            "PID|1||P900^^^HOSP^MR",
            "OBR|1|ORD900|SPEC900|CBC^血常规^L|||20261104115500",
            "OBX|1|NM|WBC^白细胞计数^L||6.2|10*9/L|3.5-9.5|N|||F|||20261104115800",
            "OBX|2|NM|HGB^血红蛋白^L||98|g/L|130-175|L|||F|||20261104115800");

    @BeforeEach
    void setUp() {
        setMode("hl7");
    }

    // ------------------------------------------------------------ 落库与幂等

    @Test
    void hl7IngestStoresResultsAndIsIdempotent() throws Exception {
        // 独立患者 + 独立消息号：同一类里其它用例也在写库，共用 ref 会让计数互相污染。
        ensurePatient("P920");
        String msg = ORU.replace("P900", "P920").replace("MSG-IT-001", "MSG-IT-920");

        LisIngestService.IngestResult first = lis.ingest(msg);
        assertEquals(2, first.inserted());
        assertEquals(0, first.skippedDuplicates());
        assertEquals("P920", first.patientRef());
        assertEquals("MSG-IT-920", first.messageControlId());

        LisIngestService.IngestResult second = lis.ingest(msg);
        assertEquals(0, second.inserted(), "重发不应再插一行");
        assertEquals(2, second.skippedDuplicates());

        List<LabResult> rows = labs.findByPatientRefOrderByObservedAtDescIdDesc("P920");
        assertEquals(2, rows.size(), "重发后仍应只有 2 行");

        LabResult wbc = rows.stream().filter(r -> "WBC".equals(r.getAnalyteCode()))
                .findFirst().orElseThrow();
        assertEquals("normal", wbc.getAbnormalFlag());
        assertEquals("N", wbc.getAbnormalFlagRaw());
        assertEquals("10*9/L", wbc.getUnit());
        assertEquals(0, wbc.getRefLow().compareTo(new java.math.BigDecimal("3.5")));
        assertEquals("CBC", wbc.getPanelCode());
        assertEquals("血常规", wbc.getPanelName());
        assertEquals(java.time.Instant.parse("2026-11-04T03:58:00Z"), wbc.getObservedAt());

        LabResult hgb = rows.stream().filter(r -> "HGB".equals(r.getAnalyteCode()))
                .findFirst().orElseThrow();
        assertEquals("low", hgb.getAbnormalFlag());

        // V2 迁移真的执行了
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM flyway_schema_history WHERE version='2' AND success=true")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "V2 应落账");
        }
    }

    // ------------------------------------------------------------ 配置闸门

    @Test
    void disabledConfigRejectsClearly() {
        setMode("off");
        LisDisabledException e = assertThrows(LisDisabledException.class, () -> lis.ingest(ORU));
        assertTrue(e.getMessage().contains("off"));

        setMode("fhir");
        // 配置成 fhir 时 HL7 通道必须拒绝 —— 不能"两条都收"
        assertThrows(LisDisabledException.class, () -> lis.ingest(ORU));
    }

    @Test
    void unknownPatientIsRejectedWithoutCreatingRecord() {
        String msg = ORU.replace("P900", "P-NOT-EXIST");
        assertThrows(UnknownPatientException.class, () -> lis.ingest(msg));
        assertTrue(!patients.existsById("P-NOT-EXIST"), "不得凭空建档");
    }

    @Test
    void malformedMessageStoresNothing() {
        String bad = ORU.replace("P900", "P902").replace("ORU^R01", "ADT^A01");
        assertThrows(RuntimeException.class, () -> lis.ingest(bad));
        assertEquals(0, labs.countByPatientRef("P902"), "整条消息拒收时不应落任何行");
    }

    // ------------------------------------------------------------ HTTP 鉴权

    @Test
    void httpEndpointRejectsMissingOrWrongToken() throws Exception {
        ensurePatient("P900");
        mvc.perform(post("/api/integration/lis/oru")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(ORU))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("missing_token"));

        mvc.perform(post("/api/integration/lis/oru")
                        .header("X-LIS-Token", "wrong")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(ORU))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_token"));
    }

    @Test
    void httpEndpointAcceptsValidTokenAndReturnsParseErrors() throws Exception {
        ensurePatient("P900");
        String msg = ORU.replace("MSG-IT-001", "MSG-HTTP-001");
        mvc.perform(post("/api/integration/lis/oru")
                        .header("X-LIS-Token", TOKEN)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(msg))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.inserted").value(2));

        String broken = msg.replace("MSG-HTTP-001", "MSG-HTTP-BAD")
                .replace("||6.2|", "|||");
        mvc.perform(post("/api/integration/lis/oru")
                        .header("X-LIS-Token", TOKEN)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(broken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("hl7_parse_error"))
                .andExpect(jsonPath("$.segment").value("OBX"));
    }

    @Test
    void fhirReadRequiresUserAuthentication() throws Exception {
        // 机器令牌只开投递通道，不能读数据
        mvc.perform(get("/api/fhir/Observation").param("patient", "P900"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------ FHIR 投递

    @Test
    void fhirObservationIngestIsIdempotent() throws Exception {
        setMode("fhir");
        ensurePatient("P910");
        String json = """
                {
                  "resourceType": "Observation",
                  "id": "obs-it-1",
                  "status": "final",
                  "code": {"coding": [{"system": "urn:pasm:medical:analyte",
                                       "code": "GLU", "display": "血糖"}]},
                  "subject": {"reference": "Patient/P910"},
                  "effectiveDateTime": "2026-11-04T02:00:00Z",
                  "valueQuantity": {"value": 7.2, "unit": "mmol/L"},
                  "interpretation": [{"coding": [{"code": "H"}]}],
                  "referenceRange": [{"low": {"value": 3.9}, "high": {"value": 6.1},
                                      "text": "3.9-6.1"}]
                }
                """;
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var node = mapper.readTree(json);

        LisIngestService.IngestResult first = fhir.ingest(node);
        assertEquals(1, first.inserted());

        LisIngestService.IngestResult again = fhir.ingest(node);
        assertEquals(0, again.inserted());
        assertEquals(1, again.skippedDuplicates());

        LabResult r = labs.findFirstByMessageControlIdAndObservationSeq("obs-it-1", 1).orElseThrow();
        assertEquals("GLU", r.getAnalyteCode());
        assertEquals("血糖", r.getAnalyteName());
        assertEquals("high", r.getAbnormalFlag());
        assertEquals(0, r.getValueNum().compareTo(new java.math.BigDecimal("7.2")));
        assertEquals("fhir", r.getSource());
        assertEquals(java.time.Instant.parse("2026-11-04T02:00:00Z"), r.getObservedAt());

        // 同一 id 冒名挂到别的患者：必须冲突而不是覆盖
        ensurePatient("P911");
        var hijack = (com.fasterxml.jackson.databind.node.ObjectNode) node.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) hijack.get("subject"))
                .put("reference", "Patient/P911");
        assertThrows(IngestConflictException.class, () -> fhir.ingest(hijack));
    }

    @Test
    void fhirFallsBackToReferenceRangeComparisonWithoutInterpretation() throws Exception {
        setMode("fhir");
        ensurePatient("P900");
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {
                  "resourceType": "Observation",
                  "id": "obs-it-2",
                  "status": "final",
                  "code": {"coding": [{"code": "K", "display": "血钾"}]},
                  "subject": {"reference": "Patient/P900"},
                  "valueQuantity": {"value": 2.8, "unit": "mmol/L"},
                  "referenceRange": [{"low": {"value": 3.5}, "high": {"value": 5.3}}]
                }
                """);
        fhir.ingest(node);
        LabResult r = labs.findFirstByMessageControlIdAndObservationSeq("obs-it-2", 1).orElseThrow();
        assertEquals("low", r.getAbnormalFlag(), "无标记时应按参考区间比较");
        assertEquals("3.5-5.3", r.getRefRangeText(), "缺 text 时应由区间生成可读文本");
    }

    // ------------------------------------------------------------ 工具

    private void setMode(String mode) {
        SystemConfigService.Settings cur = config.read();
        config.save(new SystemConfigService.Settings(
                cur.ocr(), mode, cur.llm(), cur.model(), cur.baseUrl()), "test");
    }

    private void ensurePatient(String ref) {
        if (!patients.existsById(ref)) {
            Patient p = new Patient();
            p.setRef(ref);
            p.setName("集成测试患者 " + ref);
            patients.save(p);
        }
    }
}
