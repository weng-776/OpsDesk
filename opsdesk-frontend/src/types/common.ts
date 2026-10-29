/**
 * 通用类型 —— 全站所有接口的响应外壳与分页契约。
 *
 * 后端 `com.opsdesk.common.Result<T>` 的 JSON 形状：
 * `{ code, message, data, success }`（success 由 `isSuccess()` 序列化而来）。
 * 这里多写一个可选 `success` 只是为了和真实报文对齐，业务代码判断成败只看 `code === 0`。
 */

/**
 * 统一响应体。
 *
 * ⚠️ `code === 0` 才是成功。失败时后端的 **HTTP 状态码与 code 是对齐的**
 * （40100 → HTTP 401、40300 → HTTP 403 …），所以失败响应会落进 axios 的 error 分支，
 * 而不是「HTTP 200 + code !== 0」。异常分支的统一处理见 `utils/request.ts`。
 */
export interface ApiResult<T> {
  /** 业务码，0 = 成功；其余见 `constants/errorCode.ts` */
  code: number
  /** 提示信息，成功时固定为 "success" */
  message: string
  /** 业务数据，失败时为 null 或字段级错误 Map */
  data: T
  /** 后端 `Result#isSuccess()` 序列化结果，仅作参考，不要用它判成败 */
  success?: boolean
}

/**
 * 分页结果。
 *
 * ⚠️ `page` 从 **1** 开始（不是 0）；`size` 上限 **50**，前端不要传更大的值，
 * 需要「全部」时请改接口而不是把 size 调大。
 */
export interface PageResult<T> {
  /** 当前页数据 */
  list: T[]
  /** 总条数 */
  total: number
  /** 当前页码，从 1 开始 */
  page: number
  /** 每页条数，上限 PAGE_SIZE_MAX */
  size: number
}

/** 分页 size 的硬上限，与后端校验保持一致；页面里别写死数字 50 */
export const PAGE_SIZE_MAX = 50

/** 分页查询的公共入参（各业务接口的查询 DTO 在此基础上扩展） */
export interface PageQuery {
  /** 页码，从 1 开始 */
  page?: number
  /** 每页条数，≤ PAGE_SIZE_MAX */
  size?: number
}
