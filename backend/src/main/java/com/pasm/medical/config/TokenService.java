package com.pasm.medical.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
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
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);

    private static final SecureRandom RND = new SecureRandom();

    /** 演示账号：username -> {password, role, displayName}。仅在演示开关打开时可用。 */
    private static final Map<String, String[]> DEMO_USERS = Map.of(
            "patient", new String[]{"123456", "patient", "示例患者"},
            "staff", new String[]{"123456", "staff", "示例医师"});

    /** token -> {username, role, displayName} */
    private final Map<String, String[]> sessions = new ConcurrentHashMap<>();

    private final boolean demoLoginEnabled;

    public TokenService(
            @Value("${medical.auth.demo-login-enabled:false}") boolean demoLoginEnabled) {
        this.demoLoginEnabled = demoLoginEnabled;
        if (demoLoginEnabled) {
            log.warn("⚠ 演示账号已启用（staff/patient，密码写死）—— 只允许在 dev 档使用；"
                    + "生产必须改接医院统一身份（OIDC/OAuth2 或签名 JWT）");
        } else {
            log.info("演示账号已禁用：除 /api/auth/login 外无可用身份源。"
                    + "生产需接入医院统一身份（OIDC/OAuth2 或签名 JWT）后才有可登录的账号。");
        }
    }

    /**
     * 登录成功返回 {@code {token, role, displayName}}；失败返回 null。
     *
     * <p>失败**不区分**"用户不存在"与"密码错"，也不暴露"演示账号是否启用" ——
     * 三者对外都是同一个 401，避免被用来枚举账号或探测部署形态。
     */
    public String[] login(String username, String password) {
        if (!demoLoginEnabled) {
            return null;
        }
        String[] u = DEMO_USERS.get(username);
        if (u == null || !u[0].equals(password)) {
            return null;
        }
        byte[] buf = new byte[24];
        RND.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        String[] info = new String[]{username, u[1], u[2]};
        sessions.put(token, info);
        return new String[]{token, u[1], u[2]};
    }

    /** 校验令牌，返回 {username, role, displayName}，无效则 null。 */
    public String[] verify(String token) {
        return token == null ? null : sessions.get(token);
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }
}
