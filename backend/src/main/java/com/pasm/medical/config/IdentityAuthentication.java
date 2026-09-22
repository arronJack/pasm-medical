package com.pasm.medical.config;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * 携带 {@link Identity} 的认证对象。
 *
 * <p>★ <b>为什么继承而不是直接把 Identity 当 principal 塞进去</b>：
 * {@code AbstractAuthenticationToken.getName()} 在 principal 不是 String/UserDetails 时
 * 会返回 {@code principal.toString()}。审计里的"操作者"正是取 {@code auth.getName()}，
 * 若直接塞 record，审计表里的操作者会变成整条记录的 toString（含科室、工号等），
 * 而**这不会报错** —— 只会让审计里再也查不出"是谁"。
 *
 * <p>所以这里覆盖 {@code getName()} 明确返回账号名：**让所有既有调用点
 * （{@code AuditService.currentActor} 等）一行不改就继续正确**。
 */
public class IdentityAuthentication extends UsernamePasswordAuthenticationToken {

    private final Identity identity;

    public IdentityAuthentication(Identity identity) {
        super(identity, null, List.of(new SimpleGrantedAuthority(identity.role().authority())));
        this.identity = identity;
    }

    @Override
    public String getName() {
        return identity.username();
    }

    public Identity identity() {
        return identity;
    }
}
