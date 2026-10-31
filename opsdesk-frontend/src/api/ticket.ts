/**
 * 工单接口。
 *
 * 全部通过 `request<T>()` 走统一拦截器 —— 组件里禁止直接 import axios。
 */

import { request } from '@/utils/request'
import type { PageResult } from '@/types/common'
import type { TicketCreatePayload, TicketCreatedVO, TicketListVO, TicketQueryParams } from '@/types/ticket'

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
