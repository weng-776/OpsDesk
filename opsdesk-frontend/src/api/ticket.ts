/**
 * 工单接口。
 *
 * 全部通过 `request<T>()` 走统一拦截器 —— 组件里禁止直接 import axios。
 */

import { downloadFile } from '@/utils/download'
import { request } from '@/utils/request'
import type { PageResult } from '@/types/common'
import type {
  AttachmentUploadVO,
  AttachmentVO,
  CommentCreatePayload,
  TicketCommentVO,
  TicketCreatePayload,
  TicketCreatedVO,
  TicketDetailVO,
  TicketHistoryVO,
  TicketListVO,
  TicketQueryParams,
} from '@/types/ticket'

/**
 * 我的工单（`GET /api/tickets/mine`）。
 *
 * ⚠️ **F7-04 的数据源只能是这个**，不能用 `list()` 冒充：
 * `/mine` 强制 `creator_id = 当前用户`（数据范围 SELF，ADMIN 调它也只能看到自己创建的），
 * 而 `/tickets` 是按角色数据范围走的（ADMIN 会拿到全部），两者语义完全不同。
 *
 * 权限：`ticket:list`（EMPLOYEE / AGENT / ADMIN 都持有）。
 */
export function mine(params: TicketQueryParams = {}): Promise<PageResult<TicketListVO>> {
  return request<PageResult<TicketListVO>>({ url: '/tickets/mine', method: 'get', params })
}

/**
 * 工单列表（`GET /api/tickets`）—— 按角色数据范围过滤。
 *
 * 本单（F7-04 我的工单）**不用**它，留给 F7-05 的「待受理池 / 工单管理」。
 * 放在这里是因为派工单要求 `api/ticket.ts` 同时提供 mine / list 两个方法。
 */
export function list(params: TicketQueryParams = {}): Promise<PageResult<TicketListVO>> {
  return request<PageResult<TicketListVO>>({ url: '/tickets', method: 'get', params })
}

/**
 * 创建工单（`POST /api/tickets`）。
 *
 * ⚠️ **必须带 `Idempotency-Key`**（规格 §2.5 / 基线 §23.3）：
 * 后端用 Redis `SETNX idem:ticket:{key}` 占位，同一个 key 只会建出一张工单。
 * 不带这个头，用户点两次提交 / 网络重试就会建出两张 —— 这是真实事故，不是理论风险。
 *
 * ⚠️ **同一个表单会话必须复用同一个 key**：重试时重新生成 key 等于没做幂等。
 * key 的生成与复用由调用方（`views/ticket/TicketCreate.vue`）负责，本函数只负责带上。
 *
 * @param payload 表单数据
 * @param idempotencyKey 幂等键（UUID），同一个表单会话内保持不变
 */
export function create(
  payload: TicketCreatePayload,
  idempotencyKey: string,
): Promise<TicketCreatedVO> {
  return request<TicketCreatedVO>({
    url: '/tickets',
    method: 'post',
    data: payload,
    headers: { 'Idempotency-Key': idempotencyKey },
    // 错误提示由页面自己出：40900 的全局文案是「状态已变更，请刷新后重试」，
    // 而创建场景下它的真实含义是「同一请求正在处理中，请勿重复提交」，
    // 后端原文比通用文案准确，所以这里关掉自动弹窗。
    silent: true,
  })
}

// ==================== 详情页（F7-06） ====================

/**
 * 工单详情（`GET /api/tickets/{id}`）。
 *
 * 权限 `ticket:detail`。失败要区分两种：
 * - `40400` 工单不存在
 * - `40301` **存在但你看不到**（后端刻意先查存在性再判数据范围，防止用状态码探测 id）
 *
 * 所以页面**不能**一律说「工单不存在」。
 */
export function detail(id: number): Promise<TicketDetailVO> {
  return request<TicketDetailVO>({ url: `/tickets/${id}`, method: 'get' })
}

/**
 * 工单历史（`GET /api/tickets/{id}/history`）。
 *
 * ⚠️ 返回**数组不分页**，后端已按 `created_at ASC, id ASC` 排好，前端不要再排序。
 */
export function history(id: number): Promise<TicketHistoryVO[]> {
  return request<TicketHistoryVO[]>({ url: `/tickets/${id}/history`, method: 'get' })
}

/**
 * 评论列表（`GET /api/tickets/{id}/comments`）。
 *
 * ⚠️ 这个接口是**分页**的（默认 10 条/页），和附件/历史不一样。
 * ⚠️ `internal = true` 的评论 EMPLOYEE 查不到（后端 SQL 层过滤），前端不要再过滤。
 */
