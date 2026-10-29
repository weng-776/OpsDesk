<script setup lang="ts">
/**
 * 工单列表占位页（F7-05 的落点）。
 *
 * 现在唯一的作用：提供一条**受保护路由**，用来验收「未登录访问 /tickets → 跳 /login」。
 * 不要在这里写工单列表 —— 那是 F7-05。
 */
import PagePlaceholder from '@/components/PagePlaceholder.vue'
</script>

<template>
  <PagePlaceholder
    title="工单列表"
    description="本页是受保护路由的占位，真正的工单列表由 F7-05 实现。"
  >
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="路由守卫已放行：能进到这一页说明 token 有效且 /me 回源成功"
    />
  </PagePlaceholder>
</template>
