package com.pasm.medical.repository;

import com.pasm.medical.domain.KbCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface KbCandidateRepository extends JpaRepository<KbCandidate, Long> {

    /** 按状态拉列表（待审/冲突/已处理）。 */
    List<KbCandidate> findByStatusOrderByCreatedAtDesc(String status);

    /** 排除某状态（如"非已驳回"= 待审+冲突+已采纳，供默认视图用）。 */
    List<KbCandidate> findByStatusNotOrderByCreatedAtDesc(String status);

    long countByStatus(String status);

    /** 指纹去重：同一经验不重复入池。 */
    Optional<KbCandidate> findByFingerprint(String fingerprint);

    /** 按科室过滤（科室管理员只看本科室那一批）。 */
    List<KbCandidate> findByDepartmentAndStatusOrderByCreatedAtDesc(String department, String status);
}
