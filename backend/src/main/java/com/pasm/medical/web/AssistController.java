package com.pasm.medical.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.pasm.medical.cognition.PasmCognitionClient;
import com.pasm.medical.config.IdentityContext;
import com.pasm.medical.domain.AiAudit;
import com.pasm.medical.service.AuditService;
import com.pasm.medical.service.KnowledgeDocService;
import com.pasm.medical.service.PatientService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊疗辅助接口（给前端 Vue 用）。
 *
 * <p>三条硬约束落在这一层：
 * <ol>
 *   <li><b>每条响应都带 {@code requires_physician_confirmation=true}</b> —— 这是"辅助"定位的技术表达，
 *       前端据此渲染"需医师确认"标识，且未确认前不允许一键写入病历；</li>
 *   <li><b>依据必须原样返回</b>（{@code evidence}）—— 医生要能看到"系统凭哪几条说这话"；</li>
 *   <li><b>不产出处方</b>。{@code /check-herbs} 只做<b>规则核对</b>（配伍禁忌 / 妊娠 / 剂量上限），
 *       返回违例清单，最终处方权在医师。</li>
 * </ol>
 *
 * <p>注意 {@code /check-herbs} 不经过认知服务 —— 它是**确定性规则**，Python 侧实现见
 * {@code pasm_medical.safety}。把这类计算交给 LLM 是明确的红线。
 */
@RestController
@RequestMapping("/api/assist")
public class AssistController {

    private final PasmCognitionClient cognition;
    private final AuditService audit;
    private final PatientService patients;
    private final KnowledgeDocService kb;

    public AssistController(PasmCognitionClient cognition, AuditService audit,
                           PatientService patients, KnowledgeDocService kb) {
        this.cognition = cognition;
        this.audit = audit;
        this.patients = patients;
        this.kb = kb;
    }

