/**
 * 错误码 → 中文提示。
 *
 * 取值与后端 `com.opsdesk.common.ErrorCode` 逐项对齐（规格基线 §20.2），
 * 后端改码时这里必须同步改 —— 这是全前端唯一的中文提示表。
 */

/** 业务码常量，避免在业务代码里裸写 40100 这种魔法数字 */
export const ErrorCode = {
  /** 参数校验失败（HTTP 400） */
  PARAM_INVALID: 40001,
  /** 未登录 / token 无效或过期（HTTP 401）—— 会触发清 token + 跳登录 */
  UNAUTHORIZED: 40100,
  /** 已登录但无该 API 权限（HTTP 403） */
  FORBIDDEN: 40300,
  /** 有权限但超出数据范围（HTTP 403） */
  DATA_SCOPE_DENIED: 40301,
  /** 资源不存在（HTTP 404） */
  NOT_FOUND: 40400,
  /** 状态冲突：非法流转 / 乐观锁并发（HTTP 409） */
  CONFLICT: 40900,
  /** 请求过于频繁（HTTP 429） */
  TOO_MANY_REQUESTS: 42900,
  /** 系统错误（HTTP 500） */
  SYSTEM_ERROR: 50000,
  /** 依赖服务不可用：AI / MQ（HTTP 503） */
  SERVICE_UNAVAILABLE: 50300,
} as const

/** code → 面向用户的中文文案（不要直接展示后端 message，措辞不统一） */
const ERROR_MESSAGES: Record<number, string> = {
  [ErrorCode.PARAM_INVALID]: '参数校验失败',
  [ErrorCode.UNAUTHORIZED]: '登录已过期',
  [ErrorCode.FORBIDDEN]: '没有该操作权限',
  [ErrorCode.DATA_SCOPE_DENIED]: '无权访问该数据',
  [ErrorCode.NOT_FOUND]: '资源不存在',
  [ErrorCode.CONFLICT]: '状态已变更，请刷新后重试',
  [ErrorCode.TOO_MANY_REQUESTS]: '操作过于频繁',
  [ErrorCode.SYSTEM_ERROR]: '系统错误',
  [ErrorCode.SERVICE_UNAVAILABLE]: '依赖服务暂不可用',
}

/**
 * 取错误码对应的中文文案。
 *
 * 已知 code 一律用本表（措辞统一）；**未知 code 用后端返回的 message 兜底**，
 * 两者都没有时退到「操作失败」。
 *
 * @param code 业务码（后端 `Result.code`）
 * @param fallback 后端返回的 message，未知 code 时作为兜底文案
 */
export function errorMessageOf(code: number, fallback?: string): string {
  const known = ERROR_MESSAGES[code]
  if (known) return known
  const text = fallback?.trim()
  return text ? text : '操作失败'
}
