/**
 * 全局 axios 封装 —— 本单最核心的文件，后面 10 张单全部依赖它。
 *
 * 三条铁律：
 * 1. **业务代码只 import `request<T>()`，禁止在组件里直接 import axios。**
 * 2. 后端「HTTP 状态码与业务 code 对齐」（40100 → HTTP 401），所以失败响应走的是
 *    axios 的 **error 分支**，不存在「HTTP 200 里判 code」这种单一分支。
 * 3. 二进制请求（`responseType: 'blob'`）直接返回整个 `AxiosResponse`，不解包 ——
 *    下载场景要读响应头里的文件名。
 */

import axios, { type AxiosError, type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
// 显式引入样式：ElMessage 是函数式组件，不经过模板，unplugin 的按需样式不会覆盖到它。
// 漏了这一行，弹出的就是「裸文字」提示框。
import 'element-plus/es/components/message/style/css'

import { ErrorCode, errorMessageOf } from '@/constants/errorCode'
import { clearAuthStorage, getToken } from '@/utils/authStorage'
import type { ApiResult } from '@/types/common'

/**
 * 业务异常。
 *
 * 拦截器 reject 出去的都是这个类型，业务层可以 `err instanceof BizError` 后读 `err.code`
 * 做细分处理（例如 40400 显示空态而不是弹错误）。
 */
export class BizError extends Error {
  /** 后端业务码；网络层错误（超时 / 断网）固定为 -1 */
  code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'BizError'
    this.code = code
  }
}

/** 网络层错误（超时 / 断网 / 后端没起）的统一文案 */
const NETWORK_ERROR_MESSAGE = '网络异常，请稍后重试'

/** 网络层错误对应的伪业务码 */
const NETWORK_ERROR_CODE = -1

/**
 * 401 并发去重标志位。
 *
 * 首屏 5 个请求同时 401 时，只弹一次提示、只跳一次 /login。
 * ⚠️ 必须在**跳转之前**置位，否则 5 个请求会在 await 期间全部穿过去。
 * 登录成功后由 `resetUnauthorizedFlag()` 复位，否则重新登录后再过期就不跳转了。
 */
let unauthorizedHandled = false

/**
 * 复位 401 去重标志。
 *
 * 只在**登录成功后**调用（见 `stores/user.ts` 的 `applyLogin`），
 * 不要在其他地方随手调，否则会破坏去重。
 */
export function resetUnauthorizedFlag(): void {
  unauthorizedHandled = false
}

/** 创建实例：所有请求走 /api 前缀，10s 超时（AI 接口单独放宽，见后续工单） */
const service = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

// ==================== 请求拦截器 ====================

service.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

// ==================== 响应拦截器 ====================

/**
 * 从失败响应里抠出 `{ code, message }`。
 *
 * ⚠️ blob 请求失败时 `response.data` 是 **Blob 不是 JSON**，直接读 `.code` 拿到的是
 * `undefined`（这是下载/导出场景最经典的坑）。必须先 `await blob.text()` 再 JSON.parse。
 */
async function readErrorPayload(response: AxiosResponse): Promise<{ code: number; message: string }> {
  const body: unknown = response.data
  let parsed: unknown = body

  if (body instanceof Blob) {
    try {
      parsed = JSON.parse(await body.text()) as unknown
    } catch {
      return { code: 0, message: '' }
    }
  }

  if (parsed && typeof parsed === 'object') {
    const result = parsed as { code?: unknown; message?: unknown }
    return {
      code: typeof result.code === 'number' ? result.code : 0,
      message: typeof result.message === 'string' ? result.message : '',
    }
  }

  return { code: 0, message: '' }
}

/**
 * 40100 的统一处理：清登录态 → 提示一次 → 跳 /login 一次。
 *
 * 用动态 import 拿 router / store，是为了避免
 * `request.ts → router → views → api → request.ts` 的循环依赖在模块初始化阶段炸掉。
 */
async function handleUnauthorized(message: string): Promise<void> {
  clearAuthStorage()

  if (unauthorizedHandled) return
  unauthorizedHandled = true

  ElMessage.error(message)

  try {
    const [{ default: router }, { useUserStore }] = await Promise.all([
      import('@/router'),
      import('@/stores/user'),
    ])
    useUserStore().reset()
    if (router.currentRoute.value.path !== '/login') {
      await router.replace('/login')
    }
  } catch {
    // router / pinia 还没就绪（极端情况）时退回整页跳转，保证不会卡在原地
    window.location.replace('/login')
  }
}

