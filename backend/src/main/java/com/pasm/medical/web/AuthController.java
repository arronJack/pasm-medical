package com.pasm.medical.web;

import com.pasm.medical.config.Identity;
import com.pasm.medical.config.IdentityContext;
import com.pasm.medical.config.Role;
import com.pasm.medical.config.TokenService;
import com.pasm.medical.domain.AiAudit;
import com.pasm.medical.service.AuditService;
import com.pasm.medical.service.DepartmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final TokenService tokens;
    private final AuditService audit;
    private final DepartmentService departments;

    public AuthController(TokenService tokens, AuditService audit, DepartmentService departments) {
        this.tokens = tokens;
        this.audit = audit;
        this.departments = departments;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest req) {
        if (req.username() == null || req.password() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "账号和密码不能为空"));
        }
        String username = req.username().trim();
        // ★ 先用"账号密码是否正确"做一次判定：错误一律 401（不区分账号不存在/密码错），
        //   正确之后才谈科室 —— 否则会变成"用错误密码也能探出账号是医护"。
        if (!tokens.credentialsValid(username, req.password())) {
            return ResponseEntity.status(401).body(Map.of("error", "账号或密码不正确"));
        }
        Role role = tokens.roleOf(username);
        if (role == Role.DOCTOR || role == Role.DEPT_ADMIN) {
            if (req.department() == null || req.department().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "该账号需选择坐诊科室"));
            }
            if (!departments.existsActive(req.department())) {
                return ResponseEntity.badRequest().body(Map.of("error", "科室不存在或已停用"));
            }
        }
        TokenService.LoginResult r = tokens.login(username, req.password(), req.department());
        if (r == null) {
            return ResponseEntity.status(401).body(Map.of("error", "账号或密码不正确"));
        }
        Identity id = r.identity();
        // 登录留痕：谁、何时登入。审计库只增不改，是合规红线的落地。
        AiAudit a = new AiAudit();
        // ★ 记的是**账号名**，不是令牌。这里原先是 a.setActor(r[0])，而 r[0] 是令牌串本身
        //   （返回值第 0 位是 token），后果有两个：① 审计里查不出"是谁登的"；
        //   ② **访问令牌被明文落进了审计表**。两者都不报错，只有真去查审计才会发现。
        a.setActor(id.username());
        a.setAction("login");
        a.setModelVersion("auth");
        a.setInputSnapshot("username=" + id.username()
                + ";role=" + id.role().name()
                + ";department=" + (id.department() == null ? "-" : id.department()));
        try { audit.record(a); } catch (Exception ignored) { }
        Map<String, Object> out = identityBody(id);
        out.put("token", r.token());
        return ResponseEntity.ok(out);
    }

    /**
     * 当前身份。
     *
     * <p>★ 前端**必须**用它来决定"能进哪个工作区、显示什么数据范围"，
     * 而不是自己猜或读登录时存下的副本 —— 令牌还在、身份可能已经不同（换人、改权限）。
     * 界面据此分支只是体验；真正的边界在服务端（见 {@link IdentityContext}）。
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me() {
        Identity id = IdentityContext.current();
        if (!id.authenticated()) {
            return ResponseEntity.status(401).body(Map.of("error", "未认证"));
        }
        return ResponseEntity.ok(identityBody(id));
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @RequestHeader(value = "Authorization", required = false) String h) {
        if (h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            tokens.logout(h.substring(7).trim());
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 身份 → 响应体。登录与 {@code /me} 共用，避免两处字段漂移。 */
    private Map<String, Object> identityBody(Identity id) {
        Role role = id.role();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", id.username());
        m.put("role", role == null ? "" : role.name());
        m.put("roleLabel", role == null ? "" : role.label());
        m.put("displayName", id.displayName() == null ? "" : id.displayName());
        m.put("department", id.department() == null ? "" : id.department());
        m.put("staffId", id.staffId() == null ? "" : id.staffId());
        // 患者只看到自己绑定的那个 ref —— 这就是"患者只能看本人"在界面上的表达
        m.put("patientRef", id.patientRef() == null ? "" : id.patientRef());
        m.put("scopeLabel", id.scopeLabel());
        m.put("hospitalWide", id.hospitalWide());
        m.put("clinicalSide", id.clinicalSide());
        // 前端据此禁用按钮；**服务端仍会独立拒绝**，不能只靠界面隐藏
        m.put("canWriteKb", role == Role.SUPER_ADMIN);
        m.put("canWriteConfig", role == Role.SUPER_ADMIN);
        m.put("canManageDepartments", role == Role.SUPER_ADMIN);
        return m;
    }

    /** {@code department} 仅医护/科室管理员需要（本次坐诊科室）。 */
    public record LoginRequest(String username, String password, String department) {
    }
}
