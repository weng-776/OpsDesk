/**
 * 登录态 store（Pinia）。
 *
 * 管三件事：token、当前用户、权限码。
 *
 * ⚠️ **前端权限只用于界面显隐，不是安全边界。**
 * 后端每个接口都独立校验权限（`@RequirePermission` + 数据范围判定），
 * 前端把按钮藏起来只是体验优化 —— 手改 localStorage 里的 permissions 骗不到任何数据。
 *
 * ⚠️ token 必须落 localStorage：只放内存的话 F5 一下就掉登录态，是最常见的低级 bug。
 * 初始化时从 localStorage 恢复，读不到才算未登录。
 */

import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

import * as authApi from '@/api/auth'
import {
  clearAuthStorage,
  getStoredPermissions,
  getStoredUser,
  getToken,
  setStoredPermissions,
  setStoredUser,
  setToken,
} from '@/utils/authStorage'
import { resetUnauthorizedFlag } from '@/utils/request'
import type { LoginUserVO, LoginVO } from '@/types/auth'

export const useUserStore = defineStore('user', () => {
  // ==================== state（初始化即从 localStorage 恢复） ====================

  /** 当前 token；空字符串表示未登录 */
  const token = ref<string>(getToken() ?? '')

  /** 当前用户；刷新后先用本地缓存顶上，再由 fetchMe() 校正 */
  const user = ref<LoginUserVO | null>(getStoredUser())

  /** 权限码列表，来源是 user.permissions，单独存一份方便判断 */
  const permissions = ref<string[]>(getStoredPermissions())

  /**
   * 用户信息是否已从**服务端**回源过。
   *
   * ⚠️ 判据不能是 `user !== null` —— user 可能是 localStorage 里的旧缓存。
   * 规格 §2.2 要求「管理员改角色后无需重新登录即生效」，所以权限必须每次刷新都回源 `/me`，
   * 只有 login / fetchMe 拿到真实响应后才置 true（见路由守卫 `router/guard.ts`）。
   */
  const profileLoaded = ref(false)

  // ==================== getters ====================

  /** 角色码列表 */
  const roles = computed<string[]>(() => user.value?.roles ?? [])

  /** 是否已登录（有 token 就算，token 有效性由后端判定） */
  const isLoggedIn = computed<boolean>(() => Boolean(token.value))

  /** 是否管理员 */
  const isAdmin = computed<boolean>(() => roles.value.includes('ADMIN'))

  // ==================== 权限判断 ====================

  /**
   * 是否拥有某个权限码（如 `'ticket:export'`）。
   *
   * 只用来决定按钮 / 菜单是否渲染，**不要**把它当访问控制。
   */
  function hasPerm(code: string): boolean {
    return permissions.value.includes(code)
  }

  /** 是否拥有某个角色（如 `'AGENT'`） */
  function hasRole(role: string): boolean {
    return roles.value.includes(role)
  }

  // ==================== actions ====================

  /** 把用户信息写进 state 与 localStorage（**只应由服务端响应调用**） */
  function applyUser(nextUser: LoginUserVO): void {
    user.value = nextUser
    permissions.value = nextUser.permissions ?? []
    setStoredUser(nextUser)
    setStoredPermissions(permissions.value)
    profileLoaded.value = true
  }

  /** 登录成功后落地 token + 用户信息，并复位 401 去重标志 */
  function applyLogin(data: LoginVO): void {
    token.value = data.token
    setToken(data.token)
    applyUser(data.user)
    // 重新登录后，之前那次「401 已处理」的标记必须清掉，否则下次过期不跳转
    resetUnauthorizedFlag()
  }

  /**
   * 登录。失败时拦截器已弹过提示，这里直接让异常继续往上抛。
   */
  async function login(username: string, password: string): Promise<LoginVO> {
    const data = await authApi.login({ username, password })
    applyLogin(data)
    return data
  }

  /**
   * 拉取当前用户（刷新页面后用 token 换回最新的用户信息与权限）。
   *
   * 401 时由拦截器统一清 token + 跳登录，这里不需要 catch。
   */
  async function fetchMe(): Promise<LoginUserVO> {
    const currentUser = await authApi.me()
    applyUser(currentUser)
    return currentUser
  }

  /**
   * 确保用户信息已回源：已加载过就直接返回，否则调 `/me`。
   *
   * 路由守卫用这个，避免每次导航都打一次 `/me`（只在 `profileLoaded === false` 时打）。
   */
  async function ensureProfile(): Promise<LoginUserVO> {
    if (profileLoaded.value && user.value) return user.value
    return fetchMe()
  }

  /**
   * 登出：先通知后端把 token 拉黑，再清本地。
   *
   * 用 `finally` 保证「后端挂了 / token 已过期」也能清干净本地，
   * 否则用户会卡在「点了登出但还在登录态」的怪状态里。
   */
  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      reset()
    }
  }

  /** 只清本地登录态，不发请求（401 处理、登出兜底都走它） */
  function reset(): void {
    token.value = ''
    user.value = null
    permissions.value = []
    profileLoaded.value = false
    clearAuthStorage()
  }

  return {
    token,
    user,
    permissions,
    profileLoaded,
    roles,
    isLoggedIn,
    isAdmin,
    hasPerm,
    hasRole,
    applyLogin,
    applyUser,
    login,
    fetchMe,
    ensureProfile,
    logout,
    reset,
  }
})
