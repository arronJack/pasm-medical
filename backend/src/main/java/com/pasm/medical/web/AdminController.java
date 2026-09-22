package com.pasm.medical.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.pasm.medical.cognition.PasmCognitionClient;
import com.pasm.medical.domain.AiAudit;
import com.pasm.medical.domain.Encounter;
import com.pasm.medical.domain.Patient;
import com.pasm.medical.repository.AuditRepository;
import com.pasm.medical.repository.EncounterRepository;
import com.pasm.medical.repository.PatientRepository;
import com.pasm.medical.service.AuditService;
import com.pasm.medical.service.SystemConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 后台数据接口 —— 给前端 AdminView 的「患者情况 / 审计 / 统计 / 对接设置」提供**真实数据**。
 *
 * <p>之前这些面板是前端硬编码的占位。现在从业务库（JPA）实时读取，
 * 与「诊断辅助」链路同源，不再是假数据。
 *
 * <p>★ 授权：整个 {@code /api/admin/**} 在 {@link com.pasm.medical.config.SecurityConfig}
 * 里被限制为 {@code ROLE_STAFF}。这不是可选项 —— 这些接口能读出全院患者的过敏史，
 * 还能改「大模型指向何处」（决定患者数据会不会出网）。患者令牌必须拿不到。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final PatientRepository patients;
    private final EncounterRepository encounters;
    private final AuditRepository audits;
    private final SystemConfigService configs;
    private final PasmCognitionClient cog;
    private final AuditService audit;

    public AdminController(PatientRepository patients, EncounterRepository encounters,
                           AuditRepository audits, SystemConfigService configs,
                           PasmCognitionClient cog, AuditService audit) {
        this.patients = patients;
        this.encounters = encounters;
        this.audits = audits;
        this.configs = configs;
        this.cog = cog;
        this.audit = audit;
    }

    /** 患者情况：每位患者最新一次就诊 + 分诊 + 就诊次数。 */
    @GetMapping("/patients")
    public ResponseEntity<List<Map<String, Object>>> patients() {
        ZoneId z = ZoneId.systemDefault();
        var out = patients.findTop100ByOrderByCreatedAtDesc().stream().map(p -> {
            var last = encounters.findByPatientRefOrderByOccurredAtDesc(p.getRef())
                    .stream().findFirst().orElse(null);
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("ref", p.getRef());
            m.put("name", p.getName());
            m.put("allergy", p.getAllergy() == null ? "" : p.getAllergy());
            m.put("chronic", p.getChronic() == null ? "" : p.getChronic());
            m.put("encounterCount", encounters.countByPatientRef(p.getRef()));
            if (last != null) {
                m.put("last", last.getOccurredAt().atZone(z).toString().replace('T', ' ').substring(0, 16));
                m.put("dept", last.getDepartment() == null ? "" : last.getDepartment());
                m.put("urgency", last.getTriageUrgency() == null ? "routine" : last.getTriageUrgency());
            } else {
                m.put("last", "");
                m.put("dept", "");
                m.put("urgency", "routine");
            }
            return m;
        }).toList();
        return ResponseEntity.ok(out);
    }

    /** 审计（只增不改）：最近 200 条。 */
    @GetMapping("/audit")
    public ResponseEntity<List<Map<String, Object>>> audit() {
        ZoneId z = ZoneId.systemDefault();
        var out = audits.findTop200ByOrderByOccurredAtDesc().stream().map(a -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("time", a.getOccurredAt().atZone(z).toString().replace('T', ' ').substring(0, 16));
            m.put("ref", a.getPatientRef() == null ? "" : a.getPatientRef());
            m.put("actor", a.getActor() == null ? "" : a.getActor());
            m.put("action", a.getAction());
            m.put("evidenceCount", a.getEvidenceCount() == null ? 0 : a.getEvidenceCount());
            m.put("modelVersion", a.getModelVersion() == null ? "" : a.getModelVersion());
            m.put("refused", Boolean.TRUE.equals(a.getRefused()));
            return m;
        }).toList();
        return ResponseEntity.ok(out);
    }

    /** 运营统计：今日问诊量 / 红旗命中 / 拒答率 / 采纳率。 */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        List<AiAudit> recent = audits.findTop200ByOrderByOccurredAtDesc();
        LocalDate today = LocalDate.now();
        ZoneId z = ZoneId.systemDefault();
        long consultToday = recent.stream().filter(a -> {
            String act = a.getAction();
            if (!"ask".equals(act) && !"consult-finish".equals(act)) {
                return false;
            }
            return a.getOccurredAt().atZone(z).toLocalDate().equals(today);
        }).count();
        long redFlags = audits.countByAction("consult-redflag");
        long askTotal = audits.countByAction("ask");
        long askRefused = audits.countByActionAndRefusedTrue("ask");
        long adopt = audits.countByAction("feedback-adopt");
        long reject = audits.countByAction("feedback-reject");
        double refusalRate = askTotal > 0 ? (double) askRefused / askTotal : 0.0;
        double adoptionRate = (adopt + reject) > 0 ? (double) adopt / (adopt + reject) : 0.0;

        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("consultToday", consultToday);
        m.put("redFlags", redFlags);
        m.put("refusalRate", Math.round(refusalRate * 100) / 100.0);
        m.put("adoptionRate", Math.round(adoptionRate * 100) / 100.0);
        m.put("askTotal", askTotal);
        m.put("feedbackTotal", adopt + reject);
        return ResponseEntity.ok(m);
    }

    // ─────────────────────────────────────────── 对接设置（可读可写）

    /**
     * 读取对接设置，并**与认知服务进程的实际值对账**。
     *
     * <p>★ 为什么要对账：本表存的是"机构期望值"，真正跑模型的在 Python 侧
     * （由 {@code PASM_MEDICAL_LLM*} 环境变量决定）。两边不一致时，界面会一直显示
     * "已配置"，而患者实际拿到的是模板话术 —— 这种假象比没有配置项更有害。
     * 所以这里把认知服务的实际 provider/model 一并取回，由 {@code drift} 明示是否漂移。
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config() {
        return ResponseEntity.ok(configView(configs.read()));
    }

    /**
     * 保存对接设置（幂等 upsert），并留审计。
     *
     * <p>取值校验在 {@link SystemConfigService} 里做白名单 —— 前端下拉框不是安全边界。
     * 非法值返回 400，**不静默改成默认值**（那会让管理员以为改成功了）。
     */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody ConfigRequest req,
                                                          Authentication auth) {
        String actor = auth == null ? "" : String.valueOf(auth.getName());
        SystemConfigService.Settings saved;
        try {
            saved = configs.save(new SystemConfigService.Settings(
                    req.ocr(), req.lis(), req.llm(), req.model(), req.baseUrl()), actor);
        } catch (IllegalArgumentException ex) {
            // 400 而不是 500：这是调用方传错了，不是服务坏了
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(ex.getMessage())));
        }
        auditConfig(actor, saved);
        return ResponseEntity.ok(configView(saved));
    }

    /** 请求体。字段名与前端 api.ts 的 adminConfig 契约一一对应。 */
    public record ConfigRequest(String ocr, String lis, String llm, String model, String baseUrl) {
    }

    /** 组装「期望 + 实际 + 漂移」视图。认知服务不可达时 applied=null、drift=null（未知，不猜）。 */
    private Map<String, Object> configView(SystemConfigService.Settings desired) {
        Map<String, Object> applied = null;
        try {
            JsonNode n = cog.get("/api/config", null);
            if (n != null && !n.isMissingNode() && !n.isNull()) {
                Object plain = cog.toPlain(n);
                if (plain instanceof Map<?, ?> mm) {
                    Map<String, Object> conv = new java.util.LinkedHashMap<>();
                    mm.forEach((k, v) -> conv.put(String.valueOf(k), v));
                    applied = conv;
                }
            }
        } catch (Exception ex) {
            // 认知服务不可达不是错误：后台仍要能打开（否则改配置要看运气）
            applied = null;
        }
        Instant at = configs.lastUpdatedAt();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("desired", desired.asMap());
        m.put("updatedAt", at == null ? "" : at.atZone(ZoneId.systemDefault())
                .toString().replace('T', ' ').substring(0, 16));
        m.put("updatedBy", configs.lastUpdatedBy());
        m.put("applied", applied);
        m.put("drift", configs.drift(desired, applied));
        m.put("note", "期望值由业务层持久化；``applied`` 是认知服务进程实际生效的配置。"
                + "两者不一致时 drift=true —— 此时界面显示的配置并未真正生效。"
                + "密钥一律走环境变量，故此处不回显 api_key。");
        return m;
    }

    private void auditConfig(String actor, SystemConfigService.Settings s) {
        AiAudit a = new AiAudit();
        a.setPatientRef("");
        a.setAction("config-update");
        a.setActor(actor);
        a.setModelVersion("system-config");
        a.setEvidenceCount(0);
        a.setInputSnapshot("ocr=" + s.ocr() + ";lis=" + s.lis() + ";llm=" + s.llm()
                + ";model=" + s.model() + ";baseUrl=" + s.baseUrl());
        try { audit.record(a); } catch (Exception ignored) { }
    }
}
