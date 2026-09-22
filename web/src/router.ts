import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken } from './api'
import { ensureIdentity, landingFor, sideOf } from './identity'
import LoginView from './views/LoginView.vue'
import ConsultView from './views/ConsultView.vue'
import AdminView from './views/AdminView.vue'

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/login' },
    { path: '/login', component: LoginView, meta: { public: true } },
    // `side` 与身份的四角色对应：患者侧 / 医护侧
    { path: '/consult', component: ConsultView, meta: { side: 'patient' } },
    { path: '/admin', component: AdminView, meta: { side: 'clinical' } },
  ],
})

/**
 * 守卫做两件事：① 未登录回登录页；② **按身份把用户送回自己的那个工作区**。
 *
 * ★ 为什么要有 ②：把患者放进 `/admin` 只会得到一串 403 和"没有权限"的红字，
 *   看起来像系统坏了。界面的分流只是体验 —— 真正的拒绝在服务端（患者换 ref
 *   读别人记录一律 403），所以这里判错也不会越权，只会让人困惑。
 */
router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  if (!getToken()) {
    return { path: '/login', query: { next: to.fullPath } }
  }
  const id = await ensureIdentity()
  if (!id) {
    // 令牌在但身份取不到（已失效 / 被清除）→ 回登录页，别带着空身份继续跑
    return { path: '/login', query: { next: to.fullPath } }
  }
  const wanted = to.meta.side as 'patient' | 'clinical' | undefined
  const mine = sideOf(id)
  if (wanted && mine && wanted !== mine) {
    return { path: landingFor(id) }
  }
  return true
})
