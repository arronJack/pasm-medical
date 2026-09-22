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

    /**
     * 按 id 取一次就诊 —— <b>同时校验归属</b>。
     *
     * <p>★ 为什么不能只 {@code findById} 了事：就诊 id 是自增整数，换一个数字就能读到
     * 别人的病历（IDOR）。医疗数据里这是最严重的一类越权，所以归属校验必须和查询绑在
     * 一起 —— 放在调用方"记得校验"是靠不住的。
     *
     * @return 命中且归属相符才返回实体；否则 null（调用方翻译成 404，
     *         <b>不区分"不存在"和"不属于你"</b>，避免探测他人 id 是否存在）
     */
    public Encounter findForPatient(String ref, Long id) {
        if (ref == null || ref.isBlank() || id == null) {
            return null;
        }
        return repo.findById(id)
                .filter(e -> ref.equals(e.getPatientRef()))
                .orElse(null);
    }

    public long countByPatient(String ref) {
        return repo.countByPatientRef(ref);
    }
}
