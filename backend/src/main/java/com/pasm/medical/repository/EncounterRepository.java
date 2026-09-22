package com.pasm.medical.repository;

import com.pasm.medical.domain.Encounter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface EncounterRepository extends JpaRepository<Encounter, Long> {
    List<Encounter> findByPatientRefOrderByOccurredAtDesc(String patientRef);
    long countByPatientRef(String patientRef);

    /**
     * 本科室范围内出现过的患者标识（"本科室"的数据范围就是靠它算出来的）。
     *
     * <p>★ 用 {@code like} 是因为演示数据里 {@code department} 可能是
     * {@code "心内科 / 急诊内科"} 这种复合串。P1-2 引入科室实体后，
     * 这里要换成按科室 id 精确匹配 —— 字符串包含匹配**只适合当前阶段**，
     * 它会误命中子串（例如"内科"会命中"神经内科"）。
     */
    @Query("select distinct e.patientRef from Encounter e "
            + "where e.department like concat('%', :department, '%')")
    List<String> patientRefsInDepartment(@Param("department") String department);
}
