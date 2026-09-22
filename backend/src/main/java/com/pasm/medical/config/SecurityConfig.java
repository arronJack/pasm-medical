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
 * <p>三条设计：
 * <ol>
 *   <li><b>无状态</b>（不用 session）：前端用 Bearer 令牌，服务端不存会话。</li>
 *   <li><b>按"能做什么"分级授权</b>，而不是"登录了就能看"（见下表）。</li>
 *   <li><b>CORS 只允许本机前端</b>（开发期）；生产要改成真实域名白名单，
 *       不要用 "*" —— 带凭证的跨域用通配符等于不设防。</li>
 * </ol>
 *
 * <table>
 *   <caption>授权矩阵</caption>
 *   <tr><th>路径</th><th>要求</th><th>为什么</th></tr>
 *   <tr><td>{@code /actuator/health}、{@code /api/auth/login}</td>
 *       <td>放行</td><td>探活与登录本身不能要令牌</td></tr>
 *   <tr><td>{@code /api/admin/config}</td><td>仅超管</td>
 *       <td>它决定「大模型指向何处」，即患者数据会不会出网 —— 这不是科室级权限</td></tr>
 *   <tr><td>{@code /api/admin/kb/**}</td><td>仅超管</td>
 *       <td>资料库是全院答案的依据；当前没有科室维度，给科室写权限 = 一个科室改全院</td></tr>
 *   <tr><td>{@code /api/admin/**}（其余，含读）</td><td>医护侧</td>
 *       <td>能读院内的姓名/过敏史/慢病 → 患者令牌必须 403（最小权限）</td></tr>
 *   <tr><td>其余 {@code /api/**}</td><td>已认证</td>
 *       <td>★ 但"已认证"**不等于**"能看任意患者"：带 {@code ref} 的接口一律再经
 *           {@link IdentityContext#scopedRef} 收窄到身份允许的范围</td></tr>
 * </table>
 *
 * <p>★ <b>两层判定的分工必须说清楚</b>：{@code authorizeHttpRequests} 只能回答
 * "这类角色能不能调用这个接口"（粗粒度，按 URL）；而"这个患者能不能看那个 `ref`"
 * 是**资源级**判定，URL 里看不出来，必须在接口里按身份收窄。
 * 只做第一层就是本系统之前的缺口：患者令牌本身合法，换个股就看到了别人的病历。
 *
 * <p>⚠️ 当前仍是**开发骨架**：令牌是进程内的不透明串、账号是写死的演示账号。
 * 生产必须换成真正方案（OAuth2/OIDC 或签名 JWT + 刷新机制）并接入医院统一身份；
 * 接入点已被收敛到 {@link TokenService}（把 {@code Identity} 改成从令牌 claim 解析即可）。
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
                // ★ 顺序敏感：更具体的规则必须写在前面，否则会被下面的通配吃掉
                .requestMatchers("/api/admin/config", "/api/admin/kb/**")
                    .hasRole("SUPER_ADMIN")
                // 后台其余（患者列表 / 审计 / 统计）：医护侧可读，范围在接口内按科室收窄
                .requestMatchers("/api/admin/**")
                    .hasAnyRole("DOCTOR", "DEPT_ADMIN", "SUPER_ADMIN")
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
