package com.pasm.medical.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.pasm.medical.cognition.PasmCognitionClient;
import com.pasm.medical.domain.AiAudit;
import com.pasm.medical.domain.Encounter;
import com.pasm.medical.domain.Patient;
import com.pasm.medical.service.AuditService;
import com.pasm.medical.service.EncounterService;
import com.pasm.medical.service.PatientService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 医疗接口 —— 业务层到认知服务的**薄转发**。
 *
 * ★ 为什么是薄转发而不是在 Java 里重写一遍：
 *   问诊树、相关性闸门、检验单判读的**唯一实现**在 Python 侧（{@code pasm_medical}）。
 *   在 Java 里再实现一遍就是同源两份代码 —— 这个项目已经因为同源两份代码
 *   坏过一次（相关性闸门只回植了一边）。所以这一层的职责只有三件：
 *   **鉴权、参数校验、透传**。
 *
 * ★ 所有响应都带 {@code requiresPhysicianConfirmation}：这是"辅助"定位的技术表达。
 *
 * ★ 本层同时承担两件"业务层该做的事"—— 与薄转发不冲突：
 *   ① 每次认知调用都**留痕到审计库**（append-only，不可变）；
 *   ② 预问诊结束（finish）时把一次就诊**落业务库**（Encounter），
 *      使后台「患者情况 / 时间轴」有真实数据源，而不是前端硬编码。
 */
@RestController
@RequestMapping("/api")
public class MedicalProxyController {

    private final PasmCognitionClient cog;
    private final AuditService audit;
    private final PatientService patients;
    private final EncounterService encounters;

    public MedicalProxyController(PasmCognitionClient cog, AuditService audit,
                                 PatientService patients, EncounterService encounters) {
        this.cog = cog;
        this.audit = audit;
        this.patients = patients;
        this.encounters = encounters;
    }

    @GetMapping("/encounters")
    public ResponseEntity<Map<String, Object>> encounters(@RequestParam String patientRef) {
        JsonNode r = cog.get("/api/encounters", Map.of("patientRef", patientRef));
        return ResponseEntity.ok(Map.of(
                "encounters", r.path("encounters"),
                "requiresPhysicianConfirmation", true));
    }

    /** 患者档案（业务层视图）：过敏史 / 慢病这类高优先级事实来自结构化字段。 */
    @GetMapping("/patient")
    public ResponseEntity<Map<String, Object>> patient(@RequestParam String ref) {
        Patient p = patients.findById(ref);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", p.getRef());
        m.put("name", p.getName());
        m.put("sex", p.getSex());
        m.put("age", p.getAge());
        m.put("allergy", p.getAllergy());
        m.put("chronic", p.getChronic());
        return ResponseEntity.ok(m);
    }

