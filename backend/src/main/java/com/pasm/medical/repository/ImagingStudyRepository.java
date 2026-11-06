package com.pasm.medical.repository;

import com.pasm.medical.domain.ImagingStudy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ImagingStudyRepository extends JpaRepository<ImagingStudy, Long> {

    List<ImagingStudy> findByPatientRefOrderByCreatedAtDesc(String patientRef);

    List<ImagingStudy> findByStatusOrderByCreatedAtDesc(String status);

    List<ImagingStudy> findByStatusAndDepartmentOrderByCreatedAtDesc(String status, String department);
}
