/**
 * 路由表 —— F7-01 只放一个占位页 + 一个 /login 占位。
 *
 * 真正的登录页是 F7-02，布局 / 菜单是 F7-03，别在这里提前做。
 * 路由守卫（未登录跳 /login、无权限跳 403）也在 F7-02 加。
 */

import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'PlaceholderHome',
    component: () => import('@/views/PlaceholderHome.vue'),
    meta: { title: '工程自检' },
  },
  {
    // 占位路由：F7-02 会把这里换成真正的登录页
    path: '/login',
    name: 'PlaceholderLogin',
    component: () => import('@/views/PlaceholderLogin.vue'),
    meta: { title: '登录' },
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
