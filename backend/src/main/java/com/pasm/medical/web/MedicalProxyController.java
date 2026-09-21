package com.pasm.medical.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.pasm.medical.cognition.PasmCognitionClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 医疗接口 —— 业务层到认知服务的**薄转发**。
 *
 * ★ 为什么是薄转发而不是在 Java 里重写一遍：
 *   问诊树、相关性闸门、检验单判读的**唯一实现**在 Python 侧（`pasm_medical`）。
 *   在 Java 里再实现一遍就是同源两份代码 —— 这个项目已经因为同源两份代码
 *   坏过一次（相关性闸门只回植了一边）。所以这一层的职责只有三件：
 *   **鉴权、参数校验、透传**。
 *
 * ★ 所有响应都带 `requiresPhysicianConfirmation`：这是"辅助"定位的技术表达。
 */
@RestController
@RequestMapping("/api")
public class MedicalProxyController {

    private final PasmCognitionClient cog;

    public MedicalProxyController(PasmCognitionClient cog) {
        this.cog = cog;
    }

    @GetMapping("/encounters")
    public ResponseEntity<Map<String, Object>> encounters(@RequestParam String patientRef) {
        JsonNode r = cog.get("/api/encounters", Map.of("patientRef", patientRef));
        return ResponseEntity.ok(Map.of(
                "encounters", r.path("encounters"),
                "requiresPhysicianConfirmation", true));
    }

    @PostMapping("/consult/start")
    public ResponseEntity<Map<String, Object>> consultStart(@RequestBody Map<String, Object> body) {
        return fwd("/api/consult/start", body);
    }

    @PostMapping("/consult/answer")
    public ResponseEntity<Map<String, Object>> consultAnswer(@RequestBody Map<String, Object> body) {
        return fwd("/api/consult/answer", body);
    }

    @PostMapping("/consult/finish")
    public ResponseEntity<Map<String, Object>> consultFinish(@RequestBody Map<String, Object> body) {
        return fwd("/api/consult/finish", body);
    }

    /** 检验单识别：返回**待确认**结果 —— 未确认不会写入病历。 */
    @PostMapping("/lab/parse")
    public ResponseEntity<Map<String, Object>> labParse(@RequestBody Map<String, Object> body) {
        return fwd("/api/lab/parse", body);
    }

    @PostMapping("/lab/confirm")
    public ResponseEntity<Map<String, Object>> labConfirm(@RequestBody Map<String, Object> body) {
        return fwd("/api/lab/confirm", body);
    }

    @PostMapping("/critical-fact")
    public ResponseEntity<Map<String, Object>> criticalFact(@RequestBody Map<String, Object> body) {
        return fwd("/api/critical-fact", body);
    }

    private ResponseEntity<Map<String, Object>> fwd(String path, Map<String, Object> body) {
        JsonNode r = cog.post(path, body == null ? new HashMap<>() : body);
        Map<String, Object> out = new HashMap<>();
        r.fields().forEachRemaining(e -> out.put(e.getKey(),
                cog.toPlain(e.getValue())));
        out.put("requiresPhysicianConfirmation", true);
        return ResponseEntity.ok(out);
    }
}
