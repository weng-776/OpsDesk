/**
 * 认证接口（API 文档 §4）。
 *
 * 全部通过 `request<T>()` 走统一拦截器 —— 组件里禁止直接 import axios。
 * 返回类型来自 `types/auth.ts`，字段名与后端 JSON 完全一致。
 */

import { request } from '@/utils/request'
import type { LoginRequest, LoginUserVO, LoginVO } from '@/types/auth'

/**
 * 登录（`POST /api/auth/login`）。
 *
 * 失败：40001 参数校验失败 / 40100 账号密码错或账号禁用 / 42900 失败次数超限锁定中。
 *
 * ⚠️ 这里必须带 `skipAuthRedirect` + `silent`：
 * 登录失败后端回的也是 **40100**，若走全局策略会「清 token + 跳登录 + 弹『登录已过期』」，
 * 用户看到的就是一条错文案，还白跳一次路由。
 * 文案由登录页按 `BizError.code` 决定（40100 → 用户名或密码错误）。
 */
export function login(data: LoginRequest): Promise<LoginVO> {
  return request<LoginVO>({
    url: '/auth/login',
    method: 'post',
    data,
    skipAuthRedirect: true,
    silent: true,
  })
}

/**
 * 当前登录用户（`GET /api/auth/me`）。
 *
 * 未登录 / token 失效会被后端拦成 40100（HTTP 401），由拦截器统一清 token + 跳登录。
 *
 * ⚠️ 这是权限的**唯一可信来源**：规格 §2.2 要求「管理员改角色后无需重新登录即生效」，
 * 所以每次刷新都必须回源本接口，不能只信 localStorage 里缓存的 permissions。
 */
export function me(): Promise<LoginUserVO> {
  return request<LoginUserVO>({ url: '/auth/me', method: 'get' })
}

/**
 * 登出（`POST /api/auth/logout`）。
 *
 * 后端把当前 token 的 jti 写进 Redis 黑名单，TTL = token 剩余有效期。
 * 前端无论成功失败都要清本地登录态（见 `stores/user.ts` 的 `logout`）。
 */
export function logout(): Promise<void> {
  return request<void>({ url: '/auth/logout', method: 'post' })
}
