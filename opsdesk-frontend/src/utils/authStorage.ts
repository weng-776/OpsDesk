/**
 * 登录态的本地存储（localStorage）。
 *
 * 为什么单独一个文件，而不是直接写在 `stores/user.ts` 里：
 * `utils/request.ts` 的请求拦截器要在**不 import store** 的前提下取到 token
 * （store → api → request 已经是一条链，request 再反向 import store 就是循环依赖，
 * 模块初始化顺序会炸）。所以把「读 token」这件事下沉到这里，两边都用它。
 *
 * ⚠️ 三个 key 故意用最朴素的名字（`token` / `user` / `permissions`）：
 * 验收时手动 `localStorage.setItem('token', 'xxx')` 刷新，请求头就应该带上
 * `Authorization: Bearer xxx`。
 */

import type { LoginUserVO } from '@/types/auth'

/** token 的 localStorage key */
export const TOKEN_KEY = 'token'
/** 用户信息的 localStorage key（JSON 字符串） */
export const USER_KEY = 'user'
/** 权限码数组的 localStorage key（JSON 字符串） */
export const PERMISSIONS_KEY = 'permissions'

/** 读 token；没有则返回 null */
export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

/** 写 token；传空字符串等价于清除 */
export function setToken(token: string): void {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

/** 读本地缓存的用户信息（解析失败一律当没有） */
export function getStoredUser(): LoginUserVO | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as LoginUserVO
  } catch {
    return null
  }
}

/** 写本地缓存的用户信息；传 null 等价于清除 */
export function setStoredUser(user: LoginUserVO | null): void {
  if (user) {
    localStorage.setItem(USER_KEY, JSON.stringify(user))
  } else {
    localStorage.removeItem(USER_KEY)
  }
}

/** 读本地缓存的权限码列表 */
export function getStoredPermissions(): string[] {
  const raw = localStorage.getItem(PERMISSIONS_KEY)
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw) as unknown
    return Array.isArray(parsed) ? (parsed as string[]) : []
  } catch {
    return []
  }
}

/** 写本地缓存的权限码列表；传空数组等价于清除 */
export function setStoredPermissions(permissions: string[]): void {
  if (permissions.length > 0) {
    localStorage.setItem(PERMISSIONS_KEY, JSON.stringify(permissions))
  } else {
    localStorage.removeItem(PERMISSIONS_KEY)
  }
}

/**
 * 清空登录态。
 *
 * 401 / 登出 / 手动重置时调它，三个 key 一起清 —— 只清 token 会留下
 * 「有用户信息但没 token」的脏状态，刷新后界面会闪一下旧数据。
 */
export function clearAuthStorage(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  localStorage.removeItem(PERMISSIONS_KEY)
}
