/**
 * 路由表。
 *
 * 结构：`/login` 独立（Layout 外），其余全部挂在 `DefaultLayout` 下 ——
 * 这样侧边栏/顶栏只渲染一次，切页不会整屏重绘。
 *
 * ⚠️ 菜单**不在本文件**：菜单树（分组、顺序、图标）在 `router/menu.ts`，
 * 这里只负责「URL → 组件」+ 给守卫用的 `meta.menuPerm`。
 * 两边的菜单路径必须一致，`assertMenuRoutes()` 在开发环境会校验并告警。
 *
 * ⚠️ 守卫不在本文件安装，见 `router/guard.ts`（由 `main.ts` 在 `app.use(router)` 之前装）。
 */

import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

import { MENU_TREE, type MenuNode } from './menu'

/** 路由 meta 的类型增强，让 `to.meta.title` / `to.meta.menuPerm` 有类型 */
declare module 'vue-router' {
  interface RouteMeta {
    /** 浏览器标题 & 面包屑 */
    title?: string
    /** 免登录访问（当前只有登录页） */
    public?: boolean
    /**
     * 进入该路由所需的 MENU 权限码。
     * 不设 = 不做菜单级校验（如 /403 本身）。
     */
    menuPerm?: string
  }
}

/** 所有未实现页面共用一个占位组件 */
const PlaceholderPage = () => import('@/views/PlaceholderPage.vue')
/** 布局：侧边栏 + 顶栏 + 内容区 */
const DefaultLayout = () => import('@/layouts/DefaultLayout.vue')

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/LoginView.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/',
    component: DefaultLayout,
    redirect: '/dashboard',
    children: [
      // ---------- 工作台（F7-07：员工端首页，登录后的默认落地页） ----------
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/EmployeeHome.vue'),
        meta: { title: '工作台', menuPerm: 'menu:dashboard' },
      },

      // ---------- 工单中心 ----------
      {
        path: 'tickets',
        name: 'TicketMine',
        component: () => import('@/views/ticket/TicketList.vue'),
        meta: { title: '我的工单', menuPerm: 'menu:ticket:mine' },
      },
      {
        path: 'tickets/create',
        name: 'TicketCreate',
        component: () => import('@/views/ticket/TicketCreate.vue'),
        meta: { title: '提交工单', menuPerm: 'menu:ticket:create' },
      },
      {
        path: 'tickets/manage',
        name: 'TicketManage',
        component: PlaceholderPage,
        meta: { title: '工单管理', menuPerm: 'menu:ticket:manage' },
      },
      {
        /**
         * 工单详情（F7-06 实现页面）。
         *
         * ⚠️ **刻意不设 `menuPerm`**：详情页不在菜单里，而且「我的工单」「待受理池」
         * 「工单管理」三个入口都会跳到这里，而 AGENT 并不持有 `menu:ticket:mine` ——
         * 挂任何一个菜单码都会把另一拨人挡在门外。
         *
         * 真正的边界在后端：`GET /api/tickets/{id}` 会做数据范围校验，
         * 看不到的工单返回 40301。前端放行 URL 不等于能看到数据。
         */
        path: 'tickets/:id',
        name: 'TicketDetail',
        component: () => import('@/views/ticket/TicketDetail.vue'),
        meta: { title: '工单详情' },
      },
      {
        path: 'pool',
        name: 'TicketPool',
        component: PlaceholderPage,
        meta: { title: '待受理池', menuPerm: 'menu:ticket:pool' },
      },

      // ---------- 知识库 ----------
      {
        path: 'knowledge',
        name: 'Knowledge',
        component: PlaceholderPage,
        meta: { title: '知识库', menuPerm: 'menu:knowledge' },
      },

      // ---------- 系统管理（Day 13 实现，先占位） ----------
      {
        path: 'system/users',
        name: 'SystemUser',
        component: PlaceholderPage,
        meta: { title: '用户管理', menuPerm: 'menu:system:user' },
      },
      {
        path: 'system/depts',
        name: 'SystemDept',
        component: PlaceholderPage,
        meta: { title: '部门管理', menuPerm: 'menu:system:dept' },
      },
      {
        path: 'system/roles',
        name: 'SystemRole',
        component: PlaceholderPage,
        meta: { title: '角色权限', menuPerm: 'menu:system:role' },
      },
      {
        path: 'system/sla',
        name: 'SystemSla',
        component: PlaceholderPage,
        meta: { title: 'SLA 配置', menuPerm: 'menu:system:sla' },
      },
      {
        path: 'system/knowledge',
        name: 'SystemKnowledge',
        component: PlaceholderPage,
        meta: { title: '知识库管理', menuPerm: 'menu:system:knowledge' },
      },
      {
        path: 'system/audit-logs',
        name: 'SystemAuditLog',
        component: PlaceholderPage,
        meta: { title: '审计日志', menuPerm: 'menu:system:audit' },
      },
      {
        path: 'system/dead-letters',
        name: 'SystemDeadLetter',
        component: PlaceholderPage,
        meta: { title: '死信管理', menuPerm: 'menu:system:mq' },
      },

      // ---------- 无权限 ----------
      {
        // 不设 menuPerm：否则无权限访问 /403 会再跳 /403，死循环
        path: '403',
        name: 'Forbidden',
        component: () => import('@/views/Forbidden.vue'),
        meta: { title: '无权限' },
      },
    ],
  },
  {
    // 兜底：未知路径回工作台（未登录时守卫会再把它转去登录页）
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard',
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
})

/**
 * 开发环境自检：菜单树里的每条路径都必须有对应路由。
 *
 * 防的是最典型的一种低级 bug —— 菜单能点，点了 404 / 被兜底重定向。
 * 生产构建里 `import.meta.env.DEV` 为 false，整段被摇掉。
 */
function assertMenuRoutes(): void {
  const paths = new Set(router.getRoutes().map((record) => record.path))

  const walk = (nodes: MenuNode[]): void => {
    for (const node of nodes) {
      if (node.children) {
        walk(node.children)
        continue
      }
      if (!paths.has(node.path)) {
        console.warn(`[menu] 菜单「${node.title}」指向的路由 ${node.path} 不存在`)
      }
    }
  }

  walk(MENU_TREE)
}

if (import.meta.env.DEV) {
  assertMenuRoutes()
}

export default router