    /**
     * 带依据的问答。
     *
     * <p>认知服务会先过**相关性闸门**：命中的词必须落在资料的标题或标签上。
     * 没有够格的依据时它**直接拒答**（{@code refused=true}），而不是给一个"像样"的答案 ——
     * 医疗场景里编造是最严重的失败。前端遇到 {@code refused} 要如实显示"查不到"。
     */
    @PostMapping("/ask")
    public ResponseEntity<Map<String, Object>> ask(@RequestBody AskRequest req) {
        if (req.question() == null || req.question().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question 不能为空"));
        }
        // ★ 先按身份收窄：患者可以不传 patientRef（等于本人），写了别人则 403。
        //   这里**不再**把"patientRef 为空"当 400 —— 那是要求前端替患者重复声明一遍身份。
        String ref = IdentityContext.scopedRef(req.patientRef());
        if (ref == null) {
            return forbidden(req.patientRef(), "ask");
        }
        patients.ensureExists(ref);
        int k = req.k() <= 0 ? 5 : req.k();
        // ★ 走医疗侧的「带闸门问答」(/api/answer)，而不是框架的 /api/cog/recall：
        //   ① 那条路只召回**患者记忆**、不含机构资料库 → 资料库对问答毫无影响（摆设）；
        //   ② 它**不经过相关性闸门** → "无依据必须拒答"在界面上形同虚设。
        //   docKeys 传业务库里"当前生效"的资料键：即使认知侧清理失败，
        //   已下架的资料也不会被当成依据（第二道保险）。
        JsonNode r = cognition.post("/api/answer", Map.of(
                "patientRef", ref,
                "question", req.question(),
                "k", k,
                "docKeys", kb.activeKeys()));
        List<Map<String, Object>> sources = new ArrayList<>();
        JsonNode arr = r.path("sources");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                Object plain = cognition.toPlain(n);
                if (plain instanceof Map<?, ?> mm) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    mm.forEach((key, v) -> c.put(String.valueOf(key), v));
                    sources.add(c);
                }
            }
        }
        boolean refused = r.path("refused").asBoolean(false);
        auditAsk(ref, req.question(), sources.size(), refused);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patientRef", ref);
        out.put("question", req.question());
        // 保持 evidence.hits 的形状（既有前端契约），另外单独给出 sources / refused
        out.put("evidence", Map.of("hits", sources));
        out.put("sources", sources);
        out.put("count", sources.size());
        out.put("refused", refused);
        out.put("refusalReason", r.path("refusal_reason").asText(""));
        out.put("message", r.path("text").asText(""));
        // 恒为 true —— 不要因为"看起来没风险"就放开
        out.put("requiresPhysicianConfirmation", true);
        out.put("disclaimer", "本结果由辅助系统基于既有资料生成，仅供医师参考，不构成诊断意见。");
        return ResponseEntity.ok(out);
    }

    /** 认知上下文（只读）：前端把 recalled 渲染成"系统记得的事"，供医生核对与纠错。 */
    @GetMapping("/context")
    public ResponseEntity<Map<String, Object>> context(
            @RequestParam(required = false) String patientRef,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int k) {
        String ref = IdentityContext.scopedRef(patientRef);
        if (ref == null) {
            return forbidden(patientRef, "context");
        }
        return ResponseEntity.ok(Map.of(
                "context", cognition.context(ref, query, k),
                "requiresPhysicianConfirmation", true));
    }

    /** 登记关键事实（过敏史 / 危急事件）。前端应在"保存病历"时同步调用。 */
    @PostMapping("/critical-fact")
    public ResponseEntity<Map<String, Object>> criticalFact(@RequestBody CriticalFactRequest req) {
        String ref = IdentityContext.scopedRef(req.patientRef());
        if (ref == null) {
            return forbidden(req.patientRef(), "critical-fact");
        }
        // salience=5：关键事实绝不能被后续闲聊挤出上下文（再次处方时可能致命）
        JsonNode r = cognition.observe(ref, req.title(), req.detail(),
                req.tags() == null ? List.of() : req.tags(), 5);
        patients.recordFacts(ref, req.title(), null);
        auditAct(ref, "critical-fact", 1, "cognition-observe",
                "title=" + req.title());
        return ResponseEntity.ok(Map.of("result", r, "salience", 5));
    }

    /**
     * 医生对一条 AI 输出的处置（采纳 / 修改 / 否决）。
     *
     * <p><b>这是最高质量的学习信号</b>：病历只记录结果，而"医生否决了这条建议"直接标出
     * 系统的错误。前端每次点击都要落到这里 —— 别只做 UI 状态。
     */
    @PostMapping("/feedback")
    public ResponseEntity<Map<String, Object>> feedback(@RequestBody FeedbackRequest req) {
        String ref = IdentityContext.scopedRef(req.patientRef());
        if (ref == null) {
            return forbidden(req.patientRef(), "feedback");
        }
        JsonNode r = cognition.feedback(ref, req.kind(), req.action());
        String action = "feedback-" + (req.action() == null ? "other" : req.action());
        auditAct(ref, action, null, "cognition-feedback",
                "kind=" + req.kind() + ";action=" + req.action());
        return ResponseEntity.ok(Map.of("result", r));
    }

    /** 认知服务健康状态（运维页 + 前端顶部状态条）。 */
    @GetMapping("/cognition-health")
    public ResponseEntity<Map<String, Object>> cognitionHealth() {
        return ResponseEntity.ok(Map.of("available", cognition.isAvailable()));
    }

    /**
     * 越权留痕 + 403（与 {@code MedicalProxyController} 同一套语义）。
     *
     * <p>★ 越权尝试要**先记再拒**：它是数据泄露的前兆，也是 IDOR 探测的唯一证据。
     */
    private ResponseEntity<Map<String, Object>> forbidden(String requested, String action) {
        try {
            audit.recordRead(AuditService.currentActor(null), requested, "denied-" + action,
                    "requestedRef=" + requested
                            + ";scope=" + IdentityContext.current().scopeLabel());
        } catch (Exception ignored) {
            // 留痕失败不能把 403 变成 500 —— 拒绝必须照常生效
        }
        return ResponseEntity.status(403).body(Map.of(
                "error", "无权访问该患者的记录",
                "scope", IdentityContext.current().scopeLabel()));
    }

    // ------------------------------------------------------------ 审计埋点

    private void auditAsk(String patientRef, String question, int evidenceCount, boolean refused) {
        AiAudit a = new AiAudit();
        a.setPatientRef(patientRef);
        a.setActor(AuditService.currentActor(null));
        a.setAction("ask");
        a.setEvidenceCount(evidenceCount);
        a.setModelVersion("cognition-gated-ask");
        a.setInputSnapshot("question=" + question);
        // ★ 必须记下"这次拒答了没有"：统计里的**拒答率**就是数这个字段
        //   （`countByActionAndRefusedTrue("ask")`）。以前从不设置它 → 分子恒为 0 →
        //   拒答率永远显示 0.0，一个"看着合理"的假数字。
        a.setRefused(refused);
        try { audit.record(a); } catch (Exception ignored) { }
    }

    private void auditAct(String patientRef, String action, Integer evidenceCount,
                          String model, String snapshot) {
        AiAudit a = new AiAudit();
        a.setPatientRef(patientRef);
        a.setActor(AuditService.currentActor(null));
        a.setAction(action);
        a.setEvidenceCount(evidenceCount);
        a.setModelVersion(model);
        a.setInputSnapshot(snapshot);
        try { audit.record(a); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ 请求体

    public record AskRequest(String patientRef, String question, int k) {
    }

    public record CriticalFactRequest(String patientRef, String title, String detail,
                                      List<String> tags) {
    }

    public record FeedbackRequest(String patientRef, String kind, String action) {
    }
}
