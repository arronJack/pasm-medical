package com.pasm.medical.web;

import com.pasm.medical.domain.AiAudit;
import com.pasm.medical.domain.Encounter;
import com.pasm.medical.domain.Patient;
import com.pasm.medical.repository.AuditRepository;
import com.pasm.medical.repository.EncounterRepository;
import com.pasm.medical.repository.PatientRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 后台数据接口 —— 给前端 AdminView 的「患者情况 / 审计 / 统计」三个面板提供**真实数据**。
 *
 * <p>之前这些面板是前端硬编码的占位。现在从业务库（JPA）实时读取，
 * 与「诊断辅助」链路同源，不再是假数据。所有 /api/** 都走鉴权。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final PatientRepository patients;
    private final EncounterRepository encounters;
    private final AuditRepository audits;

    public AdminController(PatientRepository patients, EncounterRepository encounters,
                           AuditRepository audits) {
        this.patients = patients;
        this.encounters = encounters;
        this.audits = audits;
    }

    /** 患者情况：每位患者最新一次就诊 + 分诊 + 就诊次数。 */
    @GetMapping("/patients")
    public ResponseEntity<List<Map<String, Object>>> patients() {
        ZoneId z = ZoneId.systemDefault();
        var out = patients.findTop100ByOrderByCreatedAtDesc().stream().map(p -> {
            var last = encounters.findByPatientRefOrderByOccurredAtDesc(p.getRef())
                    .stream().findFirst().orElse(null);
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("ref", p.getRef());
            m.put("name", p.getName());
            m.put("allergy", p.getAllergy() == null ? "" : p.getAllergy());
            m.put("chronic", p.getChronic() == null ? "" : p.getChronic());
            m.put("encounterCount", encounters.countByPatientRef(p.getRef()));
            if (last != null) {
                m.put("last", last.getOccurredAt().atZone(z).toString().replace('T', ' ').substring(0, 16));
                m.put("dept", last.getDepartment() == null ? "" : last.getDepartment());
                m.put("urgency", last.getTriageUrgency() == null ? "routine" : last.getTriageUrgency());
            } else {
                m.put("last", "");
                m.put("dept", "");
                m.put("urgency", "routine");
            }
            return m;
        }).toList();
        return ResponseEntity.ok(out);
    }

    /** 审计（只增不改）：最近 200 条。 */
    @GetMapping("/audit")
    public ResponseEntity<List<Map<String, Object>>> audit() {
        ZoneId z = ZoneId.systemDefault();
        var out = audits.findTop200ByOrderByOccurredAtDesc().stream().map(a -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("time", a.getOccurredAt().atZone(z).toString().replace('T', ' ').substring(0, 16));
            m.put("ref", a.getPatientRef() == null ? "" : a.getPatientRef());
            m.put("actor", a.getActor() == null ? "" : a.getActor());
            m.put("action", a.getAction());
            m.put("evidenceCount", a.getEvidenceCount() == null ? 0 : a.getEvidenceCount());
            m.put("modelVersion", a.getModelVersion() == null ? "" : a.getModelVersion());
            m.put("refused", Boolean.TRUE.equals(a.getRefused()));
            return m;
        }).toList();
        return ResponseEntity.ok(out);
    }

    /** 运营统计：今日问诊量 / 红旗命中 / 拒答率 / 采纳率。 */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        List<AiAudit> recent = audits.findTop200ByOrderByOccurredAtDesc();
        LocalDate today = LocalDate.now();
        ZoneId z = ZoneId.systemDefault();
        long consultToday = recent.stream().filter(a -> {
            String act = a.getAction();
            if (!"ask".equals(act) && !"consult-finish".equals(act)) {
                return false;
            }
            return a.getOccurredAt().atZone(z).toLocalDate().equals(today);
        }).count();
        long redFlags = audits.countByAction("consult-redflag");
        long askTotal = audits.countByAction("ask");
        long askRefused = audits.countByActionAndRefusedTrue("ask");
        long adopt = audits.countByAction("feedback-adopt");
        long reject = audits.countByAction("feedback-reject");
        double refusalRate = askTotal > 0 ? (double) askRefused / askTotal : 0.0;
        double adoptionRate = (adopt + reject) > 0 ? (double) adopt / (adopt + reject) : 0.0;

        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("consultToday", consultToday);
        m.put("redFlags", redFlags);
        m.put("refusalRate", Math.round(refusalRate * 100) / 100.0);
        m.put("adoptionRate", Math.round(adoptionRate * 100) / 100.0);
        m.put("askTotal", askTotal);
        m.put("feedbackTotal", adopt + reject);
        return ResponseEntity.ok(m);
    }
}
