package com.pasm.medical.service;

import com.pasm.medical.domain.AiAudit;
import com.pasm.medical.repository.AuditRepository;
import org.springframework.beans.factory.annotation.Value;
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

    /** 记录一条审计（不可变）。enabled=false 时静默跳过（合规探针可用）。 */
    @Transactional
    public void record(AiAudit a) {
        if (!enabled) {
            return;
        }
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
