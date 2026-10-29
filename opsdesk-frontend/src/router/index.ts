/**
 * 路由表。
 *
 * F7-02 起 `/login` 换成真正的登录页，其余仍是占位页：
 * 布局 / 菜单是 F7-03，工单列表是 F7-05 —— 不要在这里提前写业务页面。
 *
 * ⚠️ 守卫不在本文件安装，见 `router/guard.ts`（由 `main.ts` 在 `app.use(router)` 之前装）。
 */

import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

/** 路由 meta 的类型增强，让 `to.meta.title` 有类型 */
declare module 'vue-router' {
  interface RouteMeta {
    /** 浏览器标题后缀 */
    title?: string
    /** 免登录访问（当前只有登录页） */
    public?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/',
    name: 'PlaceholderHome',
    component: () => import('@/views/PlaceholderHome.vue'),
    meta: { title: '工程自检' },
  },
  {
    // 占位：F7-05 的工单列表落点。
    // 现在存在的意义是「有一条受保护路由可以被验收脚本直接访问」，
    // 用来证明未登录访问 /tickets 会跳 /login。
    path: '/tickets',
    name: 'PlaceholderTickets',
    component: () => import('@/views/PlaceholderTickets.vue'),
    meta: { title: '工单列表' },
  },
  {
    // 兜底：未知路径回首页，避免直接白屏
    path: '/:pathMatch(.*)*',
    redirect: '/',
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

export default router
