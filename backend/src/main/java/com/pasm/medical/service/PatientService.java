package com.pasm.medical.service;

import com.pasm.medical.domain.Patient;
import com.pasm.medical.repository.PatientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 患者仓储服务。
 *
 * <p>业务层的"患者"只持有一个引用主键与少量结构化字段；
 * 真正的诊疗记忆在认知层。这里保证"该患者存在"，并把过敏史 / 慢病这类
 * 高优先级事实顺手落一份结构化副本，便于后台检索。
 */
@Service
public class PatientService {

    private final PatientRepository repo;

    public PatientService(PatientRepository repo) {
        this.repo = repo;
    }

    /** 按主键查患者；不存在返回 null。 */
    public Patient findById(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        return repo.findById(ref).orElse(null);
    }

    /** 确保患者存在（首次出现时建档，不覆盖既有信息）。 */
    @Transactional
    public Patient ensureExists(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        return repo.findById(ref).orElseGet(() -> {
            Patient p = new Patient();
            p.setRef(ref);
            p.setName("患者·" + ref);
            return repo.save(p);
        });
    }

    /** 写入 / 更新高优先级事实（过敏史 / 慢病）。 */
    @Transactional
    public void recordFacts(String ref, String allergy, String chronic) {
        if (ref == null || ref.isBlank()) {
            return;
        }
        Patient p = repo.findById(ref).orElseGet(() -> {
            Patient n = new Patient();
            n.setRef(ref);
            n.setName("患者·" + ref);
            return n;
        });
        if (allergy != null && !allergy.isBlank()) {
            p.setAllergy(allergy);
        }
        if (chronic != null && !chronic.isBlank()) {
            p.setChronic(chronic);
        }
        repo.save(p);
    }
}
