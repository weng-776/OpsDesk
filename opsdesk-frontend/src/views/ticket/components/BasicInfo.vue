<script setup lang="ts">
/**
 * 工单基本信息（详情页区块 ①）。
 *
 * 纯展示组件：数据全部来自父组件传下来的 `TicketDetailVO`，自己不发请求。
 */
import { TicketCategory, TicketSource, TicketType, labelOf } from '@/constants/enums'
import { formatDateTime } from '@/utils/format'
import type { TicketDetailVO } from '@/types/ticket'

defineProps<{
  /** 工单详情 */
  ticket: TicketDetailVO
}>()
</script>

<template>
  <el-card shadow="never" class="basic-info">
    <template #header>
      <span class="basic-info__title">基本信息</span>
    </template>

    <el-descriptions :column="2" border size="small">
      <el-descriptions-item label="工单类型">
        {{ labelOf(TicketType, ticket.type) }}
      </el-descriptions-item>
      <el-descriptions-item label="工单分类">
        {{ labelOf(TicketCategory, ticket.category) }}
      </el-descriptions-item>

      <el-descriptions-item label="创建人">{{ ticket.creatorName }}</el-descriptions-item>
      <el-descriptions-item label="所属部门">
        {{ ticket.departmentName ?? '—' }}
      </el-descriptions-item>

      <el-descriptions-item label="处理人">
        <span v-if="ticket.assigneeName">{{ ticket.assigneeName }}</span>
        <span v-else class="basic-info__muted">未分派</span>
      </el-descriptions-item>
      <el-descriptions-item label="来源">
        {{ labelOf(TicketSource, ticket.source) }}
      </el-descriptions-item>

      <el-descriptions-item label="创建时间">
        {{ formatDateTime(ticket.createdAt) }}
      </el-descriptions-item>
      <el-descriptions-item label="首次受理">
        {{ formatDateTime(ticket.firstResponseAt) }}
      </el-descriptions-item>

      <el-descriptions-item label="解决时间">
        {{ formatDateTime(ticket.resolvedAt) }}
      </el-descriptions-item>
      <el-descriptions-item label="关闭时间">
        {{ formatDateTime(ticket.closedAt) }}
      </el-descriptions-item>

      <el-descriptions-item v-if="ticket.cancelReason" label="撤销原因" :span="2">
        {{ ticket.cancelReason }}
      </el-descriptions-item>
    </el-descriptions>
  </el-card>
</template>

<style scoped lang="scss">
.basic-info {
  &__title {
    font-size: 15px;
    font-weight: 600;
  }

  &__muted {
    color: var(--el-text-color-placeholder);
  }
}
</style>
