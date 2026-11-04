package com.pasm.medical.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.pasm.medical.domain.LabResult;
import com.pasm.medical.integration.IngestConflictException;
import com.pasm.medical.integration.LisDisabledException;
import com.pasm.medical.integration.UnknownPatientException;
import com.pasm.medical.integration.hl7.Hl7ParseException;
import com.pasm.medical.repository.LabResultRepository;
import com.pasm.medical.repository.PatientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * FHIR R4 检验结果接入：{@code Observation} 或 {@code Bundle(type=collection)} → 落库。
 *
 * <p>与 HL7 通道是<b>同一件事的两种线格式</b>：都归一到 {@link LabResult}，
 * 都受 {@code system_config.lis} 开关与共享密钥保护，都做幂等。
 *
 * <p>★ 幂等键用 {@code Observation.id}（FHIR 的稳定资源标识）。<b>没有 id 就拒收</b>：
 * 随机生成一个 id 会让同一结果重发时落成两条，"多一条"在检验数据里就是错。
 *
 * <p>★ 不做 LOINC 反查：没有映射表时，{@code code.coding[0].code} 原样当院内码存。
 * 猜一个 LOINC 码比没有码更危险。
 */
@Service
public class FhirIngestService {

    private final LabResultRepository results;
    private final PatientRepository patients;
    private final SystemConfigService config;

    public FhirIngestService(LabResultRepository results,
                             PatientRepository patients,
                             SystemConfigService config) {
        this.results = results;
        this.patients = patients;
        this.config = config;
    }

    @Transactional
    public LisIngestService.IngestResult ingest(JsonNode root) {
        String mode = config.read().lis();
        if ("off".equals(mode)) {
            throw new LisDisabledException("机构未启用 LIS 对接（system_config.lis=off）");
        }
        if (!"fhir".equals(mode)) {
            throw new LisDisabledException(
                    "当前 LIS 对接模式为 " + mode + "，FHIR 通道未启用（改为 fhir 后重试）");
        }

        List<JsonNode> observations = collect(root);
        if (observations.isEmpty()) {
            throw new Hl7ParseException("请求中没有 Observation 资源");
        }

        int inserted = 0;
        int skipped = 0;
        String patientRef = null;
        String firstId = null;

        for (JsonNode obs : observations) {
            if (!"Observation".equals(obs.path("resourceType").asText())) {
                throw new Hl7ParseException(
                        "Bundle 内只接受 Observation，收到：" + obs.path("resourceType").asText());
            }
            String id = text(obs, "id");
            if (id.isEmpty()) {
                throw new Hl7ParseException("Observation.id 缺失（幂等键必需）");
            }
            if (id.length() > 64) {
                throw new Hl7ParseException("Observation.id 超长（>64）");
            }

            String ref = subjectRef(obs);
            if (ref.isEmpty()) {
                throw new Hl7ParseException("Observation.subject.reference 缺失（需 Patient/<ref>）");
            }
            if (patientRef == null) {
                patientRef = ref;
                firstId = id;
            } else if (!patientRef.equals(ref)) {
                throw new Hl7ParseException(
                        "同一 Bundle 混有多个患者：" + patientRef + " / " + ref);
            }

            if (!patients.existsById(ref)) {
                throw new UnknownPatientException(
                        "未知患者 ref：" + ref + "（请先在业务侧建档）");
            }

            Optional<LabResult> existing =
                    results.findFirstByMessageControlIdAndObservationSeq(id, 1);
            if (existing.isPresent()) {
                if (!ref.equals(existing.get().getPatientRef())) {
                    throw new IngestConflictException(
                            "Observation/" + id + " 已存在且归属患者 "
                                    + existing.get().getPatientRef() + "，拒绝挂到 " + ref);
                }
                skipped++;                    // 同一资源重发：幂等跳过
                continue;
            }
            results.save(toEntity(id, ref, obs));
            inserted++;
        }

        return new LisIngestService.IngestResult(patientRef, firstId, "", observations.size(),
                inserted, skipped);
    }

    // ------------------------------------------------------------ 内部

    private static List<JsonNode> collect(JsonNode root) {
        String type = root.path("resourceType").asText();
        if ("Observation".equals(type)) {
            return List.of(root);
        }
        if ("Bundle".equals(type)) {
            List<JsonNode> out = new ArrayList<>();
            for (JsonNode e : root.path("entry")) {
                JsonNode r = e.path("resource");
                if (!r.isMissingNode() && !r.isNull()) {
                    out.add(r);
                }
            }
            return out;
        }
        throw new Hl7ParseException(
                "不支持的资源类型：" + type + "（只接受 Observation 或 Bundle）");
    }

