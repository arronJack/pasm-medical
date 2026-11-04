package com.pasm.medical.repository;

import com.pasm.medical.domain.LabResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LabResultRepository extends JpaRepository<LabResult, Long> {

    /** 患者检验时间轴（最近在前）。 */
    List<LabResult> findByPatientRefOrderByObservedAtDescIdDesc(String patientRef);

    /** 按检验单回看（同一消息内的项目按序号）。 */
    List<LabResult> findByPatientRefAndMessageControlIdOrderByObservationSeqAsc(
            String patientRef, String messageControlId);

    boolean existsByMessageControlIdAndObservationSeq(String messageControlId, Integer observationSeq);

    Optional<LabResult> findFirstByMessageControlIdAndObservationSeq(
            String messageControlId, Integer observationSeq);

    long countByPatientRef(String patientRef);
}
