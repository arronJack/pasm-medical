package com.pasm.medical.repository;

import com.pasm.medical.domain.AiAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AuditRepository extends JpaRepository<AiAudit, Long> {

    /** 审计面板用：最近 200 条。★ 只能用于「展示」，**不能**用它做统计口径。 */
    List<AiAudit> findTop200ByOrderByOccurredAtDesc();

    List<AiAudit> findByPatientRefOrderByOccurredAtDesc(String patientRef);

    long countByAction(String action);

    long countByActionAndRefusedTrue(String action);

    /**
     * 时间窗内的计数。
     *
     * <p>★ 统计必须走这两个方法，**不要**先取 200 条再在内存里筛 —— 旧实现就是这么写的，
     * 后果是今日问诊量在审计超过 200 条后**静默封顶**，且数字随"最近 200 条里恰好有什么"漂移。
     * 这种错误不会报错，只会给出一个看着合理的假数字。
     */
    long countByActionAndOccurredAtGreaterThanEqual(String action, Instant from);

    long countByActionAndRefusedTrueAndOccurredAtGreaterThanEqual(String action, Instant from);
}
