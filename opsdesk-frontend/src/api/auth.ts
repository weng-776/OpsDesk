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
 */
export function login(data: LoginRequest): Promise<LoginVO> {
  return request<LoginVO>({ url: '/auth/login', method: 'post', data })
}

/**
 * 当前登录用户（`GET /api/auth/me`）。
 *
 * 未登录 / token 失效会被后端拦成 40100（HTTP 401），由拦截器统一清 token + 跳登录。
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
