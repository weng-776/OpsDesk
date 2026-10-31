<script setup lang="ts">
/**
 * SLA 面板（详情页区块 ④，右栏）。
 *
 * 两条线：响应（responseDeadline / slaResponseState）、解决（resolutionDeadline / slaResolutionState）。
 * 外加累计暂停时长与重新打开次数 —— 这两项是「SLA 为什么没超时」的解释，缺了会被追问。
 */
import { computed } from 'vue'

import SlaTag from '@/components/SlaTag.vue'
import { formatDateTime } from '@/utils/format'
import type { TicketDetailVO } from '@/types/ticket'

const props = defineProps<{
  /** 工单详情 */
  ticket: TicketDetailVO
}>()

/**
 * 把「后端时间字符串」和「现在」比出剩余/超时文案。
 *
 * ⚠️ 这里用的是**浏览器本地时钟**，而 SLA 状态（NORMAL/WARNING/BREACHED）是后端定时扫描写入的**快照**。
 * 两者可能不一致（扫描有间隔、种子数据也可能是几天前的），所以：
 * - 文案保持中性（「距截止」/「已过截止」），不说「已超时」——那会和状态标签打架；
 * - **颜色只跟随后端状态**，不跟本地时钟。后端说 BREACHED 才标红。
 */
function remainingOf(deadline: string | null): { text: string; expired: boolean } {
  if (!deadline) return { text: '—', expired: false }

  const target = new Date(deadline.replace(' ', 'T')).getTime()
  if (Number.isNaN(target)) return { text: '—', expired: false }

  const diff = target - Date.now()
  const abs = Math.abs(diff)
  const hours = Math.floor(abs / 3_600_000)
  const minutes = Math.floor((abs % 3_600_000) / 60_000)
  const text = hours > 0 ? `${hours} 小时 ${minutes} 分` : `${minutes} 分`

  return diff >= 0 ? { text: `距截止 ${text}`, expired: false } : { text: `已过截止 ${text}`, expired: true }
}

const responseRemaining = computed(() => remainingOf(props.ticket.responseDeadline))
const resolutionRemaining = computed(() => remainingOf(props.ticket.resolutionDeadline))

/** 这两个状态下 SLA 是暂停的（§9.4：WAITING_USER / WAITING_CONFIRM 期间暂停并顺延） */
const isPaused = computed<boolean>(
  () => props.ticket.status === 'WAITING_USER' || props.ticket.status === 'WAITING_CONFIRM',
)
const pausedReason = computed<string>(() =>
  props.ticket.status === 'WAITING_USER' ? '等待用户补充' : '等待用户确认',
)

/** 只有后端判定超时，才把剩余时间标红 */
const responseBreached = computed(() => props.ticket.slaResponseState === 'BREACHED')
const resolutionBreached = computed(() => props.ticket.slaResolutionState === 'BREACHED')
</script>

<template>
  <el-card shadow="never" class="sla-panel">
    <template #header>
      <span class="sla-panel__title">SLA</span>
    </template>

    <div class="sla-panel__line">
      <div class="sla-panel__label">
        <span>响应</span>
        <SlaTag :state="ticket.slaResponseState" />
      </div>
      <div class="sla-panel__deadline">{{ formatDateTime(ticket.responseDeadline) }}</div>
      <div class="sla-panel__remaining" :class="{ 'is-expired': responseBreached }">
        {{ responseRemaining.text }}
      </div>
    </div>

    <el-divider class="sla-panel__divider" />

    <div class="sla-panel__line">
      <div class="sla-panel__label">
        <span>解决</span>
        <SlaTag :state="ticket.slaResolutionState" />
      </div>
      <div class="sla-panel__deadline">{{ formatDateTime(ticket.resolutionDeadline) }}</div>
      <div class="sla-panel__remaining" :class="{ 'is-expired': resolutionBreached }">
        {{ resolutionRemaining.text }}
      </div>
    </div>

    <el-divider class="sla-panel__divider" />

    <!-- 挂起 / 待确认期间 SLA 是暂停的：后端不直接给「是否暂停」，由状态推导 -->
    <div v-if="isPaused" class="sla-panel__paused">
      <el-tag type="warning" size="small" effect="plain" disable-transitions>已暂停</el-tag>
      <span class="sla-panel__paused-text">{{ pausedReason }}，SLA 计时暂停并顺延</span>
    </div>

    <div class="sla-panel__meta">
      <div class="sla-panel__meta-row">
        <span class="sla-panel__meta-label">累计暂停</span>
        <span>{{ ticket.slaPausedMinutes ?? 0 }} 分钟</span>
      </div>
      <div class="sla-panel__meta-row">
        <span class="sla-panel__meta-label">重新打开</span>
        <span>{{ ticket.reopenCount ?? 0 }} 次</span>
      </div>
    </div>
  </el-card>
</template>

<style scoped lang="scss">
.sla-panel {
  &__title {
    font-size: 15px;
    font-weight: 600;
  }

  &__line {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  &__label {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 13px;
    font-weight: 500;
  }

  &__deadline {
    color: var(--el-text-color-regular);
    font-size: 13px;
  }

  &__remaining {
    color: var(--el-text-color-secondary);
    font-size: 12px;

    &.is-expired {
      color: var(--el-color-danger);
      font-weight: 500;
    }
  }

  &__divider {
    margin: 14px 0;
  }

  &__paused {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 12px;
  }

  &__paused-text {
    color: var(--el-color-warning);
    font-size: 12px;
  }

  &__meta {
    display: flex;
    flex-direction: column;
    gap: 8px;
    font-size: 13px;
  }

  &__meta-row {
    display: flex;
    justify-content: space-between;
  }

  &__meta-label {
    color: var(--el-text-color-secondary);
  }
}
</style>
