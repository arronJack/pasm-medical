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
 *     其余 api 路径一律要令牌。
 *  3. **CORS 只允许本机前端**（开发期）；生产要改成真实域名白名单，
 *     不要用 "*" —— 带凭证的跨域用通配符等于不设防。
 *
 * ⚠️ 当前是**开发骨架**：令牌是进程内的不透明串。生产必须换成真正方案
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
