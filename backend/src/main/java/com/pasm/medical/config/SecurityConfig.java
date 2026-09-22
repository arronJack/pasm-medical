package com.pasm.medical.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 安全配置。
 *
 * 三条设计：
 *  1. **无状态**（不用 session）：前端用 Bearer 令牌，服务端不存会话。
 *  2. actuator/health 与 api/auth/login 放行（不加粗、避免出现连续星号斜杠），
 *     其余 api 路径一律要令牌；**`/api/admin/**` 额外要求 ROLE_STAFF**。
 *  3. **CORS 只允许本机前端**（开发期）；生产要改成真实域名白名单，
 *     不要用 "*" —— 带凭证的跨域用通配符等于不设防。
 *
 * <p>★ 为什么后台必须单独限 {@code ROLE_STAFF}，而不是"登录了就能看"：
 * {@code /api/admin/**} 能读出**全院**患者的姓名 / 过敏史 / 慢病，还能改
 * 「大模型指向何处」（决定患者数据会不会出网）。若只判"已认证"，
 * 一个患者令牌就能拿到全院数据 —— 这正是最小权限原则要挡的事。
 *
 * <p>⚠️ 尚未做到的一条（如实标注，不假装已解决）：{@code /api/patient/**} 目前只校验
 * 「已认证」，患者令牌理论上可以换 {@code ref} 去读别人的就诊记录。
 * 要修必须先有"令牌 → 该患者 ref"的映射，而当前演示账号没有这个映射
 * （用户名 {@code patient} 与 ref {@code demo-patient-001} 对不上）。
 * 生产接 OIDC 时应把 ref 绑定进令牌声明（claim），届时在过滤器里强制覆盖请求参数即可。
 *
 * <p>⚠️ 当前是**开发骨架**：令牌是进程内的不透明串。生产必须换成真正方案
 *    （OAuth2/OIDC 或签名 JWT + 刷新机制），并接入医院统一身份。
 */
@Configuration
public class SecurityConfig {

    private final TokenService tokens;

    public SecurityConfig(TokenService tokens) {
        this.tokens = tokens;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())                    // 无状态 + Bearer，不需要 CSRF
            .cors(cors -> cors.configurationSource(corsSource()))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/api/auth/login").permitAll()
                // 后台：能读全院患者、能改模型指向 —— 必须医护角色，患者令牌一律 403
                .requestMatchers("/api/admin/**").hasRole("STAFF")
                .requestMatchers("/api/**").authenticated()
                .anyRequest().authenticated())
            .addFilterBefore(new TokenAuthFilter(tokens),
                             UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private CorsConfigurationSource corsSource() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(List.of("http://127.0.0.1:5173", "http://localhost:5173"));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }
}
