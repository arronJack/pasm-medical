package com.pasm.medical.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.pasm.medical.cognition.PasmCognitionClient;
import com.pasm.medical.config.IdentityContext;
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
 * <p>★ 授权分**两层**，缺一层都不够：
 * <ol>
 *   <li>{@code SecurityConfig} 限制访客：{@code /api/admin/**} 只给医护侧角色，
 *       患者令牌一律 403；其中改配置与资料库**仅超管**（它们决定患者数据会不会出网、
 *       以及全院的答案依据）。</li>
 *   <li>本层再按身份收窄**数据范围**：超管看全院；医护与科室管理员看**本科室**
 *       （见 {@link IdentityContext#scopeDepartment}）。少了第二层，
 *       "能进这个接口"就等价于"能看全院所有患者的过敏史"。</li>
 * </ol>
 *
 * <p>⚠️ <b>如实标注</b>：{@code /stats} 目前仍是**全院口径**（科室维度统计尚未实现），
 * 因此它对科室角色会显式带上 {@code scope} 与 {@code scopeNote} ——
 * 标清口径，而不是假装那些数字是本科室的。
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
        // ★ 数据范围：超管 dept=null（全院）；医护/科室管理员 = 本科室出现过的患者
        String dept = IdentityContext.scopeDepartment();
        List<String> inScope = dept == null ? null : encounters.patientRefsInDepartment(dept);
        // 读审计：患者名单比看单个患者档案更敏感 —— 谁拉过这份名单必须留痕
        audit.recordRead(AuditService.currentActor(null), "", "read-admin-patients",
                "scope=" + (dept == null ? "all" : "department:" + dept));
        ZoneId z = ZoneId.systemDefault();
        var out = patients.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(p -> inScope == null || inScope.contains(p.getRef()))
                .map(p -> {
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

    /**
     * 审计（只增不改）：最近 200 条。
     *
     * <p>★ 必须把 {@code inputSnapshot} 也返回：问诊的问题原文就存在这里
     * （{@code AssistController} 写的是 {@code question=…}）。以前接口不返回这个字段，
     * 于是"后台看不到患者问了什么"—— 数据在库里躺着，界面却是空的。
     *
     * <p>快照可能是整段配置或整句提问，统一截到 {@value #SNAPSHOT_LIMIT} 字再下发，
     * 避免一条记录把整个列表撑大；截断会加省略号，不假装是全文。
     *
     * <p>★ 范围：超管 = 全院最近 200 条；医护/科室管理员 = **本科室患者**相关的记录。
     * 因此登录/登出/改配置这类没有 {@code patientRef} 的事件对科室角色不可见 ——
     * 它们不属于"本科室患者数据"，要看它们请用超管账号。
     */
    @GetMapping("/audit")
    public ResponseEntity<List<Map<String, Object>>> audit() {
        ZoneId z = ZoneId.systemDefault();
        // ★ 在**数据库**里筛，不要"取最近 200 条再在内存里过滤"：
        //   本科室事件不落在那 200 条里时，界面会静默变空
        String dept = IdentityContext.scopeDepartment();
        List<AiAudit> rows;
        if (dept == null) {
            rows = audits.findTop200ByOrderByOccurredAtDesc();
        } else {
            List<String> inScope = encounters.patientRefsInDepartment(dept);
            // ★ 本科室没有患者时返回空，**不能**退化成"查全部" —— 那是把越权包装成正常返回
            rows = inScope.isEmpty()
                    ? List.of()
                    : audits.findTop200ByPatientRefInOrderByOccurredAtDesc(inScope);
        }
        var out = rows.stream().map(a -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            // id 给前端当列表 key —— 用「时间+动作」当 key 在同一分钟内必然撞车（读审计尤其频繁）
            m.put("id", a.getId());
            m.put("time", a.getOccurredAt().atZone(z).toString().replace('T', ' ').substring(0, 16));
            m.put("ref", a.getPatientRef() == null ? "" : a.getPatientRef());
            m.put("actor", a.getActor() == null ? "" : a.getActor());
            m.put("action", a.getAction());
            m.put("evidenceCount", a.getEvidenceCount() == null ? 0 : a.getEvidenceCount());
            m.put("modelVersion", a.getModelVersion() == null ? "" : a.getModelVersion());
            m.put("refused", Boolean.TRUE.equals(a.getRefused()));
            String snap = a.getInputSnapshot() == null ? "" : a.getInputSnapshot();
            boolean cut = snap.length() > SNAPSHOT_LIMIT;
            m.put("inputSnapshot", cut ? snap.substring(0, SNAPSHOT_LIMIT) + "…" : snap);
            m.put("inputTruncated", cut);
            return m;
        }).toList();
        return ResponseEntity.ok(out);
    }

    /** 审计快照下发时的截断长度。 */
    private static final int SNAPSHOT_LIMIT = 300;

    /**
     * 运营统计。
     *
     * <p>★ <b>口径（每个数字都要说得出它数的是什么，否则就是一张好看的假表）</b>：
     * <ul>
     *   <li><b>一次问诊 = 一条 {@code consult-start} 审计事件。</b>
     *       不是 {@code ask} —— 一次问诊要问十几轮，按问题数算会虚高好几倍；
     *       也不是 {@code consult-finish} —— 红旗中断或患者直接关页面就没有 finish，按它算会漏掉。</li>
     *   <li><b>今日窗口</b> = 本机时区的当天 00:00 起（窗口起点随响应一起返回，便于核对）。</li>
     *   <li><b>率类指标</b>：拒答率用今日窗口（分子分母同窗口）；采纳率用累计
     *       —— 一天的医生反馈样本太小，天天在 0% / 100% 之间跳没有意义。</li>
     * </ul>
     *
     * <p>⚠️ 旧实现是「取最近 200 条审计 → 在内存里筛今天、把 ask 与 consult-finish 都算一次」，
     * 有两个错：审计超过 200 条就**静默封顶**，且一次问诊被重复计数。修好后有断言盯着这条
     * （{@code tools/e2e_stack.py} 先灌 250 条审计，再起一次问诊，要求计数**精确 +1**）。
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        ZoneId z = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(z);
        Instant from = today.atStartOfDay(z).toInstant();

        long consultStartedToday =
                audits.countByActionAndOccurredAtGreaterThanEqual("consult-start", from);
        long consultFinishedToday =
                audits.countByActionAndOccurredAtGreaterThanEqual("consult-finish", from);
        long redFlagsToday =
                audits.countByActionAndOccurredAtGreaterThanEqual("consult-redflag", from);
        long questionsToday = audits.countByActionAndOccurredAtGreaterThanEqual("ask", from);
        long refusalsToday =
                audits.countByActionAndRefusedTrueAndOccurredAtGreaterThanEqual("ask", from);

        long consultStartedTotal = audits.countByAction("consult-start");
        long redFlagsTotal = audits.countByAction("consult-redflag");
        long questionsTotal = audits.countByAction("ask");
        long refusalsTotal = audits.countByActionAndRefusedTrue("ask");
        long adopt = audits.countByAction("feedback-adopt");
        long reject = audits.countByAction("feedback-reject");

        Map<String, Object> m = new java.util.LinkedHashMap<>();
        // 窗口本身也要返回：数字脱离窗口就无法核对
        m.put("zone", z.getId());
        m.put("windowFrom", from.atZone(z).toString());
        m.put("windowTo", Instant.now().atZone(z).toString());
        // ★ 数据范围同理必须返回。科室维度统计尚未实现（P3-13），
        //   所以这里如实标成全院口径，而不是让科室角色把全院数字当成自己的
        String dept = IdentityContext.scopeDepartment();
        m.put("scope", dept == null ? "hospital" : "department:" + dept);
        m.put("scopeNote", dept == null
                ? "全院口径"
                : "当前为全院累计口径；科室维度统计尚未实现（见 docs/GUIDE.md 未实现清单）");
        // 今日
        m.put("consultationsToday", consultStartedToday);
        m.put("consultationsFinishedToday", consultFinishedToday);
        m.put("redFlagsToday", redFlagsToday);
        m.put("questionsToday", questionsToday);
        m.put("refusalsToday", refusalsToday);
        m.put("refusalRateToday", ratio(refusalsToday, questionsToday));
        // 累计
        m.put("consultationsTotal", consultStartedTotal);
        m.put("redFlagsTotal", redFlagsTotal);
        m.put("questionsTotal", questionsTotal);
        m.put("refusalRateTotal", ratio(refusalsTotal, questionsTotal));
        m.put("feedbackTotal", adopt + reject);
        m.put("adoptionRateTotal", ratio(adopt, adopt + reject));
        // 口径随数据一起给出去，避免前端各写一版说明、各理解一套
        Map<String, String> defs = new java.util.LinkedHashMap<>();
        defs.put("consultation", "一次问诊 = 一条 consult-start 审计事件（不是 ask，也不是 finish）");
        defs.put("todayWindow", "本机时区当天 00:00 起（见 windowFrom）");
        defs.put("refusalRate", "今日窗口内 无依据拒答的问题数 / 今日全部问题数");
        defs.put("adoptionRate", "累计 采纳 / （采纳 + 否决）；用累计是因为一天的反馈样本太小");
        m.put("definitions", defs);
        return ResponseEntity.ok(m);
    }

    /** 保留两位小数的比率。分母为 0 时返回 0 —— 不能返回 NaN，JSON 里的 NaN 会让前端解析失败。 */
    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return Math.round((double) numerator / denominator * 100) / 100.0;
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
