package com.pasm.medical.repository;

import com.pasm.medical.domain.Encounter;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface EncounterRepository extends JpaRepository<Encounter, Long> {
    List<Encounter> findByPatientRefOrderByOccurredAtDesc(String patientRef);
    long countByPatientRef(String patientRef);
}
