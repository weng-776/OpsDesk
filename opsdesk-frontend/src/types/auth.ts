/**
 * 认证相关类型 —— 字段名与后端 JSON **完全一致**，不许改名、不许驼峰化。
 *
 * 对应后端：
 * - `com.opsdesk.auth.vo.LoginVO`
 * - `com.opsdesk.auth.vo.LoginUserVO`
 */

/**
 * 登录响应（`POST /api/auth/login` 的 `data`）。
 */
export interface LoginVO {
  /** 三段式 JWT（HS256，exp = 8 小时） */
  token: string
  /** 当前登录用户信息 */
  user: LoginUserVO
}

/**
 * 登录用户信息。
 *
 * 同时用于 `POST /api/auth/login` 的 `data.user` 与 `GET /api/auth/me` 的 `data`。
 *
 * ⚠️ `permissions` 只用于**界面显隐**（菜单 / 按钮），不是安全边界 ——
 * 后端每个接口都独立校验权限，前端藏起来只是体验优化，不是防护。
 */
export interface LoginUserVO {
  /** 用户 ID */
  id: number
  /** 登录名 */
  username: string
  /** 姓名 */
  nickname: string
  /** 邮箱 */
  email: string
  /** 部门 ID，可能为空 */
  departmentId: number | null
  /** 部门名称，可能为空 */
  departmentName: string | null
  /** 状态：1 启用 / 0 禁用 */
  status: number
  /** 角色码列表，如 ["AGENT"] */
  roles: string[]
  /** 权限码列表，如 ["ticket:list"] */
  permissions: string[]
}

/** 登录请求体（`POST /api/auth/login` 的 body） */
export interface LoginRequest {
  /** 登录名 */
  username: string
  /** 明文密码，仅走 HTTPS / 本地 proxy，不做前端加密 */
  password: string
}
