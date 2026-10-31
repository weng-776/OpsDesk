/**
 * 工单接口。
 *
 * 全部通过 `request<T>()` 走统一拦截器 —— 组件里禁止直接 import axios。
 */

import { request } from '@/utils/request'
import type { PageResult } from '@/types/common'
import type { TicketListVO, TicketQueryParams } from '@/types/ticket'

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
