package com.pasm.medical.repository;

import com.pasm.medical.domain.Patient;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PatientRepository extends JpaRepository<Patient, String> {
    List<Patient> findTop100ByOrderByCreatedAtDesc();
}
