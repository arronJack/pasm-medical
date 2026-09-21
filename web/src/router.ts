import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken } from './api'
import LoginView from './views/LoginView.vue'
import ConsultView from './views/ConsultView.vue'
import AdminView from './views/AdminView.vue'

export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/login' },
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/consult', component: ConsultView, meta: { role: 'patient' } },
    { path: '/admin', component: AdminView, meta: { role: 'staff' } },
  ],
})

// 未登录一律回登录页 —— 患者数据不允许匿名访问
router.beforeEach((to) => {
  if (to.meta.public) return true
  if (!getToken()) return { path: '/login', query: { next: to.fullPath } }
  return true
})
