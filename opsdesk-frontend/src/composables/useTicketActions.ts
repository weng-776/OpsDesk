/**
 * 工单状态流转的公共逻辑（F8-02）。
 *
 * 把「动作元数据 + 调用 + 错误处理」集中在一处，详情页和待受理池共用，
 * 避免每个页面各写一份 40900 处理再走偏（F8-01 就踩过这个坑）。
 *
 * ⚠️ **`canOperate` 是渲染依据，不是安全边界**（§16.8 原文）。
 * 前端只负责「不显示必然失败的按钮」；每个流转接口后端都会独立校验
 * 权限 + 状态 + 数据范围，前端藏按钮不等于授权。
 */

import { ref } from 'vue'
import { ElMessage } from 'element-plus'

import * as ticketApi from '@/api/ticket'
import { ErrorCode } from '@/constants/errorCode'
import { BizError } from '@/utils/request'
import type { TicketDetailVO } from '@/types/ticket'

/** 按钮视觉层级 */
export type ActionLevel = 'primary' | 'default' | 'danger'

/**
 * 弹窗形态：
 * - `none`     直接执行（接单、开始处理）
 * - `confirm`  确认弹窗（ElMessageBox）
 * - `assignee` 选处理人（手填 ID，后端没有查 AGENT 列表的接口）
 * - `reason`   原因输入（撤销，必填 ≤200）
 */
export type DialogKind = 'none' | 'confirm' | 'assignee' | 'reason'

export interface TicketActionMeta {
  /** 与后端接口路径一致的动作名 */
  action: string
  /** 按钮文案 */
  label: string
  /** 视觉层级 */
  level: ActionLevel
  /** 弹窗形态 */
  dialog: DialogKind
  /** 确认弹窗里的说明 */
  tip?: string
  /** 成功提示 */
  successText: string
}

/**
 * 动作元数据表（11 个）。
 *
 * ⚠️ 请求体字段**不统一**，别把 remark 套到所有动作上：
 * - `assign` / `transfer` → `{ assigneeId }`
 * - `cancel` → `{ reason }`（必填）
 * - 其余 8 个 → **没有请求体**（后端签名里就没有 @RequestBody）
 *
 * 派工单要求给 transfer / hold / resume / resolve / reject 填备注，但后端不收，
 * 写了会被静默丢弃 —— 所以这里**不收集备注**（已与需求方确认）。
 */
export const TICKET_ACTION_META: Record<string, TicketActionMeta> = {
  accept: {
    action: 'accept',
    label: '接单',
    level: 'primary',
    dialog: 'none',
    successText: '接单成功',
  },
  start: {
    action: 'start',
    label: '开始处理',
    level: 'primary',
    dialog: 'none',
    successText: '已开始处理',
  },
  resolve: {
    action: 'resolve',
    label: '提交解决',
    level: 'primary',
    dialog: 'confirm',
    tip: '提交后工单进入「待确认」，等待提交人确认。',
    successText: '已提交，等待用户确认',
  },
  close: {
    action: 'close',
    label: '确认关闭',
    level: 'primary',
    dialog: 'confirm',
    tip: '关闭后工单结束，不能再流转。',
    successText: '工单已关闭',
  },
  resume: {
    action: 'resume',
    label: '恢复',
    level: 'primary',
    dialog: 'confirm',
    tip: '恢复后 SLA 继续计时，并顺延已暂停的时长。',
    successText: '已恢复，SLA 已顺延',
  },
  hold: {
    action: 'hold',
    label: '挂起',
    level: 'default',
    dialog: 'confirm',
    tip: '挂起后 SLA 暂停计时，等待提交人补充信息。',
    successText: '已挂起，SLA 已暂停',
  },
  transfer: {
    action: 'transfer',
    label: '转派',
    level: 'default',
    dialog: 'assignee',
    successText: '已转派',
  },
  assign: {
    action: 'assign',
    label: '分派',
    level: 'default',
    dialog: 'assignee',
    successText: '已分派',
  },
  reject: {
    action: 'reject',
    label: '未解决',
    level: 'danger',
    dialog: 'confirm',
    tip: '驳回后工单重新打开，回到处理中。',
    successText: '已重新打开',
  },
  cancel: {
    action: 'cancel',
    label: '撤销',
    level: 'danger',
    dialog: 'reason',
    successText: '工单已撤销',
  },
  'force-close': {
    action: 'force-close',
    label: '强制关闭',
    level: 'danger',
    dialog: 'confirm',
    tip: '强制关闭不可撤销，请确认。',
    successText: '工单已强制关闭',
  },
}

