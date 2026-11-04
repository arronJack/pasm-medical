package com.pasm.medical.web;

import com.pasm.medical.integration.LisDisabledException;
import com.pasm.medical.integration.UnknownPatientException;
import com.pasm.medical.integration.hl7.Hl7ParseException;
import com.pasm.medical.service.LisIngestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LIS → 业务层的检验结果接入端点（HL7 v2.x ORU^R01）。
 *
 * <p>★ <b>鉴权方式与用户接口不同</b>：LIS 是机器，没有用户 JWT，也不能让它拿
 * 一个医护令牌（等于给了它读全部病历的权限）。所以用<b>专用共享密钥</b>
 * {@code MEDICAL_LIS_TOKEN}，只授予"投递检验结果"这一件事。
 *
 * <p>★ <b>未配置令牌时拒绝一切请求</b>（503），不是放行。
 * "没配就等于不校验"是这类集成最常见的洞 —— 上线时忘了配环境变量，
 * 通道就变成匿名可写。
 *
 * <p>★ 令牌比较在 {@link IntegrationTokenGuard} 里做（常量时间，见该类注释），
 * 两个对接控制器共用同一份实现，避免安全逻辑分叉。
 */
@RestController
@RequestMapping("/api/integration/lis")
public class LisIntegrationController {

    /** 单条消息上限 1 MiB：正常 ORU 只有几 KB，超限多半是发错对象或攻击。 */
    private static final int MAX_BYTES = 1024 * 1024;

    private final LisIngestService ingest;
    private final IntegrationTokenGuard guard;

    public LisIntegrationController(LisIngestService ingest, IntegrationTokenGuard guard) {
        this.ingest = ingest;
        this.guard = guard;
    }

    @PostMapping("/oru")
    public ResponseEntity<Map<String, Object>> oru(
            @RequestHeader(value = "X-LIS-Token", required = false) String token,
            @RequestBody(required = false) String body) {

        IntegrationTokenGuard.Verdict verdict = guard.check(token);
        if (verdict != IntegrationTokenGuard.Verdict.OK) {
            return switch (verdict) {
                case NOT_CONFIGURED -> error(HttpStatus.SERVICE_UNAVAILABLE,
                        "lis_token_not_configured",
                        "服务端未配置 MEDICAL_LIS_TOKEN，通道已关闭（不会以'不校验'方式开放）");
                case MISSING -> error(HttpStatus.UNAUTHORIZED, "missing_token",
                        "缺少 X-LIS-Token");
                default -> error(HttpStatus.UNAUTHORIZED, "invalid_token", "接入令牌无效");
            };
        }
        if (body == null || body.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "empty_message", "消息体为空");
        }
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "message_too_large",
                    "消息超过 1 MiB 上限");
        }

        LisIngestService.IngestResult r = ingest.ingest(body);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accepted", true);
        out.put("patientRef", r.patientRef());
        out.put("messageControlId", r.messageControlId());
        out.put("panelCode", r.panelCode());
        out.put("received", r.received());
        out.put("inserted", r.inserted());
        out.put("skippedDuplicates", r.skippedDuplicates());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
    }

    // ------------------------------------------------------------ 错误映射

    @ExceptionHandler(LisDisabledException.class)
    public ResponseEntity<Map<String, Object>> onDisabled(LisDisabledException e) {
        // 409 而不是 503：这是"配置不允许"，不是"服务暂时不可用"，
        // LIS 侧看到 409 应停止重试并联系管理员，看到 503 会一直重试。
        return error(HttpStatus.CONFLICT, "lis_disabled", e.getMessage());
    }

    @ExceptionHandler(UnknownPatientException.class)
    public ResponseEntity<Map<String, Object>> onUnknownPatient(UnknownPatientException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "unknown_patient", e.getMessage());
    }

    @ExceptionHandler(Hl7ParseException.class)
    public ResponseEntity<Map<String, Object>> onParse(Hl7ParseException e) {
        Map<String, Object> body = errorBody("hl7_parse_error", e.getMessage());
        if (!e.getSegment().isEmpty()) {
            body.put("segment", e.getSegment());
            body.put("field", e.getField());
        }
        return ResponseEntity.badRequest().body(body);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status,
                                                             String code,
                                                             String message) {
        return ResponseEntity.status(status).body(errorBody(code, message));
    }

    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accepted", false);
        m.put("error", code);
        m.put("message", message);
        return m;
    }
}
