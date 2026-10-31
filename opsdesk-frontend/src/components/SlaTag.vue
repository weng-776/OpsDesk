<script setup lang="ts">
/**
 * SLA 状态标签（解决时限的 SLA 判定结果）。
 *
 * NORMAL 正常 / WARNING 临近超时 / BREACHED 已超时 —— 颜色同样只从 `constants/enums.ts` 取。
 * 抽成组件是因为 F8-05（SLA 相关页面）还要复用，各页面自己写一份三色映射必然写歪。
 */
import { SlaState, labelOf, tagTypeOf } from '@/constants/enums'

defineProps<{
  /** 后端 SLA 状态码，如 NORMAL / BREACHED */
  state: string | null | undefined
}>()
</script>

<template>
  <el-tag :type="tagTypeOf(SlaState, state)" effect="light" disable-transitions>
    {{ labelOf(SlaState, state) }}
  </el-tag>
</template>
