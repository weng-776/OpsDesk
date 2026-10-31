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
import { onMounted, ref } from 'vue'

import { history } from '@/api/ticket'
import { TicketHistoryAction, TicketStatus, labelOf, tagTypeOf } from '@/constants/enums'
import { formatDateTime } from '@/utils/format'
import { BizError } from '@/utils/request'
import type { TicketHistoryVO } from '@/types/ticket'

const props = defineProps<{
  /** 工单 ID */
  ticketId: number
}>()

const items = ref<TicketHistoryVO[]>([])
const loading = ref(false)
const errorText = ref('')

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    items.value = await history(props.ticketId)
  } catch (error) {
    errorText.value = (error as BizError).serverMessage
    items.value = []
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
            <span v-else-if="item.toStatus" class="history__transition">
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
