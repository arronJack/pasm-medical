package com.pasm.medical.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.pasm.medical.integration.IngestConflictException;
import com.pasm.medical.integration.LisDisabledException;
import com.pasm.medical.integration.UnknownPatientException;
import com.pasm.medical.integration.hl7.Hl7ParseException;
import com.pasm.medical.service.FhirIngestService;
import com.pasm.medical.service.LisIngestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FHIR 投递通道：接受 {@code Observation} 或 {@code Bundle(type=collection)}。
 *
 * <p>与 HL7 通道并列，由 {@code system_config.lis} 决定哪一条是启用的：
 * {@code hl7} → 只开 HL7，{@code fhir} → 只开本通道，{@code off} → 两条都关。
 * <b>不会同时开</b>：一个机构同时从两条线收同一份结果，幂等键不同会变成双份数据。
 */
@RestController
@RequestMapping("/api/integration/fhir")
public class FhirIntegrationController {

    private final FhirIngestService ingest;
    private final IntegrationTokenGuard guard;

    public FhirIntegrationController(FhirIngestService ingest, IntegrationTokenGuard guard) {
        this.ingest = ingest;
        this.guard = guard;
    }

    @PostMapping(value = "/observations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> observations(
            @RequestHeader(value = "X-LIS-Token", required = false) String token,
            @RequestBody(required = false) JsonNode body) {

        IntegrationTokenGuard.Verdict verdict = guard.check(token);
        if (verdict != IntegrationTokenGuard.Verdict.OK) {
            return switch (verdict) {
                case NOT_CONFIGURED -> error(HttpStatus.SERVICE_UNAVAILABLE,
                        "lis_token_not_configured",
                        "服务端未配置 MEDICAL_LIS_TOKEN，通道已关闭");
                case MISSING -> error(HttpStatus.UNAUTHORIZED, "missing_token",
                        "缺少 X-LIS-Token");
                default -> error(HttpStatus.UNAUTHORIZED, "invalid_token", "接入令牌无效");
            };
        }
        if (body == null || body.isNull()) {
            return error(HttpStatus.BAD_REQUEST, "empty_body", "请求体为空");
        }

        LisIngestService.IngestResult r = ingest.ingest(body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", true);
        out.put("patientRef", r.patientRef());
        out.put("received", r.received());
        out.put("inserted", r.inserted());
        out.put("skippedDuplicates", r.skippedDuplicates());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
    }

    // ------------------------------------------------------------ 错误映射

    @ExceptionHandler(LisDisabledException.class)
    public ResponseEntity<Map<String, Object>> onDisabled(LisDisabledException e) {
        return error(HttpStatus.CONFLICT, "lis_disabled", e.getMessage());
    }

    @ExceptionHandler({UnknownPatientException.class, IngestConflictException.class})
    public ResponseEntity<Map<String, Object>> onPatient(RuntimeException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "patient_conflict", e.getMessage());
    }

    @ExceptionHandler(Hl7ParseException.class)
    public ResponseEntity<Map<String, Object>> onParse(Hl7ParseException e) {
        return error(HttpStatus.BAD_REQUEST, "fhir_invalid", e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status,
                                                             String code,
                                                             String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accepted", false);
        m.put("error", code);
        m.put("message", message);
        return ResponseEntity.status(status).body(m);
    }
}
