/**
 * 工单相关类型。
 *
 * 字段与后端 `com.opsdesk.ticket.vo.TicketListVO` **逐项对齐**（已核对源码，不是抄文档）。
 *
 * ⚠️ 时间字段的真实形态：`TicketListVO` 上带 `@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")`，
 * 所以返回的是 `2026-10-27 21:21:36`（**空格分隔，不是 ISO 的 `T`**）。
 * 全局约定里说的「后端统一 ISO 带 T」在这张 VO 上不成立 —— `utils/format.ts` 两种都能解析，
 * 但不要在别处假设时间一定是 ISO。
 */

import type {
  SlaStateValue,
  TicketCategoryValue,
  TicketPriorityValue,
  TicketStatusValue,
  TicketTypeValue,
} from '@/constants/enums'

/**
 * 工单列表项（`GET /api/tickets` 与 `GET /api/tickets/mine` 共用）。
 *
 * 比详情 VO 少：没有描述、SLA 策略、AI 分析、附件 —— 列表一次最多 50 行，字段要克制。
 */
export interface TicketListVO {
  /** 工单 ID */
  id: number
  /** 工单号，如 TK202610270001 */
  ticketNo: string
  /** 标题 */
  title: string
  /** 类型 */
  type: TicketTypeValue
  /** 分类 */
  category: TicketCategoryValue
  /** 优先级 */
  priority: TicketPriorityValue
  /** 状态 */
  status: TicketStatusValue
  /** 创建人姓名 */
  creatorName: string
  /** 处理人姓名；**未分派时为 null** */
  assigneeName: string | null
  /** 解决时限的 SLA 状态 */
  slaResolutionState: SlaStateValue
  /** 解决截止时间 */
  resolutionDeadline: string | null
  /** 创建时间 */
  createdAt: string
}

/**
 * 工单列表查询参数。
 *
 * 后端 `TicketQuery` 支持 status / priority / category / type / keyword / assigneeId /
 * startTime / endTime / sort 一整套，本单只用到文档要求的两项（status + keyword）。
 * 其余留给 F7-05「工单管理」。
 *
 * ⚠️ `size` 上限 50：后端 `PageQuery.normalize()` 会把超限值**静默收敛**成 50，
 * 不会报错 —— 所以前端必须自己夹紧，否则用户选了 100 却只拿到 50 条，会以为是丢数据。
 */
export interface TicketQueryParams {
  /** 状态筛选 */
  status?: TicketStatusValue
  /** 关键字：后端匹配 ticket_no / title */
  keyword?: string
  /** 页码，从 1 开始 */
  page?: number
  /** 每页条数，≤ 50 */
  size?: number
}
