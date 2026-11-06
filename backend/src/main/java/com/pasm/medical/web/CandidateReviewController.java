package com.pasm.medical.web;

import com.pasm.medical.config.IdentityContext;
import com.pasm.medical.domain.KbCandidate;
import com.pasm.medical.service.AuditService;
import com.pasm.medical.service.KbCandidateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 自学习审核台（PLAN §8 候选池的 human-in-the-loop 出口）。
 *
 * <p>★ 授权分两层（与资料库同构）：
 * <ol>
 *   <li>URL 级（SecurityConfig）：
 *       {@code /candidates/generate} 与 {@code /kb-pool/rollback} 仅超管；
 *       其余 {@code /kb/candidates/**} 与 {@code /kb-pool/version} 限
 *       {@code DEPT_ADMIN} + {@code SUPER_ADMIN}。</li>
 *   <li>资源级（本控制器）：候选的科室必须属当前身份可管理范围
 *       （{@link IdentityContext#canManageDepartment}，空科室 = 全院通用，仅超管）。
 *       越权操作直接 403，不静默降级。</li>
 * </ol>
 *
 * <p>★ 这里是"学习生效"的唯一闸门：候选只有被本接口的 approve 采纳后，
 * 才会 upsert 进 knowledge_docs 并同步认知侧。任何"自动生效"的旁路都不要加。
 */
@RestController
@RequestMapping("/api/admin")
public class CandidateReviewController {

    private final KbCandidateService cand;
    private final AuditService audit;

    public CandidateReviewController(KbCandidateService cand, AuditService audit) {
        this.cand = cand;
        this.audit = audit;
    }

    /** 候选列表（审核台渲染）。status 缺省 = 待审 + 冲突 + 已采纳（不含已驳回）。 */
    @GetMapping("/kb/candidates")
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(required = false) String status) {
        String dept = IdentityContext.scopeDepartment();
        List<Map<String, Object>> rows = cand.list(status, dept).stream()
                .map(this::view).collect(Collectors.toList());
        return ResponseEntity.ok(rows);
    }

    /**
     * 触发每日批处理（生成候选）。仅超管（它会扫全库就诊、写候选池）。
     *
     * <p>★ 默认不自动跑（见 {@code medical.learning.auto-generate-candidates.enabled}）；
     * 这里是手动入口，也便于运维在"认知服务不可达 / 低峰期"主动触发。
     */
    @PostMapping("/kb/candidates/generate")
    public ResponseEntity<Map<String, Object>> generate(
            @RequestParam(defaultValue = "7") int days) {
        String actor = AuditService.currentActor(null);
        return ResponseEntity.ok(cand.generateFromRecent(days, actor));
    }

    /** 采纳候选 → 入库 + 知识池版本 +1 + 同步认知侧。 */
    @PostMapping("/kb/candidates/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable long id) {
        KbCandidate c = cand.get(id);
        if (c == null) {
            return ResponseEntity.notFound().build();
        }
        if (!IdentityContext.canManageDepartment(c.getDepartment())) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "只能审核本科室候选"));
        }
        String actor = AuditService.currentActor(null);
        try {
            return ResponseEntity.ok(cand.approve(id, actor));
        } catch (IllegalStateException ex) {
            // ★ 合规闸门单独用 422：与「已处理过」的 409 区分，前端能给出准确原因，
            //   e2e 也能精确断言「不是别的原因导致的失败」。
            if (ex.getMessage() != null
                    && ex.getMessage().startsWith("candidate_not_physician_confirmed:")) {
                return ResponseEntity.unprocessableEntity()
                        .body(Map.of("error", ex.getMessage(),
                                "code", "NOT_PHYSICIAN_CONFIRMED"));
            }
            return conflict(ex.getMessage());
        }
    }

    /** 驳回候选（绝不入库）。 */
    @PostMapping("/kb/candidates/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable long id) {
        KbCandidate c = cand.get(id);
        if (c == null) {
            return ResponseEntity.notFound().build();
        }
        if (!IdentityContext.canManageDepartment(c.getDepartment())) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "只能审核本科室候选"));
        }
        String actor = AuditService.currentActor(null);
        try {
            return ResponseEntity.ok(cand.reject(id, actor));
        } catch (IllegalStateException ex) {
            return conflict(ex.getMessage());
        }
    }

    /** 当前知识池版本信息（含是否可回滚）。 */
    @GetMapping("/kb-pool/version")
    public ResponseEntity<Map<String, Object>> version() {
        return ResponseEntity.ok(cand.poolVersionInfo());
    }

    /** 知识池回滚（停用上一版本引入的资料）。仅超管。 */
    @PostMapping("/kb-pool/rollback")
    public ResponseEntity<Map<String, Object>> rollback() {
        String actor = AuditService.currentActor(null);
        try {
            return ResponseEntity.ok(cand.rollback(actor));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(409)
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    // ------------------------------------------------------------ 内部

    private static ResponseEntity<Map<String, Object>> conflict(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return ResponseEntity.status(409).body(m);
    }

    private Map<String, Object> view(KbCandidate c) {
        Map<String, Object> m = KbCandidateService.toView(c);
        ZoneId z = ZoneId.systemDefault();
        m.put("createdAt", c.getCreatedAt() == null ? ""
                : c.getCreatedAt().atZone(z).toString().replace('T', ' ').substring(0, 16));
        m.put("reviewedAt", c.getReviewedAt() == null ? ""
                : c.getReviewedAt().atZone(z).toString().replace('T', ' ').substring(0, 16));
        return m;
    }
}
