package com.pasm.medical.config;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开发期的令牌服务。
 *
 * ⚠️ **仅骨架**：令牌存在内存里，重启即失效；账号也是写死的演示账号。
 * 生产必须换成：医院统一身份（OIDC/OAuth2）或签名 JWT，并带过期与刷新。
 * 这里保留是因为「没有登录就进不去」这条约束本身要在代码里成立 ——
 * 至于用什么身份源，是接入时替换的实现细节。
 */
@Service
public class TokenService {

    private static final SecureRandom RND = new SecureRandom();

    /** 演示账号：username -> {password, role, displayName} */
    private static final Map<String, String[]> USERS = Map.of(
            "patient", new String[]{"123456", "patient", "示例患者"},
            "staff", new String[]{"123456", "staff", "示例医师"});

    /** token -> {username, role, displayName} */
    private final Map<String, String[]> sessions = new ConcurrentHashMap<>();

    /** 登录成功返回令牌；失败返回 null（不要把"用户不存在"和"密码错"分开回，避免枚举账号）。 */
    public String[] login(String username, String password) {
        String[] u = USERS.get(username);
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
