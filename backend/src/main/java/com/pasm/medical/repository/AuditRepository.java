package com.pasm.medical.repository;

import com.pasm.medical.domain.AiAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AuditRepository extends JpaRepository<AiAudit, Long> {
    List<AiAudit> findTop200ByOrderByOccurredAtDesc();
    List<AiAudit> findByPatientRefOrderByOccurredAtDesc(String patientRef);
    long countByAction(String action);
    long countByActionAndRefusedTrue(String action);
}