    private static String subjectRef(JsonNode obs) {
        String reference = obs.path("subject").path("reference").asText("").trim();
        if (reference.startsWith("Patient/")) {
            return reference.substring("Patient/".length());
        }
        // 也接受 urn:uuid / 绝对 URL 里的 Patient/<id> 形态
        int idx = reference.lastIndexOf("Patient/");
        return idx >= 0 ? reference.substring(idx + "Patient/".length()) : "";
    }

    private static LabResult toEntity(String id, String patientRef, JsonNode obs) {
        JsonNode code = obs.path("code");
        String analyteCode = codingCode(code);
        if (analyteCode.isEmpty()) {
            throw new Hl7ParseException("Observation/" + id + " 缺少 code.coding[0].code");
        }

        LabResult r = new LabResult();
        r.setPatientRef(patientRef);
        r.setMessageControlId(id);
        r.setObservationSeq(1);
        r.setAnalyteCode(analyteCode);
        r.setAnalyteName(firstNonBlank(code.path("text").asText(""), codingDisplay(code)));
        r.setSource("fhir");
        r.setResultStatus(obxStatus(obs.path("status").asText("final")));
        r.setObservedAt(instant(obs.path("effectiveDateTime").asText("")));

        JsonNode q = obs.path("valueQuantity");
        if (!q.isMissingNode() && !q.isNull() && q.hasNonNull("value")) {
            BigDecimal v = decimal(q.get("value"));
            if (v != null) {
                r.setValueNum(v);
                r.setValueText(v.stripTrailingZeros().toPlainString());
            }
            r.setUnit(firstNonBlank(q.path("unit").asText(""), q.path("code").asText("")));
        } else if (obs.hasNonNull("valueString")) {
            r.setValueText(obs.get("valueString").asText());
        } else {
            throw new Hl7ParseException(
                    "Observation/" + id + " 缺少 valueQuantity / valueString");
        }

        JsonNode range = obs.path("referenceRange");
        if (range.isArray() && !range.isEmpty()) {
            JsonNode rr = range.get(0);
            r.setRefLow(decimal(rr.path("low").path("value")));
            r.setRefHigh(decimal(rr.path("high").path("value")));
            r.setRefRangeText(firstNonBlank(rr.path("text").asText(""),
                    rangeText(r.getRefLow(), r.getRefHigh())));
        }

        String rawFlag = interpretationCode(obs);
        r.setAbnormalFlagRaw(rawFlag);
        r.setAbnormalFlag(normalize(rawFlag, r.getValueNum(), r.getRefLow(), r.getRefHigh()));
        return r;
    }

    private static String codingCode(JsonNode codeable) {
        JsonNode coding = codeable.path("coding");
        if (coding.isArray() && !coding.isEmpty()) {
            String c = coding.get(0).path("code").asText("");
            if (!c.isBlank()) {
                return c.trim();
            }
        }
        return "";
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    /** code.coding[0].display（用于检验项目名称）。 */
    private static String codingDisplay(JsonNode codeable) {
        JsonNode coding = codeable.path("coding");
        if (coding.isArray() && !coding.isEmpty()) {
            return coding.get(0).path("display").asText("").trim();
        }
        return "";
    }

    private static String interpretationCode(JsonNode obs) {
        JsonNode interp = obs.path("interpretation");
        if (!interp.isArray() || interp.isEmpty()) {
            return "";
        }
        return codingCode(interp.get(0));
    }

    private static String normalize(String rawFlag, BigDecimal value,
                                    BigDecimal low, BigDecimal high) {
        switch (rawFlag.toUpperCase()) {
            case "H", "HU" -> { return "high"; }
            case "L", "LU" -> { return "low"; }
            case "N" -> { return "normal"; }
            case "A", "AA" -> { return "abnormal"; }
            default -> { }
        }
        if (value == null) {
            return "unknown";
        }
        if (low != null && value.compareTo(low) < 0) {
            return "low";
        }
        if (high != null && value.compareTo(high) > 0) {
            return "high";
        }
        return (low != null || high != null) ? "normal" : "unknown";
    }

    private static String obxStatus(String fhirStatus) {
        return switch (fhirStatus) {
            case "preliminary" -> "P";
            case "corrected", "amended" -> "C";
            case "cancelled", "entered-in-error" -> "X";
            default -> "F";
        };
    }

    private static BigDecimal decimal(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        try {
            return n.isNumber() ? n.decimalValue() : new BigDecimal(n.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso.trim());
        } catch (DateTimeParseException e) {
            throw new Hl7ParseException("effectiveDateTime 不是合法 ISO-8601 时间：" + iso);
        }
    }

    private static String rangeText(BigDecimal low, BigDecimal high) {
        if (low != null && high != null) {
            return low.stripTrailingZeros().toPlainString() + "-"
                    + high.stripTrailingZeros().toPlainString();
        }
        if (high != null) {
            return "<" + high.stripTrailingZeros().toPlainString();
        }
        if (low != null) {
            return ">" + low.stripTrailingZeros().toPlainString();
        }
        return "";
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }
}
