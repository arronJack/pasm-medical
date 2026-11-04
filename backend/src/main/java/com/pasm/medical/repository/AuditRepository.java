package com.pasm.medical.repository;

import com.pasm.medical.domain.AiAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface AuditRepository extends JpaRepository<AiAudit, Long> {

    /**
     * 审计面板用：**分页**读取（按时间倒序）。
     *
     * <p>★ 旧接口固定 {@code findTop200...}，超过 200 条就更早的记录在界面上翻不到 ——
     * 对"只增不改"的合规库来说这是静默丢历史。展示请一律走分页方法，
     * 统计请走下面的 {@code countBy...} 方法（不要拿展示数据算指标）。
     */
    Page<AiAudit> findAllByOrderByOccurredAtDesc(Pageable pageable);

    /** 科室范围的分页版本：在库里筛 + 分页，避免"先取一批再内存过滤"导致静默变空。 */
    Page<AiAudit> findByPatientRefInOrderByOccurredAtDesc(
            Collection<String> patientRefs, Pageable pageable);

    /** @deprecated 固定 200 条会丢历史；仅为兼容保留，新代码请用分页方法。 */
    @Deprecated
    List<AiAudit> findTop200ByOrderByOccurredAtDesc();

    List<AiAudit> findByPatientRefOrderByOccurredAtDesc(String patientRef);

    /**
     * 科室范围：只看这些患者的审计（同样是最近 200 条窗口）。
     *
     * <p>★ 必须在库里筛，**不能**"取最近 200 条再在内存里过滤"：
     * 本科室的事件不落在那 200 条里时，界面会**静默变空**，
     * 看起来像"本科室没有活动"，实际是筛选方式错了。
     */
    /** @deprecated 固定 200 条会丢历史；仅为兼容保留，新代码请用分页方法。 */
    @Deprecated
    List<AiAudit> findTop200ByPatientRefInOrderByOccurredAtDesc(
            Collection<String> patientRefs);

    long countByAction(String action);

    long countByActionAndRefusedTrue(String action);

    // ---- 科室维度统计（★ 按 patientRef 集合过滤，而不是把全院数字当本科室）----

    long countByActionAndPatientRefIn(String action, Collection<String> patientRefs);

    long countByActionAndPatientRefInAndOccurredAtGreaterThanEqual(
            String action, Collection<String> patientRefs, Instant from);

    long countByActionAndRefusedTrueAndPatientRefIn(
            String action, Collection<String> patientRefs);

    long countByActionAndRefusedTrueAndPatientRefInAndOccurredAtGreaterThanEqual(
            String action, Collection<String> patientRefs, Instant from);

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