service.interceptors.response.use(
  // 这里的 `any` 是 axios 类型决定的、唯一躲不掉的一处：
  // 拦截器的签名要求返回 AxiosResponse，而我们要把整个响应换成「解包后的 data」。
  // 用 any 把它挡在拦截器边界上，业务侧只经 `request<T>()` 拿类型 —— 全项目仅此一处。
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (response: AxiosResponse): any => {
    // a) 二进制：原样返回整个响应，业务层要读 Content-Disposition 和 blob 本体
    if (response.config.responseType === 'blob') {
      return response
    }

    const body = response.data as ApiResult<unknown> | null | undefined

    // 非统一响应体（204 空响应 / 第三方接口）直接给原始数据，不做拆包
    if (!body || typeof body !== 'object' || typeof body.code !== 'number') {
      return response.data
    }

    // b) JSON 且 code === 0：只把 data 交给业务层
    if (body.code === 0) {
      return body.data
    }

    // 防御性分支：后端 HTTP 与 code 对齐，正常不会走到这里；
    // 万一将来某个接口破了约定，也不能静默吞掉
    const message = errorMessageOf(body.code, body.message)
    if (!(response.config as RequestConfig).silent) {
      ElMessage.error(message)
    }
    return Promise.reject(new BizError(body.code, message))
  },

  async (error: AxiosError): Promise<never> => {
    const response = error.response

    // 网络层错误：超时 / 断网 / 后端没起，此时没有 response
    if (!response) {
      ElMessage.error(NETWORK_ERROR_MESSAGE)
      return Promise.reject(new BizError(NETWORK_ERROR_CODE, NETWORK_ERROR_MESSAGE))
    }

    const { code, message: backendMessage } = await readErrorPayload(response)

    // 抠不出业务码，说明响应体不是后端统一格式 —— 请求在代理 / 网关层就挂了
    // （典型：后端没起，Vite proxy 直接回 500 且没有 JSON body）。
    // 后端的每一个失败响应都带 code，所以「没有 code」可以放心当成网络问题。
    if (code === 0) {
      const isGatewayFailure = response.status >= 500
      const message = isGatewayFailure ? NETWORK_ERROR_MESSAGE : `请求失败（HTTP ${response.status}）`
      ElMessage.error(message)
      return Promise.reject(new BizError(isGatewayFailure ? NETWORK_ERROR_CODE : response.status, message))
    }

    // 已知 code 用本地中文表；未知 code 用后端 message；都没有才用 HTTP 状态兜底
    const message = errorMessageOf(code, backendMessage || `请求失败（HTTP ${response.status}）`)

    const options = (error.config ?? {}) as RequestConfig

    if (code === ErrorCode.UNAUTHORIZED && !options.skipAuthRedirect) {
      await handleUnauthorized(message)
    } else if (!options.silent) {
      ElMessage.error(message)
    }

    return Promise.reject(new BizError(code, message))
  },
)

/**
 * 请求配置的扩展项。
 *
 * 默认行为（两个开关都不传）就是 F7-01 定下的全局策略：
 * 40100 → 清 token + 跳登录 + 弹提示；其余失败 → 弹提示。
 */
export interface RequestConfig extends AxiosRequestConfig {
  /**
   * 401 时不触发「清 token + 跳 /login」。
   *
   * 只给**登录接口**用：密码输错时后端返回的也是 40100，
   * 若走全局策略，用户会先看到「登录已过期」再看到「用户名或密码错误」，
   * 而且会白清一次 token、白跳一次路由。
   */
  skipAuthRedirect?: boolean
  /**
   * 失败时不自动弹提示，由调用方决定文案。
   *
   * 同样给登录页用 —— 「40100 = 登录已过期」这套全局文案在登录场景是错的，
   * 登录失败必须显示「用户名或密码错误」（且不能区分账号不存在与密码错）。
   */
  silent?: boolean
}

/**
 * 业务层唯一的请求入口。
 *
 * 泛型 `T` 是**解包后的业务数据**类型（不是 AxiosResponse）：
 *
 * ```ts
 * const user = await request<LoginUserVO>({ url: '/auth/me' })
 * ```
 *
 * 例外：`responseType: 'blob'` 时拦截器返回的是整个 `AxiosResponse`，
 * 此时请写 `request<AxiosResponse<Blob>>({ ..., responseType: 'blob' })`（见 `utils/download.ts`）。
 *
 * 失败时 reject 一个 `BizError`（已弹过提示，业务层通常不用再处理）。
 */
export function request<T>(config: RequestConfig): Promise<T> {
  return service.request(config) as unknown as Promise<T>
}
