package com.pasm.medical.service;

import com.pasm.medical.domain.AiAudit;
import com.pasm.medical.repository.AuditRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/**
 * AI 审计服务 —— **只提供写入入口**，不暴露 update / delete。
 *
 * <p>这是"审计库只增不改"在技术上的最小保障：调用方拿不到改删方法，
 * 想改审计只能改代码。生产再叠一层"独立库 + 只授 INSERT"的物理保障。
 */
@Service
public class AuditService {

    private final AuditRepository repo;

    @Value("${medical.audit.enabled:true}")
    private boolean enabled;

    public AuditService(AuditRepository repo) {
        this.repo = repo;
    }

    /**
     * 审计里的「操作者」。
     *
     * <p>优先用调用方传进来的认证对象，没传就从 {@link SecurityContextHolder} 取
     * （令牌过滤器每请求都会塞进去）。
     *
     * <p>★ 做成静态工具而不是要求每个 handler 都声明 {@code Authentication} 参数：
     * 少一个参数就少一次"新加接口时忘了传 actor"的机会 —— 漏传**不会报错**，
     * 只是审计里少个人，事后根本发现不了。
     */
    public static String currentActor(Authentication auth) {
        if (auth != null && auth.getName() != null) {
            return auth.getName();
        }
        Authentication ctx = SecurityContextHolder.getContext().getAuthentication();
        return ctx == null || ctx.getName() == null ? "" : ctx.getName();
    }

    /** 记录一条审计（不可变）。enabled=false 时静默跳过（合规探针可用）。 */
    @Transactional
    public void record(AiAudit a) {
        if (!enabled) {
            return;
        }
        repo.save(a);
    }

    /**
     * 记录一次**读取**审计。
     *
     * <p>★ 为什么"读"也要审计：医疗系统里「谁查看了哪位患者的病历」本身就是要留痕的事件 ——
     * 出了数据泄露，只查"谁改过"是根本查不出来的。所以查看档案 / 历史就诊 / 就诊详情
     * 这些接口都要落一条。
     *
     * <p>★ 这类事件**必须带 actor**。不带操作者的读审计等于没记：
     * 面板上的"操作者"列永远是空的，出事时无法回答"是谁看的"。
     *
     * <p>★ 调用时机是**先记再看**：即使随后返回 404（ref 不存在 / 不属于该患者），
     * 这次"尝试查看"也已经被记录下来 —— 探测行为恰恰是最需要留痕的。
     */
    @Transactional
    public void recordRead(String actor, String patientRef, String action, String detail) {
        if (!enabled) {
            return;
        }
        AiAudit a = new AiAudit();
        a.setPatientRef(patientRef == null ? "" : patientRef);
        a.setActor(actor == null ? "" : actor);
        a.setAction(action);
        a.setModelVersion("business-layer");
        a.setEvidenceCount(0);
        a.setInputSnapshot(detail == null ? "" : detail);
        a.setRefused(false);
        repo.save(a);
    }

    public List<AiAudit> recent() {
        return repo.findTop200ByOrderByOccurredAtDesc();
    }

    public List<AiAudit> byPatient(String ref) {
        return repo.findByPatientRefOrderByOccurredAtDesc(ref);
    }

    public long countByAction(String action) {
        return repo.countByAction(action);
    }

    public long countRefused(String action) {
        return repo.countByActionAndRefusedTrue(action);
    }
}
