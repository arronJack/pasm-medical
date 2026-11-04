package com.pasm.medical.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开发期的令牌服务。
 *
 * <p>★ <b>令牌形态：签名 JWT（HS256，见 {@link JwtService}）</b>。身份直接写在令牌声明里，
 * 服务端不再维护"令牌 → 身份"的内存映射 —— 重启、多实例部署都不会把已登录用户踢下线。
 * 登出通过一张**撤销表**（token → 过期时间）实现，过期项会被顺带清理。
 *
 * <p>⚠️ <b>账号源仍是写死的演示账号</b>（且默认关闭）。生产必须接入医院统一身份
 * （OIDC/OAuth2）：把外部 IdP 返回的声明映射成 {@link Identity} 后，同样交给
 * {@link JwtService#issue} 签发即可 —— 下游 {@link IdentityContext} / 各 Controller 一行不用改。
 *
 * <p>★ <b>演示账号默认关闭</b>（{@code medical.auth.demo-login-enabled}）。
 * 只有 {@code dev} 档显式打开（见 {@code application-dev.yml}）。为什么不是"默认开着、
 * 生产再记得关"：写死的 {@code patient/123456} 一旦跟随生产部署，
 * 任何人都能以患者身份登入并读到接口返回的病历 —— 这类事故的共同成因都是
 * "默认开着，忘了关"。默认关闭意味着**忘记配置的后果是登不进去**（可发现、可恢复），
 * 而不是数据泄露（不可发现、不可恢复）。
 *
 * <p>★ <b>账号必须带业务实体绑定</b>：登录返回的不只是角色，而是完整的
 * {@link Identity}（含 {@code patientRef} / {@code department}）。没有这层绑定，
 * "患者只能看自己的记录"这条规则无法表达，只能退化成"登录了就能看"。
 * 之前 {@code patient} 账号与 {@code demo-patient-001} 这个 ref 就是对不上的。
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    /**
     * 演示账号（**仅演示开关打开时可用**）。
     *
     * <pre>
     * patient   / 123456 → 患者，绑定 ref = demo-patient-001
     * doctor    / 123456 → 医护人员，科室 = 发热门诊
     * deptadmin / 123456 → 科室管理员，科室 = 发热门诊
     * staff     / 123456 → 超级管理员，全院
     * </pre>
     *
     * ★ 科室取"发热门诊"是有意的：演示数据里 {@code demo-patient-001} 有一条该科室的就诊，
     * 这样"医护侧只看本科室"这条范围规则在界面上**看得见效果**（而不是一片空白，
     * 让人误以为功能坏了）。
     */
    private static final Map<String, DemoAccount> DEMO_USERS = buildDemoUsers();

    /**
     * 登出撤销表：token → 过期时间（epoch 秒）。
     *
     * <p>★ JWT 本身是无状态的，签出去就"有效到过期"。要让登出**立即生效**，
     * 就需要一张撤销表。它只在用户主动登出时增长，且过期项会在下次操作时清理 ——
     * 规模远小于"所有在线会话"。
     */
    private final Map<String, Long> revoked = new ConcurrentHashMap<>();

    private final JwtService jwt;
    private final boolean demoLoginEnabled;

    public TokenService(
            @Value("${medical.auth.demo-login-enabled:false}") boolean demoLoginEnabled,
            JwtService jwt) {
        this.demoLoginEnabled = demoLoginEnabled;
        this.jwt = jwt;
        if (demoLoginEnabled) {
            log.warn("⚠ 演示账号已启用（{}，密码写死）—— 只允许在 dev 档使用；"
                            + "生产必须改接医院统一身份（OIDC/OAuth2 或签名 JWT）",
                    String.join(" / ", DEMO_USERS.keySet()));
        } else {
            log.info("演示账号已禁用：除 /api/auth/login 外无可用身份源。"
                    + "生产需接入医院统一身份（OIDC/OAuth2 或签名 JWT）后才有可登录的账号。");
        }
    }

    private static Map<String, DemoAccount> buildDemoUsers() {
        Map<String, DemoAccount> m = new LinkedHashMap<>();
        m.put("patient", new DemoAccount("123456", Role.PATIENT, "示例患者",
                null, null, "demo-patient-001"));
        m.put("doctor", new DemoAccount("123456", Role.DOCTOR, "示例医师",
                "发热门诊", "doc-001", null));
        m.put("deptadmin", new DemoAccount("123456", Role.DEPT_ADMIN, "发热门诊管理员",
                "发热门诊", "adm-001", null));
        m.put("staff", new DemoAccount("123456", Role.SUPER_ADMIN, "系统管理员",
                null, "adm-000", null));
        return Map.copyOf(m);
    }

    /** 演示账号定义（仅演示开关打开时可用）。 */
    private record DemoAccount(String password, Role role, String displayName,
                               String department, String staffId, String patientRef) {
    }

    /** 登录结果：令牌 + 身份。★ 返回的是身份对象，不再是散装的字符串数组。 */
    public record LoginResult(String token, Identity identity) {
    }

    /**
     * 登录成功返回 {@link LoginResult}；失败返回 null。
     *
     * <p>失败**不区分**"用户不存在"与"密码错"，也不暴露"演示账号是否启用" ——
     * 三者对外都是同一个 401，避免被用来枚举账号或探测部署形态。
     */
    public LoginResult login(String username, String password) {
        if (!demoLoginEnabled) {
            return null;
        }
        DemoAccount u = DEMO_USERS.get(username);
        if (u == null || !u.password().equals(password)) {
            return null;
        }
        Identity id = new Identity(username, u.role(), u.department(), u.staffId(),
                u.patientRef(), u.displayName());
        // ★ 身份写进令牌声明：服务端不再存会话，重启/多实例都不会掉登录态
        return new LoginResult(jwt.issue(id), id);
    }

    /** 校验令牌，返回身份；无效 / 过期 / 已撤销 一律返回 null（调用方统一按 401 处理）。 */
    public Identity verify(String token) {
        if (token == null) {
            return null;
        }
        purgeRevoked();
        if (revoked.containsKey(token)) {
            return null;
        }
        JwtService.Parsed p = jwt.parse(token);
        return p == null ? null : p.identity();
    }

    /**
     * 登出：把令牌放进撤销表，等它自然过期后清理。
     *
     * <p>★ 为什么不是"删会话"：JWT 是无状态的，服务端根本没有会话可删。
     * 撤销表是让"登出立即生效"的最小代价方案（只记录主动登出的令牌，不是所有在线会话）。
     */
    public void logout(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        JwtService.Parsed p = jwt.parse(token);
        long exp = p == null ? 0L : p.expiresAtEpochSec();
        if (exp > Instant.now().getEpochSecond()) {
            revoked.put(token, exp);
        }
        purgeRevoked();
    }

    /** 清理已过期的撤销项 —— 不加这一步，撤销表会随登出次数无限增长。 */
    private void purgeRevoked() {
        if (revoked.size() < 256) {
            return;
        }
        long now = Instant.now().getEpochSecond();
        revoked.entrySet().removeIf(e -> e.getValue() == null || e.getValue() <= now);
    }
}
