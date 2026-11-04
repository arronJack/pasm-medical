package com.pasm.medical.service;

import com.pasm.medical.config.IdentityContext;
import com.pasm.medical.domain.AiAudit;
import com.pasm.medical.domain.Encounter;
import com.pasm.medical.domain.KbCandidate;
import com.pasm.medical.domain.KnowledgeDoc;
import com.pasm.medical.domain.SystemConfig;
import com.pasm.medical.repository.EncounterRepository;
import com.pasm.medical.repository.KbCandidateRepository;
import com.pasm.medical.repository.SystemConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 自学习候选知识池服务（PLAN §8 自学习闭环的「候选池 + 审核台 + 版本回滚」）。
 *
 * <p>★ 设计边界（铁律不可破）：
 * <ul>
 *   <li><b>候选永远不直接生效</b>。每日批处理只把"从就诊里抽出的经验"写进候选池，
 *       审核台通过后才 upsert 进 {@link KnowledgeDoc}（业务库权威）。</li>
 *   <li><b>版本化 + 可回滚</b>。每次采纳都把知识池版本 +1，批准进库的资料打上该版本号；
 *       回滚 = 停用"上一版本引入"的资料（下架但不删，可审计）。</li>
 *   <li><b>冲突挂起不覆盖</b>。候选与现库同标题但结论相反 → 记为 {@code CONFLICT}，
 *       不自动覆盖现库，交给人工裁定。</li>
 *   <li><b>禁止在线改权重</b>。本服务只动资料库，绝不碰模型 / 记忆权重（那是 L3，第一版不做）。</li>
 * </ul>
 *
 * <p>★ {@code confidence} 字段是**抽取完整度启发式评分**（0~1），用于辅助审核排序，
 * <b>不是</b>临床确定性，也<b>不</b>参与任何诊断/依据判定 —— 避免被误解为"系统算出的可信度"。
 */
@Service
public class KbCandidateService {

    private static final Logger log = LoggerFactory.getLogger(KbCandidateService.class);

    /** 知识池版本号存在 system_config（与机构对接设置同库同源）。 */
    private static final String POOL_VERSION_KEY = "kb_pool_version";

    /** 单次批处理最多新生成多少条，避免极端情况下把库刷爆。 */
    private static final int MAX_PER_RUN = 50;

    private final KbCandidateRepository candidates;
    private final KnowledgeDocService kb;
    private final EncounterRepository encounters;
    private final SystemConfigRepository configs;
    private final AuditService audit;
    private final boolean autoGenerate;

    public KbCandidateService(KbCandidateRepository candidates, KnowledgeDocService kb,
                             EncounterRepository encounters, SystemConfigRepository configs,
                             AuditService audit,
                             @Value("${medical.learning.auto-generate-candidates.enabled:false}")
                             boolean autoGenerate) {
        this.candidates = candidates;
        this.kb = kb;
        this.encounters = encounters;
        this.configs = configs;
        this.audit = audit;
        this.autoGenerate = autoGenerate;
    }

    // ------------------------------------------------------------ 每日批处理（生成候选）

    /**
     * 从近 {@code days} 天的就诊里抽取"可沉淀为资料"的经验，写入候选池。
     *
     * <p>流程：抽就诊 → 合成候选（标题=主诉，正文=判断+处置）→ 指纹去重 → 与现库冲突检测
     * → 落库为 {@code PENDING}（或 {@code CONFLICT}）。<b>不</b>触碰 knowledge_docs。
     *
     * @return 报告：generated / deduplicated / conflict / pendingTotal / ids
     */
    @Transactional
    public Map<String, Object> generateFromRecent(int days, String actor) {
        int window = Math.max(1, Math.min(365, days));
        Instant from = Instant.now().minus(java.time.Duration.ofDays(window));
        List<Encounter> recent = encounters.findByOccurredAtGreaterThanEqual(from);

        List<KnowledgeDoc> active = kb.listActive();

        int generated = 0;
        int dedup = 0;
        int conflict = 0;
        java.util.List<Long> ids = new java.util.ArrayList<>();

        for (Encounter e : recent) {
            if (generated >= MAX_PER_RUN) {
                break;
            }
            String title = e.getChiefComplaint() == null ? "" : e.getChiefComplaint().trim();
            if (title.isEmpty()) {
                continue;
            }
            String assessment = e.getAssessment() == null ? "" : e.getAssessment().trim();
            String plan = e.getPlan() == null ? "" : e.getPlan().trim();
            String summary = e.getSummaryText() == null ? "" : e.getSummaryText().trim();

            StringBuilder body = new StringBuilder();
            if (!assessment.isEmpty()) {
                body.append("判断：").append(assessment).append("；");
            }
            if (!plan.isEmpty()) {
                body.append("处置：").append(plan);
            }
            if (body.length() == 0) {
                body.append(summary);
            }
            if (body.length() == 0) {
                continue; // 没有任何可沉淀内容，跳过
            }
            String content = body.toString();
            String department = e.getDepartment() == null ? "" : e.getDepartment().trim();
            String tags = (department.isEmpty() ? "" : department + ",") + "经验";
            double confidence = 0.5
                    + (assessment.isEmpty() ? 0 : 0.2)
                    + (plan.isEmpty() ? 0 : 0.2);
            confidence = Math.min(0.95, confidence);

            String fingerprint = fingerprintOf(title, content);

            // ★ 指纹去重：同一经验不重复入池
            if (candidates.findByFingerprint(fingerprint).isPresent()) {
                dedup++;
                continue;
            }

            // ★ 冲突检测：现库同标题但结论相反 → 挂起，不覆盖
            String conflictWith = null;
            for (KnowledgeDoc d : active) {
                if (normalize(d.getTitle()).equals(normalize(title))
                        && !fingerprintOf(d.getTitle(), d.getContent()).equals(fingerprint)) {
                    conflictWith = d.getDocKey();
                    break;
                }
            }

            KbCandidate c = new KbCandidate();
            c.setTitle(title);
            c.setContent(content);
            c.setTags(tags);
            c.setDepartment(department);
            c.setSource("daily-batch");
            c.setStatus(conflictWith == null ? KbCandidate.PENDING : KbCandidate.CONFLICT);
            c.setConfidence(confidence);
            c.setConflictWith(conflictWith);
            c.setEvidence("source=encounter;ref=" + e.getPatientRef() + ";id=" + e.getId());
            c.setFingerprint(fingerprint);
            candidates.save(c);

            if (conflictWith != null) {
                conflict++;
            }
            generated++;
            ids.add(c.getId());
        }

        audit("candidate-generate", actor,
                "days=" + window + ";generated=" + generated + ";dedup=" + dedup
                        + ";conflict=" + conflict);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generated", generated);
        report.put("deduplicated", dedup);
        report.put("conflict", conflict);
        report.put("pendingTotal", candidates.countByStatus(KbCandidate.PENDING)
                + candidates.countByStatus(KbCandidate.CONFLICT));
        report.put("ids", ids);
        return report;
    }

