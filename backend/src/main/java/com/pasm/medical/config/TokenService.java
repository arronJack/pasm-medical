package com.pasm.medical.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开发期的令牌服务。
 *
 * <p>⚠️ <b>仅骨架</b>：令牌存在内存里，重启即失效；账号是写死的演示账号。
 * 生产必须换成：医院统一身份（OIDC/OAuth2）或签名 JWT，并带过期与刷新。
 * 这里保留是因为「没有登录就进不去」这条约束本身要在代码里成立 ——
 * 至于用什么身份源，是接入时替换的实现细节。
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

    private static final SecureRandom RND = new SecureRandom();

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

    /** token → 身份 */
    private final Map<String, Identity> sessions = new ConcurrentHashMap<>();

    private final boolean demoLoginEnabled;

    public TokenService(
            @Value("${medical.auth.demo-login-enabled:false}") boolean demoLoginEnabled) {
        this.demoLoginEnabled = demoLoginEnabled;
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
        byte[] buf = new byte[24];
        RND.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        Identity id = new Identity(username, u.role(), u.department(), u.staffId(),
                u.patientRef(), u.displayName());
        sessions.put(token, id);
        return new LoginResult(token, id);
    }

    /** 校验令牌，返回身份；无效则 null。 */
    public Identity verify(String token) {
        return token == null ? null : sessions.get(token);
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }
}
