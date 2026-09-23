package com.pasm.medical.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前请求身份的取用与**数据范围收窄**。
 *
 * <p>★ 为什么要单独抽一层：权限判定最容易写错的地方不是"判断逻辑"，而是
 * **"判据来自哪里"**。只要还允许"请求参数里写谁就看谁"，就一定会有接口漏判 ——
 * 而漏判不会报错，只会静默返回别人的数据。所以这里的原则是：
 * <b>能访问哪个患者，由身份决定；请求参数只是"我想访问谁"的申请，必须被收窄。</b>
 *
 * <p>用法（所有带 {@code patientRef}/{@code ref} 的接口都必须这样写）：
 * <pre>{@code
 * String ref = IdentityContext.scopedRef(req.patientRef());
 * if (ref == null) {
 *     return forbidden();            // 越权：不是本患者记录
 * }
 * }</pre>
 */
public final class IdentityContext {

    private IdentityContext() {
    }

    /** 当前请求的身份；未认证返回 {@link Identity#ANONYMOUS}（其 {@code role() == null}）。 */
    public static Identity current() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a instanceof IdentityAuthentication ia) {
            return ia.identity();
        }
        return Identity.ANONYMOUS;
    }

    /**
     * 把"请求里声明的患者标识"收窄成本身份**有权访问**的标识。
     *
     * <ul>
     *   <li><b>患者</b>：只能是自己，且允许不传（不传就等于本人 —— 前端不该被要求
     *       自己填一遍身份）。请求里写了**别人** → 返回 null，调用方必须 403。</li>
     *   <li><b>医护侧</b>：临床需要跨患者查阅，允许任意 ref；但**不允许为空**
     *       （空 = 不知道要看谁，不能靠猜）。</li>
     * </ul>
     *
     * @return 有权访问的 ref；null 表示越权或无法确定（调用方一律 403）
     */
    public static String scopedRef(String requested) {
        Identity id = current();
        if (!id.authenticated()) {
            return null;
        }
        String want = requested == null ? "" : requested.trim();
        if (!id.isPatient()) {
            return want.isEmpty() ? null : want;
        }
        String own = id.patientRef();
        // 患者账号没绑定 ref = 这个身份不可用。★ 这里是"拒绝"而不是"放行"：
        // 绑不上就放行，等于把一个账号变成"可访问任意患者"的后门。
        if (own == null || own.isBlank()) {
            return null;
        }
        return want.isEmpty() ? own : (own.equals(want) ? own : null);
    }

    /**
     * 数据范围限定的科室；{@code null} = 不限（全院）。
     *
     * <p>★ 返回 null 有两种含义 —— "全院范围"与"科室未分配"。调用方要区分时
     * 请用 {@link Identity#hospitalWide()}，**不要**拿 null 当"没有科室"用。
     */
    public static String scopeDepartment() {
        Identity id = current();
        if (!id.authenticated() || id.hospitalWide()) {
            return null;
        }
        String d = id.department();
        return d == null || d.isBlank() ? null : d;
    }

    /** 当前身份是否医护侧（能读后台的患者/审计/统计）。 */
    public static boolean clinicalSide() {
        return current().clinicalSide();
    }

    /**
     * 当前身份能否管理某科室的资料（资源级授权，见 {@link Identity#canManageDepartment}）。
     *
     * <p>★ 这是 URL 级授权之后的第二道闸门：URL 只能回答"科室管理员能不能调这个接口"，
     * 而"这条资料是不是他本科室的"必须在资源里按身份收窄 —— 否则一个科室管理员
     * 就能通过改 docKey 动到别的科室甚至全院通用的资料。
     */
    public static boolean canManageDepartment(String dept) {
        return current().canManageDepartment(dept);
    }
}