export function comments(
  id: number,
  params: { page?: number; size?: number } = {},
): Promise<PageResult<TicketCommentVO>> {
  return request<PageResult<TicketCommentVO>>({
    url: `/tickets/${id}/comments`,
    method: 'get',
    params,
  })
}

/**
 * 发表评论（`POST /api/tickets/{id}/comments`）。
 *
 * 权限 `ticket:comment`。`internal` 只有 AGENT / ADMIN 能设 true（EMPLOYEE 传 true 会被静默降级）。
 */
export function addComment(
  id: number,
  payload: CommentCreatePayload,
): Promise<TicketCommentVO> {
  return request<TicketCommentVO>({ url: `/tickets/${id}/comments`, method: 'post', data: payload })
}

/**
 * 附件列表（`GET /api/tickets/{id}/attachments`）。
 *
 * ⚠️ 返回**数组不分页**（后端 `ORDER BY created_at ASC, id ASC`）。
 */
export function attachments(id: number): Promise<AttachmentVO[]> {
  return request<AttachmentVO[]>({ url: `/tickets/${id}/attachments`, method: 'get' })
}

/**
 * 上传附件（`POST /api/tickets/{id}/attachments`）。
 *
 * - `multipart/form-data`，字段名固定 `file`
 * - 单文件 ≤10MB，扩展名白名单 `jpg/png/gif/pdf/txt/log/docx/xlsx`（**没有 jpeg**）
 * - 权限 `ticket:comment`
 *
 * ⚠️ **不要手动设 Content-Type**：交给浏览器自动带 boundary，手写会导致后端解析失败。
 * ⚠️ 超时要放宽：默认 10s 对 10MB 文件不够。
 *
 * @param onProgress 上传进度回调（0-100）
 */
export function uploadAttachment(
  id: number,
  file: File,
  onProgress?: (percent: number) => void,
): Promise<AttachmentUploadVO> {
  const form = new FormData()
  form.append('file', file)

  return request<AttachmentUploadVO>({
    url: `/tickets/${id}/attachments`,
    method: 'post',
    data: form,
    timeout: 60000,
    onUploadProgress: (event) => {
      if (onProgress && event.total) {
        onProgress(Math.round((event.loaded / event.total) * 100))
      }
    },
  })
}

// ==================== 待受理池（F8-01） ====================

/**
 * 接单（`POST /api/tickets/{id}/accept`）—— `OPEN → ASSIGNED`，处理人 = 当前用户。
 *
 * 权限 `ticket:accept`（AGENT / ADMIN）。
 *
 * ⚠️ **并发冲突**：两个 AGENT 同时接同一单时，后到的那位会收到 **40900**
 * （工单已经不是 `OPEN`，状态机判定为非法流转）。页面必须专门提示「已被他人接单」并刷新列表，
 * 不能笼统显示「操作失败」。
 *
 * `silent: true` 的原因：全局错误表对 40900 的文案是「状态已变更，请刷新后重试」，
 * 在这里不够具体，文案由调用方决定。
 */
export function accept(id: number): Promise<TicketDetailVO> {
  return request<TicketDetailVO>({
    url: `/tickets/${id}/accept`,
    method: 'post',
    silent: true,
  })
}

/**
 * 分派（`POST /api/tickets/{id}/assign`）—— `OPEN → ASSIGNED`，处理人 = 指定的人。
 *
 * 权限 `ticket:assign`（AGENT / ADMIN）。`assigneeId` 必须是启用的 AGENT / ADMIN，
 * 否则后端返回 40001。
 *
 * ⚠️ 前端拿不到「AGENT 列表」：`GET /api/users` 要 `user:list`，AGENT 调会 40300。
 * 所以分派弹窗让用户手填 ID（已与需求方确认的方案）。
 */
export function assign(id: number, assigneeId: number): Promise<TicketDetailVO> {
  return request<TicketDetailVO>({
    url: `/tickets/${id}/assign`,
    method: 'post',
    data: { assigneeId },
    silent: true,
  })
}

/**
 * 导出工单（`GET /api/tickets/export`）—— 响应是 **xlsx 二进制流**，不是 JSON。
 *
 * - 权限 `ticket:export`（AGENT / ADMIN，EMPLOYEE 没有）
 * - **不传 `page` / `size`**：导出不分页，传了也无效
 * - 后端上限 10000 条，超出**静默截断**（不报错）
 * - 空结果也会返回「只有表头」的 xlsx，不是失败
 * - 必须走 `downloadFile`（blob + Authorization），**不能 `window.open`**
 *
 * @param params 当前筛选条件（status / priority / keyword），不含分页参数
 */
export function exportTickets(
  params: Omit<TicketQueryParams, 'page' | 'size'> = {},
): Promise<void> {
  return downloadFile('/tickets/export', { ...params }, '工单导出.xlsx')
}
