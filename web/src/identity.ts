/**
 * 身份缓存与工作区路由。
 *
 * ★ 为什么单独一个模块而不是塞进 api.ts：
 *   路由守卫、顶栏、后台页三处都要问"我是谁、我能看多大范围"。如果各自去读登录时
 *   存在 localStorage 的副本，就会出现"登录后换了权限、界面还按老身份显示"这种
 *   **不会报错**的不一致。统一走这里，只有一个真相源（服务端 `/api/auth/me`）。
 */
import { api, getToken, type IdentityView } from './api'

let cached: IdentityView | null = null
let inflight: Promise<IdentityView | null> | null = null

/** 已缓存的身份（可能为 null = 还没取到或未登录）。同步取用，给模板用。 */
export function currentIdentity(): IdentityView | null {
    return cached
}

/**
 * 取当前身份（带缓存与并发合并）。
 *
 * @param force true 时强制重取（改权限、换账号后调用）
 */
export async function ensureIdentity(force = false): Promise<IdentityView | null> {
    if (!getToken()) {
        cached = null
        return null
    }
    if (cached && !force) {
        return cached
    }
    if (!inflight) {
        // 并发合并：一次页面加载里守卫与顶栏会同时问，别打两次接口
        inflight = api.me()
            .then((v) => { cached = v; return v })
            .catch(() => { cached = null; return null })
            .finally(() => { inflight = null })
    }
    return inflight
}

/** 清缓存（退出登录、401 被拒后调用）。 */
export function forgetIdentity(): void {
    cached = null
}

/** 该身份应该落到哪个工作区。 */
export function landingFor(id: IdentityView | null): string {
    if (!id) {
        return '/login'
    }
    // 患者 → 问诊工作区；医护侧（医护 / 科室管理员 / 超管）→ 后台
    return id.role === 'PATIENT' ? '/consult' : '/admin'
}

/** 该身份该看到哪个工作区：'patient' | 'clinical'。 */
export function sideOf(id: IdentityView | null): 'patient' | 'clinical' | null {
    if (!id) {
        return null
    }
    return id.role === 'PATIENT' ? 'patient' : 'clinical'
}
