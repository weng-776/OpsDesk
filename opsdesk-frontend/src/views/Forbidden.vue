<script setup lang="ts">
/**
 * 403 无权限页。
 *
 * ⚠️ 它必须是一条**真实路由**，而不是「静默跳首页」——
 * 用户手输 /pool 却被弹回工作台，会以为自己点错了，反复重试；
 * 明确告诉「你没有这个页面的权限」才是对的。
 *
 * 放在 Layout 内部，侧边栏还在，用户可以直接换到有权限的页面。
 */
import { useRouter } from 'vue-router'

import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
</script>

<template>
  <div class="forbidden">
    <el-result icon="warning" title="403 · 无权限访问" sub-title="当前账号没有该页面的访问权限。">
      <template #extra>
        <p class="forbidden__account">
          当前账号：{{ userStore.user?.nickname ?? '—' }}（{{ userStore.user?.username ?? '—' }}）
        </p>
        <el-button type="primary" @click="router.replace('/dashboard')">回到工作台</el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped lang="scss">
.forbidden {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  padding: 40px 24px;

  &__account {
    margin: 0 0 16px;
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }
}
</style>
