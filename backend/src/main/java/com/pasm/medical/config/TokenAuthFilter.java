package com.pasm.medical.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 从 {@code Authorization: Bearer <token>} 里认令牌，认出来就放入安全上下文。
 *
 * <p>★ 放进去的是 {@link IdentityAuthentication}（携带完整 {@link Identity}），
 * 不是裸的角色字符串 —— 下游接口据此才能回答"这个患者只能看哪条记录"
 * （见 {@link IdentityContext#scopedRef}）。角色只决定"能不能进这个门"，
 * 而数据范围要靠身份里的业务实体绑定。
 */
public class TokenAuthFilter extends OncePerRequestFilter {

    private final TokenService tokens;

    public TokenAuthFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            Identity id = tokens.verify(h.substring(7).trim());
            if (id != null) {
                SecurityContextHolder.getContext()
                        .setAuthentication(new IdentityAuthentication(id));
            }
        }
        chain.doFilter(req, resp);
    }
}
