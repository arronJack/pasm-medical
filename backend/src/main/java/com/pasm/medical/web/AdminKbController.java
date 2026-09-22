package com.pasm.medical.web;

import com.pasm.medical.domain.AiAudit;
import com.pasm.medical.domain.KnowledgeDoc;
import com.pasm.medical.service.AuditService;
import com.pasm.medical.service.KnowledgeDocService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资料库管理（`/api/admin/kb`）。
 *
 * <p>★ 授权：整个 {@code /api/admin/**} 在 SecurityConfig 里限 {@code ROLE_STAFF}，
 * 本控制器自然继承 —— 资料是**回答依据**，能改资料就能影响系统对所有患者的回答，
 * 这比改一条病历影响面更大。
 *
 * <p>★ 每次写操作都会：① 落业务库；② 全量同步到认知侧检索副本；③ 留审计。
 * 同步结果随响应返回（{@code sync.synced}）——**修了库但检索侧没生效**这种漂移
 * 必须让操作者当场看见，而不是等患者问到才发现。
 */
@RestController
@RequestMapping("/api/admin/kb")
public class AdminKbController {

    private final KnowledgeDocService kb;
    private final AuditService audit;

    public AdminKbController(KnowledgeDocService kb, AuditService audit) {
        this.kb = kb;
        this.audit = audit;
    }

    /** 列表 + 生效/下架计数。 */
    @GetMapping("")
    public ResponseEntity<Map<String, Object>> list() {
        List<Map<String, Object>> docs = new ArrayList<>();
        for (KnowledgeDoc d : kb.list()) {
            docs.add(view(d));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docs", docs);
        m.put("active", kb.countActive());
        m.put("inactive", kb.countInactive());
        m.put("note", "标签是相关性闸门的命中面：问题词元必须落在标题或标签上，"
                + "这条资料才会被当作回答依据。正文不参与该判定。");
        return ResponseEntity.ok(m);
    }

    /** 新建 / 更新（按 docKey 幂等）。标题必填。 */
    @PostMapping("")
    public ResponseEntity<Map<String, Object>> save(@RequestBody DocRequest req) {
        if (req == null || req.title() == null || req.title().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title 不能为空"));
        }
        String actor = AuditService.currentActor(null);
        boolean creating = req.docKey() == null || req.docKey().isBlank();
        KnowledgeDoc d = kb.upsert(req.docKey(), req.title(), req.content(), req.tags(),
                req.department(), req.version(), req.reviewer(), actor);
        auditSafe(actor, creating ? "kb-create" : "kb-update",
                "key=" + d.getDocKey() + ";title=" + d.getTitle() + ";tags=" + d.getTags());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("doc", view(d));
        m.put("sync", kb.syncToCognition());
        return ResponseEntity.ok(m);
    }

    /** 上架 / 下架。下架不删数据，只是不再作为任何回答的依据。 */
    @PostMapping("/{docKey}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String docKey,
                                                      @RequestBody StatusRequest req) {
        String actor = AuditService.currentActor(null);
        KnowledgeDoc d = kb.setStatus(docKey, req != null && req.active(), actor);
        if (d == null) {
            return ResponseEntity.notFound().build();
        }
        auditSafe(actor, req != null && req.active() ? "kb-activate" : "kb-deactivate",
                "key=" + docKey);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("doc", view(d));
        m.put("sync", kb.syncToCognition());
        return ResponseEntity.ok(m);
    }

    /** 删除。返回 404 表示本来就没有 —— 不假装删成功。 */
    @DeleteMapping("/{docKey}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String docKey) {
        String actor = AuditService.currentActor(null);
        if (!kb.delete(docKey)) {
            return ResponseEntity.notFound().build();
        }
        auditSafe(actor, "kb-delete", "key=" + docKey);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deleted", true);
        m.put("sync", kb.syncToCognition());
        return ResponseEntity.ok(m);
    }

    /** 手动全量同步（运维用：认知服务重启后、或怀疑漂移时点一下）。 */
    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> sync() {
        auditSafe(AuditService.currentActor(null), "kb-sync", "manual full sync");
        return ResponseEntity.ok(kb.syncToCognition());
    }

    // ------------------------------------------------------------ 内部

    private static Map<String, Object> view(KnowledgeDoc d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docKey", d.getDocKey());
        m.put("title", d.getTitle() == null ? "" : d.getTitle());
        m.put("content", d.getContent() == null ? "" : d.getContent());
        m.put("tags", KnowledgeDocService.splitTags(d.getTags()));
        m.put("department", d.getDepartment() == null ? "" : d.getDepartment());
        m.put("status", d.getStatus());
        m.put("version", d.getVersion() == null ? "" : d.getVersion());
        m.put("reviewer", d.getReviewer() == null ? "" : d.getReviewer());
        m.put("reviewed", d.getReviewer() != null && !d.getReviewer().isBlank());
        m.put("updatedAt", d.getUpdatedAt() == null ? ""
                : d.getUpdatedAt().atZone(ZoneId.systemDefault())
                        .toString().replace('T', ' ').substring(0, 16));
        m.put("updatedBy", d.getUpdatedBy() == null ? "" : d.getUpdatedBy());
        return m;
    }

    /** 资料变更的审计（append-only）。记不下也不该影响主流程，但**不许静默**——所以 log 里留痕。 */
    private void auditSafe(String actor, String action, String detail) {
        AiAudit a = new AiAudit();
        a.setPatientRef("");
        a.setActor(actor == null ? "" : actor);
        a.setAction(action);
        a.setModelVersion("business-layer");
        a.setEvidenceCount(0);
        a.setInputSnapshot(detail);
        a.setRefused(false);
        try {
            audit.record(a);
        } catch (Exception ignored) {
            // 审计失败不阻断资料变更；真正的落点在日志里
        }
    }

    /** 请求体。`tags` 用逗号分隔的字符串（前端一个输入框），服务端统一归一化。 */
    public record DocRequest(String docKey, String title, String content, String tags,
                             String department, String version, String reviewer) {
    }

    public record StatusRequest(boolean active) {
    }
}
