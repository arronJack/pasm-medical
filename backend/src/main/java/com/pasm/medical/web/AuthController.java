package com.pasm.medical.web;

import com.pasm.medical.config.TokenService;
import com.pasm.medical.domain.AiAudit;
import com.pasm.medical.service.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final TokenService tokens;
    private final AuditService audit;

    public AuthController(TokenService tokens, AuditService audit) {
        this.tokens = tokens;
        this.audit = audit;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest req) {
        if (req.username() == null || req.password() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "账号和密码不能为空"));
        }
        String[] r = tokens.login(req.username().trim(), req.password());
        if (r == null) {
            // 不区分"账号不存在"与"密码错" —— 避免被用来枚举账号
            return ResponseEntity.status(401).body(Map.of("error", "账号或密码不正确"));
        }
        // 登录留痕：谁、何时登入。审计库只增不改，是合规红线的落地。
        AiAudit a = new AiAudit();
        a.setActor(r[0]);
        a.setAction("login");
        a.setModelVersion("auth");
        a.setInputSnapshot("username=" + r[0] + ";role=" + r[1]);
        try { audit.record(a); } catch (Exception ignored) { }
        return ResponseEntity.ok(Map.of("token", r[0], "role", r[1], "displayName", r[2]));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @RequestHeader(value = "Authorization", required = false) String h) {
        if (h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            tokens.logout(h.substring(7).trim());
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    public record LoginRequest(String username, String password) {
    }
}
