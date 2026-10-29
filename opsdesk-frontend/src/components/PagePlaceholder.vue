<script setup lang="ts">
/**
 * 占位页外壳 —— 只负责「标题 + 说明 + 内容」的排版，不含任何业务逻辑。
 *
 * 存在的意义：F7-01 的占位页和 /login 占位长得一样，抽出来避免复制两份样式。
 * 真正的页面容器（带侧边栏 / 面包屑）是 F7-03 的活。
 */
defineProps<{
  /** 页面标题 */
  title: string
  /** 一句话说明 */
  description?: string
}>()
</script>

<template>
  <div class="page-placeholder">
    <el-card shadow="never" class="page-placeholder__card">
      <template #header>
        <div class="page-placeholder__header">
          <span class="page-placeholder__title">{{ title }}</span>
          <slot name="extra" />
        </div>
      </template>

      <p v-if="description" class="page-placeholder__desc">{{ description }}</p>

      <slot />
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.page-placeholder {
  min-height: 100%;
  padding: 32px 24px;

  &__card {
    max-width: 880px;
    margin: 0 auto;
  }

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
  }

  &__title {
    font-size: 16px;
    font-weight: 600;
  }

  &__desc {
    margin: 0 0 20px;
    color: var(--el-text-color-secondary);
    font-size: 13px;
    line-height: 1.7;
  }
}
</style>