    /** 每日 02:00 自动生成候选（默认关闭，由 {@code medical.learning.auto-generate-candidates.enabled} 开关）。 */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void scheduledGenerate() {
        if (!autoGenerate) {
            return;
        }
        try {
            Map<String, Object> r = generateFromRecent(1, "scheduler");
            log.info("每日候选生成完成：{}", r);
        } catch (Exception ex) {
            // ★ 批处理失败不应拖垮主进程；记日志，等明天再跑
            log.warn("每日候选生成失败", ex);
        }
    }

    // ------------------------------------------------------------ 审核台读取

    /**
     * 候选列表（审核台渲染用）。
     *
     * @param status 过滤状态；{@code null} = 待审 + 冲突 + 已采纳（不含已驳回）。
     * @param dept   数据范围：{@code null} = 全院（超管）；否则只返回该科室的候选
     *              （空科室候选对科室管理员不可见，与资料库同规则）。
     */
    public List<KbCandidate> list(String status, String dept) {
        List<KbCandidate> all;
        if (status != null && !status.isBlank()) {
            all = candidates.findByStatusOrderByCreatedAtDesc(status);
        } else {
            all = candidates.findByStatusNotOrderByCreatedAtDesc(KbCandidate.REJECTED);
        }
        if (dept == null) {
            return all;
        }
        final String scope = dept;
        return all.stream()
                .filter(c -> c.getDepartment() != null && c.getDepartment().equals(scope))
                .toList();
    }

    /** 按 id 取候选（审核前的权限校验要用到它的科室）。 */
    public KbCandidate get(long id) {
        return candidates.findById(id).orElse(null);
    }

    // ------------------------------------------------------------ 审核：采纳 / 驳回

    /**
     * 采纳候选：upsert 进 knowledge_docs + 知识池版本 +1 + 同步认知侧 + 审计。
     *
     * @throws IllegalStateException 候选不存在 / 状态不是 PENDING 或 CONFLICT（已处理过）
     */
    @Transactional
    public Map<String, Object> approve(long id, String actor) {
        KbCandidate c = candidates.findById(id).orElse(null);
        if (c == null) {
            throw new IllegalStateException("candidate_not_found");
        }
        if (!KbCandidate.PENDING.equals(c.getStatus())
                && !KbCandidate.CONFLICT.equals(c.getStatus())) {
            throw new IllegalStateException("candidate_already_processed:" + c.getStatus());
        }

        int newVersion = bumpPoolVersion();
        String reviewer = actor == null ? "" : actor;
        KnowledgeDoc d = kb.upsert(c.getDocKey(), c.getTitle(), c.getContent(), c.getTags(),
                c.getDepartment(), String.valueOf(newVersion), reviewer, reviewer);
        Map<String, Object> sync = kb.syncToCognition();

        c.setStatus(KbCandidate.APPROVED);
        c.setPoolVersion(String.valueOf(newVersion));
        c.setReviewedAt(Instant.now());
        c.setReviewedBy(reviewer);
        candidates.save(c);

        audit("candidate-approve", actor,
                "id=" + id + ";title=" + c.getTitle() + ";docKey=" + d.getDocKey()
                        + ";poolVersion=" + newVersion);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("docKey", d.getDocKey());
        out.put("poolVersion", newVersion);
        out.put("sync", sync);
        out.put("candidate", toView(c));
        return out;
    }

