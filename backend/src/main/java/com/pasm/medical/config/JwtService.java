package com.pasm.medical.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最小可用的 <b>HS256 JWT</b> 签发 / 校验（零外部依赖）。
 *
 * <p>★ 为什么自己实现而不引 jjwt：本项目的构建环境经常离线，多一个三方依赖就多一个
 * "打包时拉不到包"的失败点；而 HS256 的规范只有「base64url(header).base64url(payload).HMAC-SHA256」
 * 三段，用 JDK 自带的 {@code javax.crypto.Mac} 就够了。**不实现 RS256/算法协商** ——
 * 只认 HS256，天然免疫 {@code alg=none} 与算法混淆攻击。
 *
 * <p>★ <b>令牌里放什么</b>：{@link Identity} 的全部字段（角色 / 科室 / 工号 / 患者 ref）。
 * 这样服务端不再需要"令牌 → 身份"的内存映射表，重启/多实例都不会掉登录态。
 * 注意 JWT 的 payload 只是 **base64，不是加密** —— 所以这里只放"本来就要下发给前端"的
 * 身份字段（{@code /api/auth/me} 也会返回同样的内容），不放密码、证件号等敏感信息。
 *
 * <p>⚠️ <b>密钥</b>：必须由 {@code medical.auth.jwt-secret} 注入（生产用环境变量）。
 * 没配置时启动会生成**临时随机密钥**并告警 —— 开发可用，但重启后旧令牌全部失效。
 * 这是刻意的：绝不内置一个"默认密钥"，否则等于所有人都能伪造管理员令牌。
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String HMAC_ALG = "HmacSHA256";
    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final ObjectMapper om;
    private final byte[] secret;
    private final long ttlSeconds;
    private final String issuer;

    /** 只用于生成 {@code jti}（令牌唯一 ID）—— 见 {@link #newJti()}。 */
    private final SecureRandom rnd = new SecureRandom();

    public JwtService(
            ObjectMapper om,
            @Value("${medical.auth.jwt-secret:}") String configuredSecret,
            @Value("${medical.auth.jwt-ttl-seconds:3600}") long ttlSeconds,
            @Value("${medical.auth.jwt-issuer:pasm-medical}") String issuer) {
        this.om = om;
        this.ttlSeconds = Math.max(60, ttlSeconds);
        this.issuer = (issuer == null || issuer.isBlank()) ? "pasm-medical" : issuer;

        String s = configuredSecret == null ? "" : configuredSecret.trim();
        if (s.isEmpty()) {
            byte[] buf = new byte[32];
            new SecureRandom().nextBytes(buf);
            this.secret = buf;
            log.warn("⚠ 未配置 medical.auth.jwt-secret —— 已生成临时密钥（重启后旧令牌全部失效）。"
                    + "生产必须通过环境变量 MEDICAL_AUTH_JWT_SECRET 注入至少 32 字节随机值。");
        } else {
            this.secret = s.getBytes(StandardCharsets.UTF_8);
            if (this.secret.length < 32) {
                log.warn("⚠ medical.auth.jwt-secret 只有 {} 字节（建议 ≥32 字节 / 256bit）——"
                        + "密钥过短会削弱 HS256 的安全性", this.secret.length);
            }
        }
    }

    /** 解析结果：身份 + 过期时间（过期时间用于登出后的撤销表清理）。 */
    public record Parsed(Identity identity, long expiresAtEpochSec) {
    }

    /**
     * 签发令牌。
     *
     * <p>声明（claim）与 {@link Identity} 一一对应：{@code sub/role/dept/staffId/
     * patientRef/name}，外加 {@code iss/iat/exp}。
     */
    public String issue(Identity id) {
        if (id == null || id.role() == null || id.username() == null || id.username().isBlank()) {
            throw new IllegalArgumentException("不能为未认证身份签发令牌");
        }
        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("sub", id.username());
        claims.put("role", id.role().name());
        if (id.department() != null) claims.put("dept", id.department());
        if (id.staffId() != null) claims.put("staffId", id.staffId());
        if (id.patientRef() != null) claims.put("patientRef", id.patientRef());
        if (id.displayName() != null) claims.put("name", id.displayName());
        // ★ jti 必须有：没有它，同一秒内为**同一身份**签发的两次令牌会逐字节相同，
        //   于是"登出其中一个"会把另一个也一起撤销（撤销表按 token 字符串匹配）。
        //   这是个不报错、只有并发登录/多标签页时才暴露的坑。
        claims.put("jti", newJti());
        claims.put("iat", now);
        claims.put("exp", now + ttlSeconds);

        String h = b64(header);
        String p = b64(claims);
        String signingInput = h + "." + p;
        return signingInput + "." + sign(signingInput);
    }

    /**
     * 校验并解析令牌；任何一步不合法都返回 {@code null}（调用方统一按 401 处理）。
     *
     * <p>校验项：结构三段 → 签名（常量时间比较）→ 算法固定 HS256 → {@code iss} → {@code exp}。
     */
    public Parsed parse(String token) {
        if (token == null) {
            return null;
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            String signingInput = parts[0] + "." + parts[1];
            String expected = sign(signingInput);
            // ★ 常量时间比较：普通 equals 会在第一个不同字节处提前返回，理论上有计时侧信道
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    parts[2].getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            JsonNode header = readJson(parts[0]);
            // ★ 只接受 HS256，杜绝 alg=none / 算法混淆
            if (header == null || !"HS256".equals(header.path("alg").asText())) {
                return null;
            }
            JsonNode c = readJson(parts[1]);
            if (c == null) {
                return null;
            }
            if (!issuer.equals(c.path("iss").asText())) {
                return null;
            }
            long exp = c.path("exp").asLong(0);
            if (exp <= Instant.now().getEpochSecond()) {
                return null;
            }
            String sub = c.path("sub").asText("");
            if (sub.isBlank()) {
                return null;
            }
            Role role;
            try {
                role = Role.valueOf(c.path("role").asText());
            } catch (IllegalArgumentException ex) {
                return null;
            }
            Identity id = new Identity(sub, role,
                    opt(c, "dept"), opt(c, "staffId"), opt(c, "patientRef"), opt(c, "name"));
            return new Parsed(id, exp);
        } catch (Exception ex) {
            // 令牌内容来自客户端，任何解析异常都等价于"无效令牌"，不能让它变成 500
            return null;
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 令牌唯一 ID：保证同一秒内重复登录签出的令牌也不相同。 */
    private String newJti() {
        byte[] b = new byte[16];
        rnd.nextBytes(b);
        return B64E.encodeToString(b);
    }

    private String b64(Map<String, Object> m) {
        try {
            return B64E.encodeToString(om.writeValueAsBytes(m));
        } catch (Exception ex) {
            throw new IllegalStateException("JWT 序列化失败", ex);
        }
    }

    private JsonNode readJson(String part) {
        try {
            return om.readTree(B64D.decode(part));
        } catch (Exception ex) {
            return null;
        }
    }

    private static String opt(JsonNode c, String field) {
        JsonNode n = c.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret, HMAC_ALG));
            return B64E.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("JWT 签名失败", ex);
        }
    }
}