/** 按钮排序权重：主操作最左，危险最右 */
const LEVEL_WEIGHT: Record<ActionLevel, number> = { primary: 0, default: 1, danger: 2 }

/**
 * 按 `canOperate` 生成有序的按钮列表。
 *
 * 未知动作（后端将来加了新动作）兜底成默认样式 + 直接执行，不会崩也不会消失。
 */
export function buildActionButtons(canOperate: string[] | undefined | null): TicketActionMeta[] {
  if (!canOperate?.length) return []

  return canOperate
    .map((action) => {
      const meta = TICKET_ACTION_META[action]
      return (
        meta ?? {
          action,
          label: action,
          level: 'default' as ActionLevel,
          dialog: 'confirm' as DialogKind,
          successText: '操作成功',
        }
      )
    })
    .sort((a, b) => LEVEL_WEIGHT[a.level] - LEVEL_WEIGHT[b.level])
}

/**
 * 是不是「状态/可见性已经变了」这一类错误。
 *
 * ⚠️ 后端会返回**两种**码，必须都认：
 * - `40900` 状态已变（非法流转 / 并发冲突）
 * - `40301` 数据范围变了（比如工单被转派给别人，你已经看不见）
 *
 * 只认 40900 的后果见 F8-01 交接工单：跨部门被抢时提示「数据范围越权」，列表还不刷新。
 */
export function isStaleError(code: number): boolean {
  return code === ErrorCode.CONFLICT || code === ErrorCode.DATA_SCOPE_DENIED
}

/** 动作执行结果 */
export type ActionResult =
  /** 成功，`ticket` 是最新快照（直接用，不要再请求一次详情） */
  | { kind: 'success'; ticket: TicketDetailVO }
  /** 状态/可见性已变，调用方应刷新 */
  | { kind: 'stale' }
  /** 其他失败，已弹过提示 */
  | { kind: 'error' }

/** 按动作名分发到对应的接口 */
function callAction(
  id: number,
  action: string,
  payload: { assigneeId?: number; reason?: string },
): Promise<TicketDetailVO> {
  switch (action) {
    case 'assign':
      return ticketApi.assign(id, payload.assigneeId as number)
    case 'transfer':
      return ticketApi.transfer(id, payload.assigneeId as number)
    case 'cancel':
      return ticketApi.cancel(id, payload.reason ?? '')
    case 'accept':
      return ticketApi.accept(id)
    case 'start':
      return ticketApi.start(id)
    case 'hold':
      return ticketApi.hold(id)
    case 'resume':
      return ticketApi.resume(id)
    case 'resolve':
      return ticketApi.resolve(id)
    case 'close':
      return ticketApi.close(id)
    case 'reject':
      return ticketApi.reject(id)
    case 'force-close':
      return ticketApi.forceClose(id)
    default:
      throw new Error(`未知的工单动作：${action}`)
  }
}

/** 动作执行器 */
export function useTicketActions() {
  /** 正在执行的动作名（用于按钮 loading） */
  const submitting = ref<string | null>(null)

  /**
   * 执行一个流转动作。
   *
   * @param ticketId 工单 ID
   * @param action 动作名（来自 canOperate）
   * @param payload 只有 assign / transfer / cancel 需要
   */
  async function runAction(
    ticketId: number,
    action: string,
    payload: { assigneeId?: number; reason?: string } = {},
  ): Promise<ActionResult> {
    submitting.value = action
    try {
      const result = await callAction(ticketId, action, payload)
      ElMessage.success(TICKET_ACTION_META[action]?.successText ?? '操作成功')
      return { kind: 'success', ticket: result }
    } catch (error) {
      const bizError = error as BizError

      if (isStaleError(bizError.code)) {
        // 不弹通用错误：这句话对用户更有用，而且调用方会去刷新
        ElMessage.warning('工单状态已变更，已为你刷新最新状态')
        return { kind: 'stale' }
      }

      ElMessage.error(bizError.serverMessage)
      return { kind: 'error' }
    } finally {
      submitting.value = null
    }
  }

  return { submitting, runAction }
}
