package com.pasm.medical.web;

import com.pasm.medical.config.IdentityContext;
import com.pasm.medical.domain.Encounter;
import com.pasm.medical.domain.LabResult;
import com.pasm.medical.domain.Patient;
import com.pasm.medical.integration.fhir.FhirMapper;
import com.pasm.medical.repository.EncounterRepository;
import com.pasm.medical.repository.LabResultRepository;
import com.pasm.medical.service.PatientService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FHIR R4 只读接口：把业务库投影成标准资源，供院内其它系统按 FHIR 读取。
 *
 * <p>★ <b>不是"又一个 JSON 接口"</b>：字段名、编码系统、状态枚举按 R4 规范来，
 * 下游（随访系统 / 数据平台 / 科研库）才能用现成的 FHIR 客户端接。
 *
 * <p>★ <b>权限照旧按患者收窄</b>：FHIR 只是表示层，不能因为它"标准"就绕过
 * {@link IdentityContext#scopedRef} —— 换个股读别人病历同样是越权。
 *
 * <p>★ 当前只实现 <b>读</b>（Patient/read、Encounter/search、Observation/search）。
 * 写入走 LIS 接入通道（{@code /api/integration/**}），两者鉴权模型不同，不混在一起。
 */
@RestController
@RequestMapping(value = "/api/fhir", produces = "application/fhir+json")
public class FhirController {

    private final PatientService patients;
    private final EncounterRepository encounters;
    private final LabResultRepository labs;

    public FhirController(PatientService patients,
                          EncounterRepository encounters,
                          LabResultRepository labs) {
        this.patients = patients;
        this.encounters = encounters;
        this.labs = labs;
    }

    @GetMapping("/metadata")
    public Map<String, Object> metadata() {
        return FhirMapper.capabilityStatement();
    }

    @GetMapping("/Patient/{ref}")
    public ResponseEntity<Map<String, Object>> patient(@PathVariable String ref) {
        String scoped = IdentityContext.scopedRef(ref);
        if (scoped == null) {
            return forbidden();
        }
        Patient p = patients.findById(scoped);
        if (p == null) {
            return notFound("Patient", scoped);
        }
        return ResponseEntity.ok(FhirMapper.patient(p));
    }

    @GetMapping("/Encounter")
    public ResponseEntity<Map<String, Object>> encounterSearch(
            @RequestParam(name = "patient", required = false) String patient) {
        String scope = IdentityContext.scopeDepartment();
        String scoped = IdentityContext.scopedRef(patient);
        if (scoped == null) {
            return forbidden();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Encounter e : encounters.findByPatientRefOrderByOccurredAtDesc(scoped)) {
            // 科室范围过滤与后台接口保持一致：跨科室的医护看不到别科就诊。
            if (scope != null && (e.getDepartment() == null
                    || !e.getDepartment().contains(scope))) {
                continue;
            }
            out.add(FhirMapper.encounter(e));
        }
        return ResponseEntity.ok(FhirMapper.bundle("searchset", out));
    }

    @GetMapping("/Observation")
    public ResponseEntity<Map<String, Object>> observationSearch(
            @RequestParam(name = "patient", required = false) String patient) {
        String scoped = IdentityContext.scopedRef(patient);
        if (scoped == null) {
            return forbidden();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (LabResult l : labs.findByPatientRefOrderByObservedAtDescIdDesc(scoped)) {
            out.add(FhirMapper.observation(l));
        }
        return ResponseEntity.ok(FhirMapper.bundle("searchset", out));
    }

    // ------------------------------------------------------------ 错误

    private static ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of(
                "resourceType", "OperationOutcome",
                "issue", List.of(Map.of(
                        "severity", "error",
                        "code", "forbidden",
                        "diagnostics", "无权访问该患者的 FHIR 资源"))));
    }

    private static ResponseEntity<Map<String, Object>> notFound(String type, String id) {
        return ResponseEntity.status(404).body(Map.of(
                "resourceType", "OperationOutcome",
                "issue", List.of(Map.of(
                        "severity", "error",
                        "code", "not-found",
                        "diagnostics", type + "/" + id + " 不存在"))));
    }
}
