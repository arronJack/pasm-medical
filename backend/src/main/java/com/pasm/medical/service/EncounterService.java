package com.pasm.medical.service;

import com.pasm.medical.domain.Encounter;
import com.pasm.medical.repository.EncounterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** 就诊（预问诊）仓储服务。 */
@Service
public class EncounterService {

    private final EncounterRepository repo;

    public EncounterService(EncounterRepository repo) {
        this.repo = repo;
    }

    /** 落库一次预问诊 / 就诊。 */
    @Transactional
    public Encounter save(Encounter e) {
        return repo.save(e);
    }

    public List<Encounter> byPatient(String ref) {
        return repo.findByPatientRefOrderByOccurredAtDesc(ref);
    }

    public long countByPatient(String ref) {
        return repo.countByPatientRef(ref);
    }
}
