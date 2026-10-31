/**
 * 路由守卫（F7-02 红区 —— 写漏等于「未登录可进所有页面」）。
 *
 * 四件事，顺序不能乱：
 * 1. 已登录访问 /login → 直接回首页（避免登录后又看到登录页）
 * 2. 无 token → 跳 /login，并把原目标塞进 `?redirect=`，登录后能回跳
 * 3. 有 token 但用户信息没回源 → 先 `/me` 再放行；**失败必须清 token 并跳登录**
 * 4. 持有该路由的 MENU 权限码才放行，否则跳 `/403`（不是静默回首页）
 *
 * ⚠️ 为什么第 3 步要回源而不是信 localStorage：
 * 规格 §2.2 明确「管理员改角色后无需重新登录即生效」，
 * 所以权限以服务端为准，每次刷新都要打一次 `/me`（`profileLoaded` 保证只打一次）。
 */

import type { Router } from 'vue-router'

import { useUserStore } from '@/stores/user'

/** 登录页路径 */
const LOGIN_PATH = '/login'

/** 无权限页路径 */
const FORBIDDEN_PATH = '/403'

/**
 * 免登录白名单。
 *
 * 只放登录页。以后若加 404 / 500 / 维护页，也在这里登记，
 * **不要**用「路径以 /xxx 开头」这种模糊判断，容易漏。
 */
const PUBLIC_PATHS: ReadonlySet<string> = new Set([LOGIN_PATH])

/**
 * 把原目标路径包成 `?redirect=` 查询参数。
 *
 * 首页（`/`）不记 —— 登录后本来就回首页，带上反而让 URL 变脏。
 */
function buildRedirect(fullPath: string): Record<string, string> {
  return fullPath && fullPath !== '/' ? { redirect: fullPath } : {}
}

/**
 * 安装全局守卫。必须在 `app.use(router)` **之前**调用，
 * 否则首次导航已经跑完，守卫装上去也拦不住。
 */
export function setupRouterGuard(router: Router): void {
  router.beforeEach(async (to) => {
    const userStore = useUserStore()

    // ① 已登录还去登录页 → 回首页
    if (PUBLIC_PATHS.has(to.path)) {
      return userStore.token ? { path: '/' } : true
    }

    // ② 没 token → 去登录页
    if (!userStore.token) {
      return { path: LOGIN_PATH, query: buildRedirect(to.fullPath) }
    }

    // ③ 有 token 但用户信息没回源 → 先 /me
    //    失败时清 token + 跳登录：不能「带着失效 token 进系统」，那会看到满屏空数据
    if (!userStore.profileLoaded) {
      try {
        await userStore.ensureProfile()
      } catch {
        userStore.reset()
        return { path: LOGIN_PATH, query: buildRedirect(to.fullPath) }
      }
    }

    // ④ 菜单权限：持有该路由的 MENU 码才放行
    //    ⚠️ 无权限必须跳 /403，**不能静默回首页** —— 用户会以为自己点错了，反复重试
    //    ⚠️ 这里只挡「页面可见性」，真正的数据安全靠后端每个接口的 API 权限校验
    const menuPerm = to.meta.menuPerm
    if (menuPerm && !userStore.hasPerm(menuPerm)) {
      return { path: FORBIDDEN_PATH }
    }

    return true
  })

  // SPA 切路由不会自动改 title，这个小事漏了很难被发现
  router.afterEach((to) => {
    document.title = to.meta.title ? `${to.meta.title} · OpsDesk` : 'OpsDesk'
  })
}
