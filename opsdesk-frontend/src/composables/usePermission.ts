/**
 * 权限相关的组合式函数。
 *
 * ⚠️ **前端权限只是界面显隐，不是安全边界。**
 * 后端每个接口都独立校验 API 权限与数据范围（`@RequirePermission` + DataScope），
 * 前端把菜单藏起来、把按钮删掉，只是让用户少走弯路；
 * 手动改 localStorage 里的 permissions 骗不到任何数据，因为接口那一侧不认它。
 *
 * 两层权限模型（规格基线 §3.6）：
 * | 类型   | 权限码                    | 作用         | 前端用途                 |
 * |--------|---------------------------|--------------|--------------------------|
 * | `MENU` | `menu:ticket:mine` 等     | 页面/菜单可见 | 动态菜单 + 路由守卫       |
 * | `API`  | `ticket:assign` 等        | 接口可调      | `v-perm` 控制按钮显隐     |
 */

import { computed } from 'vue'

import { MENU_TREE, type MenuNode } from '@/router/menu'
import { useUserStore } from '@/stores/user'

/**
 * 过滤菜单树。
 *
 * 规则：
 * 1. 叶子节点：持有该 MENU 码才保留；
 * 2. 分组节点：**自身持有分组码 且 至少一个子项存活**才保留 ——
 *    避免「父菜单还在、点开是空的」这种最难看的状态。
 *
 * 导出成纯函数是为了能单测（不依赖 pinia）。
 */
export function filterMenus(tree: MenuNode[], has: (perm: string) => boolean): MenuNode[] {
  const result: MenuNode[] = []

  for (const node of tree) {
    if (!node.children) {
      if (has(node.perm)) result.push(node)
      continue
    }

    const children = filterMenus(node.children, has)
    if (children.length > 0 && has(node.perm)) {
      result.push({ ...node, children })
    }
  }

  return result
}

/**
 * 权限判断 + 当前用户可见菜单。
 *
 * 在组件 `setup` 里调用（内部要用 pinia 的 store）。
 */
export function usePermission() {
  const userStore = useUserStore()

  /** 是否拥有某个 API / MENU 权限码 */
  const hasPerm = (code: string): boolean => userStore.hasPerm(code)

  /** 是否拥有其中**任意一个**权限码（OR 语义，与 `v-perm` 的数组一致） */
  const hasAnyPerm = (codes: string[]): boolean => codes.some((code) => userStore.hasPerm(code))

  /** 是否拥有某个角色码 */
  const hasRole = (role: string): boolean => userStore.hasRole(role)

  /** 是否管理员 */
  const isAdmin = computed<boolean>(() => userStore.isAdmin)

  /** 当前用户可见的菜单树（已按 permissions 过滤） */
  const menus = computed<MenuNode[]>(() => filterMenus(MENU_TREE, userStore.hasPerm))

  return { hasPerm, hasAnyPerm, hasRole, isAdmin, menus }
}
