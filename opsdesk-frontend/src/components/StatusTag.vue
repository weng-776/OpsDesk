<script setup lang="ts">
/**
 * 工单状态标签。
 *
 * 颜色和文案**只从 `constants/enums.ts` 取**，页面里不要再自己写「待受理」或写死颜色 ——
 * 后端加一个状态、改一个文案，这里自动跟上。
 */
import { TicketStatus, labelOf, tagTypeOf } from '@/constants/enums'

defineProps<{
  /** 后端状态码，如 OPEN / IN_PROGRESS */
  status: string | null | undefined
}>()
</script>

<template>
  <el-tag :type="tagTypeOf(TicketStatus, status)" disable-transitions>
    {{ labelOf(TicketStatus, status) }}
  </el-tag>
</template>
