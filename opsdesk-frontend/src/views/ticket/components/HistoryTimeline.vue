<script setup lang="ts">
/**
 * 工单历史时间线（详情页区块 ⑦）。
 *
 * ⚠️ `fromStatus` 在 `CREATE` 动作时为 **null**（没有来源状态）。
 * 直接拼 `${fromStatus} → ${toStatus}` 会渲染出「null → 待受理」，所以要分支处理：
 * 有来源状态才显示箭头，没有就只显示目标状态。
 *
 * 后端已按 `created_at ASC, id ASC` 排好序，前端不再排序。
 */
import { computed, onMounted, ref } from 'vue'

import { history } from '@/api/ticket'
import { TicketHistoryAction, TicketStatus, labelOf, tagTypeOf } from '@/constants/enums'
import { formatDateTime } from '@/utils/format'
import { BizError } from '@/utils/request'
import type { TicketHistoryVO } from '@/types/ticket'

const props = defineProps<{
  /** 工单 ID */
  ticketId: number
}>()

const rawItems = ref<TicketHistoryVO[]>([])
const loading = ref(false)
const errorText = ref('')

/**
 * 展示用的历史列表 —— **过滤掉 COMMENT**。
 *
 * ⚠️ §8.8 明确「写评论时同时写一条 ticket_history(COMMENT)」，
 * 所以时间线里会混进评论记录；而评论已经在「处理记录」区块展示过了，
 * 时间线里再出现一次就是纯噪音（同一条内容看两遍）。
 *
 * ⚠️ 注意区分：这里过滤的是**历史里的 COMMENT 动作**，
 * 不是过滤 `internal` 评论（那是后端 SQL 层的事，前端绝不能碰）。
 */
const items = computed<TicketHistoryVO[]>(() => rawItems.value.filter((item) => item.action !== 'COMMENT'))

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    rawItems.value = await history(props.ticketId)
  } catch (error) {
    errorText.value = (error as BizError).serverMessage
    rawItems.value = []
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card shadow="never" class="history">
    <template #header>
      <span class="history__title">工单历史</span>
    </template>

    <el-alert
      v-if="errorText"
      type="error"
      :closable="false"
      show-icon
      :title="`历史加载失败：${errorText}`"
      class="history__error"
    />

    <div v-loading="loading" class="history__body">
      <el-empty v-if="!loading && items.length === 0" description="暂无记录" :image-size="70" />

      <el-timeline v-else>
        <el-timeline-item
          v-for="item in items"
          :key="item.id"
          :timestamp="formatDateTime(item.createdAt)"
          placement="top"
        >
          <div class="history__row">
            <el-tag
              size="small"
              :type="tagTypeOf(TicketHistoryAction, item.action)"
              disable-transitions
            >
              {{ labelOf(TicketHistoryAction, item.action) }}
            </el-tag>

            <span class="history__operator">{{ item.operatorName }}</span>

            <!-- fromStatus 为 null（CREATE）时只显示目标状态，避免「null → 待受理」 -->
            <span v-if="item.fromStatus" class="history__transition">
              {{ labelOf(TicketStatus, item.fromStatus) }}
              <span class="history__arrow">→</span>
              {{ labelOf(TicketStatus, item.toStatus) }}
            </span>
            <!-- fromStatus 为 null（CREATE）：渲染成「→ 待受理」，不要出现 "null →" -->
            <span v-else-if="item.toStatus" class="history__transition">
              <span class="history__arrow">→</span>
              {{ labelOf(TicketStatus, item.toStatus) }}
            </span>
          </div>

          <p v-if="item.remark" class="history__remark">{{ item.remark }}</p>
        </el-timeline-item>
      </el-timeline>
    </div>
  </el-card>
</template>

<style scoped lang="scss">
.history {
  &__title {
    font-size: 15px;
    font-weight: 600;
  }

  &__error {
    margin-bottom: 12px;
  }

  &__body {
    min-height: 60px;
    padding-top: 4px;
  }

  &__row {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
  }

  &__operator {
    font-size: 13px;
    font-weight: 500;
  }

  &__transition {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  &__arrow {
    margin: 0 2px;
  }

  &__remark {
    margin: 6px 0 0;
    color: var(--el-text-color-regular);
    font-size: 12px;
    line-height: 1.6;
    white-space: pre-wrap;
  }
}
</style>
