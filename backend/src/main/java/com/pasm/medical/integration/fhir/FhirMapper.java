package com.pasm.medical.integration.fhir;

import com.pasm.medical.domain.Encounter;
import com.pasm.medical.domain.LabResult;
import com.pasm.medical.domain.Patient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务实体 → FHIR R4 资源（只读投影）。
 *
 * <p>★ <b>不伪造标准编码</b>：没有 LOINC/SNOMED 映射时，{@code code.coding} 用院内码
 * （{@code urn:pasm:medical:analyte}）并给出 {@code text}。<b>绝不</b>把院内码塞进
 * {@code system: http://loinc.org} —— 下游按 LOINC 解释时会读成"另一个检验项目"，
 * 这种错误在临床决策支持里是致命的。
 *
 * <p>★ 字段顺序用 {@link LinkedHashMap} 固定，便于人工比对与快照测试。
 */
public final class FhirMapper {

    private FhirMapper() {
    }

    public static Map<String, Object> patient(Patient p) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resourceType", "Patient");
        r.put("id", p.getRef());
        r.put("identifier", List.of(Map.of(
                "system", "urn:pasm:medical:patient-ref",
                "value", p.getRef())));
        if (p.getName() != null && !p.getName().isBlank()) {
            r.put("name", List.of(Map.of("text", p.getName())));
        }
        r.put("gender", gender(p.getSex()));
        // 只有年龄没有出生日期：FHIR 用 birthDate，不拿年龄倒推一个假生日。
        return r;
    }

    public static Map<String, Object> encounter(Encounter e) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resourceType", "Encounter");
        r.put("id", String.valueOf(e.getId()));
        r.put("status", "finished");
        r.put("class", Map.of(
                "system", "http://terminology.hl7.org/CodeSystem/v3-ActCode",
                "code", "AMB",
                "display", "ambulatory"));
        if (e.getDepartment() != null && !e.getDepartment().isBlank()) {
            r.put("type", List.of(Map.of("text", e.getDepartment())));
        }
        r.put("subject", Map.of("reference", "Patient/" + e.getPatientRef()));
        if (e.getOccurredAt() != null) {
            r.put("period", Map.of("start", iso(e.getOccurredAt())));
        }
        if (e.getChiefComplaint() != null && !e.getChiefComplaint().isBlank()) {
            r.put("reasonCode", List.of(Map.of("text", e.getChiefComplaint())));
        }
        return r;
    }

    public static Map<String, Object> observation(LabResult l) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resourceType", "Observation");
        r.put("id", String.valueOf(l.getId()));
        r.put("status", obsStatus(l.getResultStatus()));

        r.put("category", List.of(Map.of("coding", List.of(Map.of(
                "system", "http://terminology.hl7.org/CodeSystem/observation-category",
                "code", "laboratory",
                "display", "Laboratory")))));

        Map<String, Object> code = new LinkedHashMap<>();
        code.put("coding", List.of(Map.of(
                "system", "urn:pasm:medical:analyte",
                "code", l.getAnalyteCode(),
                "display", l.getAnalyteName() == null ? l.getAnalyteCode() : l.getAnalyteName())));
        code.put("text", l.getAnalyteName() == null ? l.getAnalyteCode() : l.getAnalyteName());
        r.put("code", code);

        r.put("subject", Map.of("reference", "Patient/" + l.getPatientRef()));

        if (l.getObservedAt() != null) {
            r.put("effectiveDateTime", iso(l.getObservedAt()));
        }
        if (l.getValueNum() != null) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("value", l.getValueNum().stripTrailingZeros().toPlainString());
            if (l.getUnit() != null) {
                q.put("unit", l.getUnit());
            }
            r.put("valueQuantity", q);
        } else if (l.getValueText() != null) {
            // 文本型结果（"阴性""未见生长"）走 valueString —— 转成数字就是改数据。
            r.put("valueString", l.getValueText());
        }

        Map<String, Object> interp = interpretation(l.getAbnormalFlag());
        if (interp != null) {
            r.put("interpretation", List.of(Map.of("coding", List.of(interp))));
        }

        Map<String, Object> range = new LinkedHashMap<>();
        if (l.getRefLow() != null) {
            range.put("low", quantity(l.getRefLow()));
        }
        if (l.getRefHigh() != null) {
            range.put("high", quantity(l.getRefHigh()));
        }
        if (l.getRefRangeText() != null) {
            range.put("text", l.getRefRangeText());
        }
        if (!range.isEmpty()) {
            r.put("referenceRange", List.of(range));
        }
        return r;
    }

    public static Map<String, Object> bundle(String type, List<Map<String, Object>> resources) {
        List<Map<String, Object>> entries = new ArrayList<>(resources.size());
        for (Map<String, Object> res : resources) {
            entries.add(Map.of(
                    "fullUrl", res.get("resourceType") + "/" + res.get("id"),
                    "resource", res));
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("resourceType", "Bundle");
        b.put("type", type);
        b.put("total", resources.size());
        b.put("entry", entries);
        return b;
    }

    /** 能力声明：明确说"只读、支持哪些资源"，不吹未实现的交互。 */
    public static Map<String, Object> capabilityStatement() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resourceType", "CapabilityStatement");
        r.put("status", "active");
        r.put("kind", "instance");
        r.put("fhirVersion", "4.0.1");
        r.put("format", List.of("application/fhir+json"));
        r.put("implementation", Map.of("description",
                "PASM Medical 业务层只读 FHIR 投影（Patient / Encounter / Observation）"));
        List<Map<String, Object>> rest = new ArrayList<>();
        List<Map<String, Object>> resources = new ArrayList<>();
        resources.add(capability("Patient", "read"));
        resources.add(capability("Encounter", "search-type"));
        resources.add(capability("Observation", "search-type"));
        rest.add(Map.of("mode", "server", "resource", resources));
        r.put("rest", rest);
        return r;
    }

    // ------------------------------------------------------------ 内部

    private static Map<String, Object> capability(String type, String interaction) {
        return Map.of(
                "type", type,
                "interaction", List.of(Map.of("code", interaction)),
                "versioning", "no-version");
    }

    private static Map<String, Object> quantity(BigDecimal v) {
        return Map.of("value", v.stripTrailingZeros().toPlainString());
    }

    /** 性别：只映射确定值，其余 unknown —— 不拿"女"猜成 female 之外的任何东西。 */
    static String gender(String sex) {
        if (sex == null) {
            return "unknown";
        }
        return switch (sex.trim()) {
            case "男", "male", "M" -> "male";
            case "女", "female", "F" -> "female";
            default -> "unknown";
        };
    }

    /** OBX-11 → Observation.status（F/P/C）。 */
    static String obsStatus(String resultStatus) {
        if (resultStatus == null) {
            return "final";
        }
        return switch (resultStatus.trim().toUpperCase()) {
            case "P" -> "preliminary";
            case "C" -> "corrected";
            case "X" -> "cancelled";
            default -> "final";
        };
    }

    /** 归一化异常标记 → v3-ObservationInterpretation 编码。 */
    static Map<String, Object> interpretation(String flag) {
        if (flag == null) {
            return null;
        }
        String code;
        String display;
        switch (flag) {
            case "high" -> { code = "H"; display = "High"; }
            case "low" -> { code = "L"; display = "Low"; }
            case "normal" -> { code = "N"; display = "Normal"; }
            case "abnormal" -> { code = "A"; display = "Abnormal"; }
            default -> { return null; }
        }
        return Map.of(
                "system", "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation",
                "code", code,
                "display", display);
    }

    private static String iso(Instant t) {
        return t.toString();
    }
}