    /** 驳回候选：只记状态，绝不入库。 */
    @Transactional
    public Map<String, Object> reject(long id, String actor) {
        KbCandidate c = candidates.findById(id).orElse(null);
        if (c == null) {
            throw new IllegalStateException("candidate_not_found");
        }
        if (!KbCandidate.PENDING.equals(c.getStatus())
                && !KbCandidate.CONFLICT.equals(c.getStatus())) {
            throw new IllegalStateException("candidate_already_processed:" + c.getStatus());
        }
        c.setStatus(KbCandidate.REJECTED);
        c.setReviewedAt(Instant.now());
        c.setReviewedBy(actor == null ? "" : actor);
        candidates.save(c);

        audit("candidate-reject", actor, "id=" + id + ";title=" + c.getTitle());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("candidate", toView(c));
        return out;
    }

    // ------------------------------------------------------------ 知识池版本 / 回滚

    /** 当前知识池版本信息：version / 是否可回滚 / 当前版本下资料数。 */
    public Map<String, Object> poolVersionInfo() {
        int v = readPoolVersion();
        long docsAtVersion = kb.countByVersion(String.valueOf(v));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", v);
        m.put("rollbackAvailable", v > 1);
        m.put("docsAtVersion", docsAtVersion);
        return m;
    }

    /**
     * 知识池回滚：停用"上一版本引入"的资料（下架但不删），版本号回退 1。
     *
     * @throws IllegalStateException 已是最初版本（无法再回退）
     */
    @Transactional
    public Map<String, Object> rollback(String actor) {
        int v = readPoolVersion();
        if (v <= 1) {
            throw new IllegalStateException("already_at_initial_version");
        }
        int deactivated = kb.deactivateByVersion(String.valueOf(v), actor);
        int to = v - 1;
        setPoolVersion(to);
        Map<String, Object> sync = kb.syncToCognition();

        audit("kb-pool-rollback", actor, "from=" + v + ";to=" + to + ";deactivated=" + deactivated);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("from", v);
        m.put("to", to);
        m.put("deactivated", deactivated);
        m.put("sync", sync);
        return m;
    }

    // ------------------------------------------------------------ 内部

    /** 视图映射（给前端审核台用）。 */
    public static Map<String, Object> toView(KbCandidate c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("docKey", c.getDocKey() == null ? "" : c.getDocKey());
        m.put("title", c.getTitle() == null ? "" : c.getTitle());
        m.put("content", c.getContent() == null ? "" : c.getContent());
        m.put("tags", KnowledgeDocService.splitTags(c.getTags()));
        m.put("department", c.getDepartment() == null ? "" : c.getDepartment());
        m.put("source", c.getSource() == null ? "" : c.getSource());
        m.put("status", c.getStatus());
        m.put("confidence", c.getConfidence());
        m.put("conflictWith", c.getConflictWith() == null ? "" : c.getConflictWith());
        m.put("evidence", c.getEvidence() == null ? "" : c.getEvidence());
        m.put("poolVersion", c.getPoolVersion() == null ? "" : c.getPoolVersion());
        m.put("reviewedBy", c.getReviewedBy() == null ? "" : c.getReviewedBy());
        return m;
    }

    private void audit(String action, String actor, String detail) {
        try {
            AiAudit a = new AiAudit();
            a.setPatientRef("");
            a.setActor(actor == null ? "" : actor);
            a.setAction(action);
            a.setModelVersion("business-layer");
            a.setEvidenceCount(0);
            a.setInputSnapshot(detail);
            a.setRefused(false);
            audit.record(a);
        } catch (Exception ignored) {
            // 审计失败不阻断主流程
        }
    }

    // ---- 知识池版本号（存 system_config）----

    private int readPoolVersion() {
        return configs.findById(POOL_VERSION_KEY)
                .map(c -> parseIntSafe(c.getValue())).orElse(1);
    }

    private int bumpPoolVersion() {
        int next = readPoolVersion() + 1;
        writePoolVersion(next);
        return next;
    }

    private void setPoolVersion(int v) {
        writePoolVersion(v);
    }

    private void writePoolVersion(int v) {
        SystemConfig c = configs.findById(POOL_VERSION_KEY).orElseGet(() -> {
            SystemConfig n = new SystemConfig();
            n.setKey(POOL_VERSION_KEY);
            return n;
        });
        c.setValue(String.valueOf(v));
        c.setUpdatedAt(Instant.now());
        c.setUpdatedBy("system");
        configs.save(c);
    }

    // ---- 指纹 / 归一化 ----

    private static String fingerprintOf(String title, String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String s = normalize(title) + " " + normalize(content);
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 归一化：去首尾空白、合并内部空白、转小写 —— 用于标题比较与指纹。 */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static int parseIntSafe(String s) {
        if (s == null) {
            return 1;
        }
        try {
            int v = Integer.parseInt(s.trim());
            return v < 1 ? 1 : v;
        } catch (NumberFormatException ex) {
            return 1;
        }
    }
}
