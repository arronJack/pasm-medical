package com.pasm.medical.config;

/**
 * 一次已认证的身份。
 *
 * <p>★ <b>这是"身份 → 业务实体"的映射，也是本系统一切权限判定的唯一依据</b>。
 * 在这之前，登录只返回一个角色字符串，账号 {@code patient} 与业务实体
 * {@code demo-patient-001} 之间**没有任何绑定** —— 于是"这个患者只能看自己的记录"
 * 这条最基础的规则**根本无法表达**，{@code /api/patient/**} 只能退化成
 * "登录了就能看"（患者令牌换个 ref 就能读别人的病历）。
 *
 * <p>把 {@code patientRef} / {@code department} 放进身份对象后，权限判定就不再依赖
 * "调用方有没有老实传对参数"，而是**从身份里取**——这是安全边界该有的方向。
 *
 * <p>⚠️ <b>本记录是"当前身份源"的投影，不是数据模型</b>。生产接 OIDC 时，
 * 把这些字段改成从令牌声明（claim）解析即可，调用方（{@link IdentityContext}、
 * 各 Controller）**不需要改** —— 这也是当初把它抽出来的目的。
 *
 * @param username    账号（审计里的操作者，必须是它 —— 绝不能是令牌本身）
 * @param role        角色，见 {@link Role}
 * @param department  所属科室；患者为 null
 * @param staffId     工号；患者为 null
 * @param patientRef  绑定的患者档案标识；**只有患者角色有**，医护为 null
 * @param displayName 展示名
 */
public record Identity(String username,
                       Role role,
                       String department,
                       String staffId,
                       String patientRef,
                       String displayName) {

    /** 未认证（拿不到令牌时用）。不要拿它去做权限判断 —— 先判 {@code role() == null}。 */
    public static final Identity ANONYMOUS =
            new Identity("", null, null, null, null, "");

    public boolean authenticated() {
        return role != null;
    }

    public boolean isPatient() {
        return role == Role.PATIENT;
    }

    /** 是否全院数据范围。 */
    public boolean hospitalWide() {
        return role != null && role.hospitalWide();
    }

    /** 是否医护侧。 */
    public boolean clinicalSide() {
        return role != null && role.clinicalSide();
    }

    /** 是否科室管理员。 */
    public boolean isDeptAdmin() {
        return role == Role.DEPT_ADMIN;
    }

    /**
     * 当前身份能否管理某科室的资料（P1-2：资料库按科室授权）。
     *
     * <ul>
     *   <li><b>超级管理员</b>（全院范围）：可管理任意科室，也包括"全院通用"（空）资料；</li>
     *   <li><b>科室管理员</b>：只能管理<b>本科室</b>资料；**不能**管理全院通用（空）
     *       或其它科室的资料 —— 否则等于让一个科室改掉全院所有科室的答案依据；</li>
     *   <li>其余角色（患者 / 医护）：资料库与其无关，返回 false。</li>
     * </ul>
     *
     * @param dept 资料归属科室；空或 null 表示"全院通用"
     */
    public boolean canManageDepartment(String dept) {
        if (role == null) {
            return false;
        }
        if (role.hospitalWide()) {
            return true;
        }
        if (role == Role.DEPT_ADMIN) {
            if (dept == null || dept.isBlank()) {
                return false;                  // 科室管理员不能动全院通用资料
            }
            return dept.equals(this.department);
        }
        return false;                          // 患者 / 医护：资料库与其无关
    }

    /**
     * 本项目前该身份能看的数据范围（给界面显示用）。
     *
     * <p>★ 做成可读文案而不只是一个枚举：界面上不写清"你现在看到的是哪个范围"，
     * 使用者会把"本科室的数字"当成"全院的数字"—— 统计口径问题里最常见的误解。
     */
    public String scopeLabel() {
        if (role == null) {
            return "未认证";
        }
        if (role == Role.PATIENT) {
            return "本人（" + (patientRef == null || patientRef.isBlank() ? "未绑定" : patientRef) + "）";
        }
        if (role.hospitalWide()) {
            return "全院";
        }
        return "本科室：" + (department == null || department.isBlank() ? "未分配" : department);
    }
}
