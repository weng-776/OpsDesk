/**
 * 菜单树 —— 侧边栏的**唯一数据来源**。
 *
 * 为什么单独一个文件而不是塞进 `router/index.ts`：
 * 路由表管「URL → 组件」，菜单树管「权限 → 可见项 + 分组 + 顺序 + 图标」，
 * 两个职责混在一个文件里，改菜单要翻路由、改路由要防菜单漂移。
 *
 * ⚠️ 节点顺序、分组、权限码全部对齐后端 `permission` 表（parent_id + sort），
 * **不要在这里写死「哪个角色看哪个菜单」** —— 可见性只由 `permissions` 决定，
 * 代码里出现 `if (role === 'EMPLOYEE')` 就是错的。
 */

import {
  Document,
  Key,
  Management,
  Memo,
  Odometer,
  OfficeBuilding,
  Plus,
  Reading,
  Setting,
  Tickets,
  Timer,
  User,
  Warning,
} from '@element-plus/icons-vue'

/**
 * 图标表。
 *
 * 模板里要用 `<component :is="...">` 动态渲染，而 unplugin 的按需解析只认**静态标签名**，
 * 所以必须显式 import 再查表。类型挂在 `MenuIcon` 上，写错图标名会在编译期报错。
 */
export const MENU_ICONS = {
  Odometer,
  Tickets,
  Memo,
  Plus,
  Management,
  Reading,
  Setting,
  User,
  OfficeBuilding,
  Key,
  Timer,
  Document,
  Warning,
} as const

/** 合法的图标名 */
export type MenuIcon = keyof typeof MENU_ICONS

/** 菜单节点 */
export interface MenuNode {
  /** 叶子节点是路由路径；分组节点是分组前缀（不可点击） */
  path: string
  /** 菜单文案 */
  title: string
  /** MENU 权限码（`menu:*`） */
  perm: string
  /** 图标名，必须是 MENU_ICONS 的 key */
  icon: MenuIcon
  /** 子菜单；有 children 即为分组 */
  children?: MenuNode[]
}

/**
 * 完整菜单树（15 个 MENU 码）。
 *
 * 未实现的页面统一指向 `PlaceholderPage`，保证每条菜单都点得进、不白屏。
 */
export const MENU_TREE: MenuNode[] = [
  {
    path: '/dashboard',
    title: '工作台',
    perm: 'menu:dashboard',
    icon: 'Odometer',
  },
  {
    path: '/ticket-group',
    title: '工单中心',
    perm: 'menu:ticket',
    icon: 'Tickets',
    children: [
      { path: '/tickets', title: '我的工单', perm: 'menu:ticket:mine', icon: 'Memo' },
      { path: '/tickets/create', title: '提交工单', perm: 'menu:ticket:create', icon: 'Plus' },
      { path: '/pool', title: '待受理池', perm: 'menu:ticket:pool', icon: 'Tickets' },
      { path: '/tickets/manage', title: '工单管理', perm: 'menu:ticket:manage', icon: 'Management' },
    ],
  },
  {
    path: '/knowledge',
    title: '知识库',
    perm: 'menu:knowledge',
    icon: 'Reading',
  },
  {
    path: '/system-group',
    title: '系统管理',
    perm: 'menu:system',
    icon: 'Setting',
    children: [
      { path: '/system/users', title: '用户管理', perm: 'menu:system:user', icon: 'User' },
      { path: '/system/depts', title: '部门管理', perm: 'menu:system:dept', icon: 'OfficeBuilding' },
      { path: '/system/roles', title: '角色权限', perm: 'menu:system:role', icon: 'Key' },
      { path: '/system/sla', title: 'SLA 配置', perm: 'menu:system:sla', icon: 'Timer' },
      { path: '/system/knowledge', title: '知识库管理', perm: 'menu:system:knowledge', icon: 'Document' },
      { path: '/system/audit-logs', title: '审计日志', perm: 'menu:system:audit', icon: 'Memo' },
      { path: '/system/dead-letters', title: '死信管理', perm: 'menu:system:mq', icon: 'Warning' },
    ],
  },
]
