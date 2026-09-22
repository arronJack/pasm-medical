package com.pasm.medical.config;

/**
 * 系统角色。
 *
 * <p><b>为什么要显式枚举而不是到处比字符串</b>：角色名会同时出现在 ① 令牌里的 claim、
 * ② Spring Security 的 authority、③ 前端路由分支、④ 文档与测试里。写成散落的字符串，
 * 改名时必漏一处，而漏掉的那处**不报错**，只是"某类人突然进不去了"。
 *
 * <p>★ <b>能力模型（P1-1 落地范围，如实标注）</b>：
 * <ul>
 *   <li>患者 —— 只能看**本人**的数据（ref 由令牌决定，不接受请求参数）</li>
 *   <li>医护人员 / 科室管理员 —— 属"医护侧"，可读后台的患者/审计/统计，范围限**本科室**</li>
 *   <li>超级管理员 —— 全院范围，且是**唯一**能改系统配置与资料库的角色
 *       （这两件事会改变"全院答案依据"和"患者数据会不会出网"，不属于科室权限）</li>
 * </ul>
 *
 * <p>⚠️ <b>医护人员与科室管理员目前共享同一档能力</b>，只差 {@code department} 绑定。
 * 二者真正的差异要等"科室实体 + 本科室资源"（P1-2）才有实质内容 ——
 * 现在硬造一个差异（比如让科室管理员管资料库）反而是错的：资料库当前没有科室维度，
 * 给了写权限就等于让一个科室改掉全院所有科室的答案依据。宁可不给，也不要给个假的。
 */
public enum Role {

    /** 患者：只能访问本人数据。 */
    PATIENT("ROLE_PATIENT", "患者", false),

    /** 医护人员：临床工作，可读本科室数据。 */
    DOCTOR("ROLE_DOCTOR", "医护人员", true),

    /** 科室管理员：本科室的管理职责（P1-2 起与医护拉开能力差异）。 */
    DEPT_ADMIN("ROLE_DEPT_ADMIN", "科室管理员", true),

    /** 超级管理员：全院范围 + 唯一能改配置与资料库的角色。 */
    SUPER_ADMIN("ROLE_SUPER_ADMIN", "超级管理员", true);

    private final String authority;
    private final String label;
    private final boolean clinicalSide;

    Role(String authority, String label, boolean clinicalSide) {
        this.authority = authority;
        this.label = label;
        this.clinicalSide = clinicalSide;
    }

    /** Spring Security 用的 authority（必须以 {@code ROLE_} 开头才能配 hasRole）。 */
    public String authority() {
        return authority;
    }

    /** 中文名，给界面与审计用。 */
    public String label() {
        return label;
    }

    /** 是否医护侧（能读后台的患者 / 审计 / 统计）。患者为 false。 */
    public boolean clinicalSide() {
        return clinicalSide;
    }

    /** 是否全院数据范围。**只有超级管理员是**。 */
    public boolean hospitalWide() {
        return this == SUPER_ADMIN;
    }

    /** 宽松解析：认不出来返回 null（调用方自行决定拒绝还是降级）。 */
    public static Role of(String name) {
        if (name == null) {
            return null;
        }
        String t = name.trim();
        for (Role r : values()) {
            if (r.name().equalsIgnoreCase(t) || r.authority.equalsIgnoreCase(t)) {
                return r;
            }
        }
        return null;
    }
}
