package com.pasm.medical.repository;

import com.pasm.medical.domain.AiAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface AuditRepository extends JpaRepository<AiAudit, Long> {

    /** 审计面板用：最近 200 条。★ 只能用于「展示」，**不能**用它做统计口径。 */
    List<AiAudit> findTop200ByOrderByOccurredAtDesc();

    List<AiAudit> findByPatientRefOrderByOccurredAtDesc(String patientRef);

    /**
     * 科室范围：只看这些患者的审计（同样是最近 200 条窗口）。
     *
     * <p>★ 必须在库里筛，**不能**"取最近 200 条再在内存里过滤"：
     * 本科室的事件不落在那 200 条里时，界面会**静默变空**，
     * 看起来像"本科室没有活动"，实际是筛选方式错了。
     */
    List<AiAudit> findTop200ByPatientRefInOrderByOccurredAtDesc(
            Collection<String> patientRefs);

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
