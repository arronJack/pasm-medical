package com.pasm.medical.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.pasm.medical.cognition.PasmCognitionClient;
import com.pasm.medical.config.IdentityContext;
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
 *
 * ★ <b>本层还负责"资源级"权限判定</b>：{@code SecurityConfig} 只能回答
 *   "这类角色能不能调这个接口"（按 URL 的粗粒度判定）；而"这个患者能不能看那条 ref"
 *   URL 里看不出来，必须在这里按身份收窄。
 *   **所有带 patientRef/ref 的接口一律先过 {@link IdentityContext#scopedRef}** ——
 *   少了这一步，接口本身是合法的，换个股就看到了别人的病历。
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
    public ResponseEntity<Map<String, Object>> encounters(
            @RequestParam(required = false) String patientRef) {
        String ref = IdentityContext.scopedRef(patientRef);
        if (ref == null) {
            return forbidden(patientRef, "read-timeline");
        }
        audit.recordRead(AuditService.currentActor(null), ref, "read-timeline",
                "patientRef=" + ref);
        JsonNode r = cog.get("/api/encounters", Map.of("patientRef", ref));
        return ResponseEntity.ok(Map.of(
                "encounters", r.path("encounters"),
                "requiresPhysicianConfirmation", true));
    }

    /** 患者档案（业务层视图）：过敏史 / 慢病这类高优先级事实来自结构化字段。 */
    @GetMapping("/patient")
    public ResponseEntity<Map<String, Object>> patient(
            @RequestParam(required = false) String ref) {
        // ★ 先收窄再取：ref 空着表示"本人"，写在参数里的别人一律 403
        String scoped = IdentityContext.scopedRef(ref);
        if (scoped == null) {
            return forbidden(ref, "read-patient");
        }
        // ★ 先记审计再看（含 404 的尝试）—— 见 AuditService.recordRead 的说明
        audit.recordRead(AuditService.currentActor(null), scoped, "read-patient",
                "ref=" + scoped);
        Patient p = patients.findById(scoped);
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
    public ResponseEntity<List<Map<String, Object>>> patientEncounters(
            @RequestParam(required = false) String ref) {
        String scoped = IdentityContext.scopedRef(ref);
        if (scoped == null) {
            deny(ref, "read-encounters");
            return ResponseEntity.status(403).build();
        }
        audit.recordRead(AuditService.currentActor(null), scoped, "read-encounters",
                "ref=" + scoped);
        ZoneId z = ZoneId.systemDefault();
        List<Map<String, Object>> out = encounters.byPatient(scoped).stream().map(e -> {
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

    /**
     * 单次就诊的**结构化详情**（左栏点开历史记录时用）。
     *
     * <p>列表接口只给"标题 + 摘要"，够列表用但不够看诊：医生点开一次历史问询，
     * 要看到主诉 / 判断 / 处置 / 科室 / 分诊 / 时间这些**各自独立的字段**，
     * 而不是把它们拼成一句话再靠肉眼拆。
     *
     * <p>★ {@code ref} 必填，且与就诊归属强校验：id 是自增整数，
     * 不校验归属就等于把全院病历开放给任何拿到患者令牌的人（IDOR）。
     * 未命中一律 404，不区分"不存在"与"不属于你" —— 后者会变成 id 探测预言机。
     */
    @GetMapping("/patient/encounter/{id}")
    public ResponseEntity<Map<String, Object>> patientEncounter(
            @PathVariable Long id, @RequestParam(required = false) String ref) {
        String scoped = IdentityContext.scopedRef(ref);
        if (scoped == null) {
            // 越权尝试连"用哪个 ref 看哪条就诊"都要留痕
            return forbidden(ref, "read-encounter");
        }
        // 归属不符时下面会 404，但"谁试图看过哪条就诊"已经留痕（IDOR 探测正是靠这个发现）
        audit.recordRead(AuditService.currentActor(null), scoped, "read-encounter",
                "ref=" + scoped + ";encounterId=" + id);
        Encounter e = encounters.findForPatient(scoped, id);
        if (e == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(e.getId()));
        m.put("ref", e.getPatientRef());
        m.put("time", e.getOccurredAt() == null ? ""
                : e.getOccurredAt().atZone(ZoneId.systemDefault())
                        .toString().replace('T', ' ').substring(0, 16));
        m.put("chiefComplaint", e.getChiefComplaint() == null ? "" : e.getChiefComplaint());
        m.put("assessment", e.getAssessment() == null ? "" : e.getAssessment());
        m.put("plan", e.getPlan() == null ? "" : e.getPlan());
        m.put("department", e.getDepartment() == null ? "" : e.getDepartment());
        m.put("urgency", e.getTriageUrgency() == null ? "routine" : e.getTriageUrgency());
        m.put("summary", e.getSummaryText() == null ? "" : e.getSummaryText());
        m.put("requiresPhysicianConfirmation", true);
        return ResponseEntity.ok(m);
    }

    @PostMapping("/consult/start")
    public ResponseEntity<Map<String, Object>> consultStart(@RequestBody Map<String, Object> body) {
        String ref = IdentityContext.scopedRef(str(body, "patientRef"));
        if (ref == null) {
            return forbidden(str(body, "patientRef"), "consult-start");
        }
        patients.ensureExists(ref);
        JsonNode r = cog.post("/api/consult/start", forwardBody(body, ref));
        auditForward("/api/consult/start", ref, body, r);
        return wrap(r);
    }

    @PostMapping("/consult/answer")
    public ResponseEntity<Map<String, Object>> consultAnswer(@RequestBody Map<String, Object> body) {
        String ref = IdentityContext.scopedRef(str(body, "patientRef"));
        if (ref == null) {
            return forbidden(str(body, "patientRef"), "consult-answer");
        }
        JsonNode r = cog.post("/api/consult/answer", forwardBody(body, ref));
        auditForward("/api/consult/answer", ref, body, r);
        return wrap(r);
    }

    @PostMapping("/consult/finish")
    public ResponseEntity<Map<String, Object>> consultFinish(@RequestBody Map<String, Object> body) {
        String ref = IdentityContext.scopedRef(str(body, "patientRef"));
        if (ref == null) {
            return forbidden(str(body, "patientRef"), "consult-finish");
        }
        JsonNode r = cog.post("/api/consult/finish", forwardBody(body, ref));
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
        String ref = IdentityContext.scopedRef(str(body, "patientRef"));
        if (ref == null) {
            return forbidden(str(body, "patientRef"), "lab-parse");
        }
        JsonNode r = cog.post("/api/lab/parse", forwardBody(body, ref));
        auditForward("/api/lab/parse", ref, body, r);
        return wrap(r);
    }

    @PostMapping("/lab/confirm")
    public ResponseEntity<Map<String, Object>> labConfirm(@RequestBody Map<String, Object> body) {
        String ref = IdentityContext.scopedRef(str(body, "patientRef"));
        if (ref == null) {
            return forbidden(str(body, "patientRef"), "lab-confirm");
        }
        JsonNode r = cog.post("/api/lab/confirm", forwardBody(body, ref));
        auditForward("/api/lab/confirm", ref, body, r);
        return wrap(r);
    }

    @PostMapping("/critical-fact")
    public ResponseEntity<Map<String, Object>> criticalFact(@RequestBody Map<String, Object> body) {
        String ref = IdentityContext.scopedRef(str(body, "patientRef"));
        if (ref == null) {
            return forbidden(str(body, "patientRef"), "critical-fact");
        }
        JsonNode r = cog.post("/api/critical-fact", forwardBody(body, ref));
        auditForward("/api/critical-fact", ref, body, r);
        return wrap(r);
    }

    // ------------------------------------------------------------ 内部

    /**
     * 越权留痕 + 403。
     *
     * <p>★ 越权尝试**必须留痕**，而且要在拒绝之前先记：它是数据泄露的前兆信号，
     * 也是 IDOR 探测唯一的证据来源。只返回 403 而不记，等于"挡住了但什么都没看见"。
     */
    private ResponseEntity<Map<String, Object>> forbidden(String requested, String action) {
        deny(requested, action);
        return ResponseEntity.status(403).body(Map.of(
                "error", "无权访问该患者的记录",
                "scope", IdentityContext.current().scopeLabel()));
    }

    /** 只留痕不返回 —— 给返回类型不是 Map 的接口用。 */
    private void deny(String requested, String action) {
        try {
            audit.recordRead(AuditService.currentActor(null), requested, "denied-" + action,
                    "requestedRef=" + requested
                            + ";scope=" + IdentityContext.current().scopeLabel());
        } catch (Exception ignored) {
            // 留痕失败不能反过来把 403 变成 500 —— 拒绝本身必须照常生效
        }
    }

    /**
     * 转发体的 patientRef **强制覆盖**为已收窄的 ref。
     *
     * <p>★ 为什么是"覆盖"而不是"校验后原样透传"：患者可以不传 ref（等于本人），
     * 原样透传就会让认知侧收到空 ref；而校验通过又原样透传，在大小写/空白不一致时
     * 会把请求参数直接带进下游存储。
     */
    private Map<String, Object> forwardBody(Map<String, Object> body, String ref) {
        Map<String, Object> out = body == null ? new HashMap<>() : new HashMap<>(body);
        out.put("patientRef", ref);
        return out;
    }

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
        // ★ 带上操作者：以前这条是空的，审计面板的"操作者"列常年空白 —— 那样"谁问的"就查不出来
        a.setActor(AuditService.currentActor(null));
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