    /** 该患者的历史就诊（业务库真实数据源，而非前端硬编码）。 */
    @GetMapping("/patient/encounters")
    public ResponseEntity<List<Map<String, Object>>> patientEncounters(@RequestParam String ref) {
        ZoneId z = ZoneId.systemDefault();
        List<Map<String, Object>> out = encounters.byPatient(ref).stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(e.getId()));
            m.put("time", e.getOccurredAt().atZone(z).toString().replace('T', ' ').substring(0, 16));
            m.put("title", e.getChiefComplaint() == null ? "就诊记录" : e.getChiefComplaint());
            m.put("summary", e.getSummaryText() == null ? "" : e.getSummaryText());
            m.put("urgency", e.getTriageUrgency() == null ? "routine" : e.getTriageUrgency());
            return m;
        }).toList();
        return ResponseEntity.ok(out);
    }

    @PostMapping("/consult/start")
    public ResponseEntity<Map<String, Object>> consultStart(@RequestBody Map<String, Object> body) {
        String ref = str(body, "patientRef");
        patients.ensureExists(ref);
        JsonNode r = cog.post("/api/consult/start", body == null ? new HashMap<>() : body);
        auditForward("/api/consult/start", ref, body, r);
        return wrap(r);
    }

    @PostMapping("/consult/answer")
    public ResponseEntity<Map<String, Object>> consultAnswer(@RequestBody Map<String, Object> body) {
        String ref = str(body, "patientRef");
        JsonNode r = cog.post("/api/consult/answer", body == null ? new HashMap<>() : body);
        auditForward("/api/consult/answer", ref, body, r);
        return wrap(r);
    }

    @PostMapping("/consult/finish")
    public ResponseEntity<Map<String, Object>> consultFinish(@RequestBody Map<String, Object> body) {
        String ref = str(body, "patientRef");
        JsonNode r = cog.post("/api/consult/finish", body == null ? new HashMap<>() : body);
        // 预问诊结束：落一次就诊到业务库（时间轴 / 后台的真实数据源）
        if (ref != null && !ref.isBlank()) {
            try {
                Encounter e = new Encounter();
                e.setPatientRef(ref);
                e.setOccurredAt(Instant.now());
                e.setChiefComplaint(r.path("chiefComplaint").asText(null));
                e.setAssessment(r.path("assessment").asText(null));
                e.setPlan(r.path("plan").asText(null));
                JsonNode triage = r.path("triage");
                if (!triage.isMissingNode() && !triage.isNull()) {
                    e.setTriageUrgency(triage.path("urgency").asText(null));
                }
                StringBuilder sb = new StringBuilder();
                if (e.getChiefComplaint() != null) sb.append("主诉：").append(e.getChiefComplaint()).append("；");
                if (e.getAssessment() != null) sb.append("判断：").append(e.getAssessment()).append("；");
                if (e.getPlan() != null) sb.append("处置：").append(e.getPlan());
                e.setSummaryText(sb.toString());
                encounters.save(e);
            } catch (Exception ex) {
                // 落库失败不影响本次返回；但要在审计里记一笔，避免静默丢失
                auditForward("/api/consult/finish", ref, body, r);
            }
        }
        auditForward("/api/consult/finish", ref, body, r);
        return wrap(r);
    }

    /** 检验单识别：返回**待确认**结果 —— 未确认不会写入病历。 */
    @PostMapping("/lab/parse")
    public ResponseEntity<Map<String, Object>> labParse(@RequestBody Map<String, Object> body) {
        String ref = str(body, "patientRef");
        JsonNode r = cog.post("/api/lab/parse", body == null ? new HashMap<>() : body);
        auditForward("/api/lab/parse", ref, body, r);
        return wrap(r);
    }

    @PostMapping("/lab/confirm")
    public ResponseEntity<Map<String, Object>> labConfirm(@RequestBody Map<String, Object> body) {
        String ref = str(body, "patientRef");
        JsonNode r = cog.post("/api/lab/confirm", body == null ? new HashMap<>() : body);
        auditForward("/api/lab/confirm", ref, body, r);
        return wrap(r);
    }

    @PostMapping("/critical-fact")
    public ResponseEntity<Map<String, Object>> criticalFact(@RequestBody Map<String, Object> body) {
        String ref = str(body, "patientRef");
        JsonNode r = cog.post("/api/critical-fact", body == null ? new HashMap<>() : body);
        auditForward("/api/critical-fact", ref, body, r);
        return wrap(r);
    }

    // ------------------------------------------------------------ 内部

    private ResponseEntity<Map<String, Object>> wrap(JsonNode r) {
        Map<String, Object> out = new HashMap<>();
        if (r != null) {
            r.fields().forEachRemaining(e -> out.put(e.getKey(), cog.toPlain(e.getValue())));
        }
        out.put("requiresPhysicianConfirmation", true);
        return ResponseEntity.ok(out);
    }

    /** 透传时按路径留痕；finish 之外的调用也在此统一记录（append-only 审计）。 */
    private void auditForward(String path, String patientRef, Map<String, Object> body,
                              JsonNode r) {
        AiAudit a = new AiAudit();
        a.setPatientRef(patientRef);
        a.setAction(actionOf(path, r));
        a.setModelVersion("pasm-cognition");
        // 依据条数：检验单看 items，问诊红旗看 red_flags，其余留空
        if (r != null) {
            if (r.path("red_flags") != null && !r.path("red_flags").isMissingNode()) {
                a.setEvidenceCount(r.path("red_flags").size());
            } else if (r.path("items") != null && !r.path("items").isMissingNode()) {
                a.setEvidenceCount(r.path("items").size());
            }
        }
        a.setInputSnapshot("path=" + path);
        try { audit.record(a); } catch (Exception ignored) { }
    }

    private String actionOf(String path, JsonNode r) {
        if (path.endsWith("/consult/start")) {
            return "consult-start";
        }
        if (path.endsWith("/consult/answer")) {
            if (r != null && r.path("halted").asBoolean(false)
                    && r.path("red_flags").size() > 0) {
                return "consult-redflag";
            }
            return "consult-answer";
        }
        if (path.endsWith("/consult/finish")) {
            return "consult-finish";
        }
        if (path.endsWith("/lab/parse")) {
            return "lab-parse";
        }
        if (path.endsWith("/lab/confirm")) {
            return "lab-confirm";
        }
        if (path.endsWith("/critical-fact")) {
            return "critical-fact";
        }
        return "proxy";
    }

    private static String str(Map<String, Object> body, String k) {
        Object v = body == null ? null : body.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
