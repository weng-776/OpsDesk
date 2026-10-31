<script setup lang="ts">
/**
 * 通用占位页。
 *
 * 15 条菜单里有 13 条的真实页面还没写（F7-04 ~ F7-12），
 * 统一指到这里 —— 保证**每条菜单都点得进、不白屏、不 404**。
 *
 * 标题取路由 `meta.title`，所以一个组件能服务所有路由。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'

import PagePlaceholder from '@/components/PagePlaceholder.vue'
import { usePermission } from '@/composables/usePermission'

const route = useRoute()
const { hasPerm } = usePermission()

const title = computed<string>(() => route.meta.title ?? '页面')

/** 权限码自检清单：用不同角色登录，能直观看到按钮增减 */
const PERM_DEMO = [
  { perm: 'ticket:assign', label: '分派' },
  { perm: 'ticket:export', label: '导出' },
  { perm: 'user:create', label: '新建用户' },
] as const
</script>

<template>
  <PagePlaceholder
    :title="title"
    :description="`当前路径：${route.path}。本页尚未实现，将在后续工单中交付。`"
  >
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="布局、菜单与权限已就绪"
      description="能看到这一页，说明当前账号持有对应的 MENU 权限码。页面内容由后续工单填充。"
    />

    <!--
      按钮级权限自检：这一块是**临时**的，用来证明 v-perm 真的在按 API 权限码删元素。
      真实页面（F7-05 起）会用自己的按钮，届时这里删掉即可。
    -->
    <section class="perm-demo">
      <h3 class="perm-demo__title">
        按钮级权限自检（v-perm）
        <span class="perm-demo__hint">换不同角色登录，看按钮增减</span>
      </h3>

      <div class="perm-demo__row">
        <el-button v-for="item in PERM_DEMO" :key="item.perm" v-perm="item.perm">
          {{ item.label }}（{{ item.perm }}）
        </el-button>

        <!-- 数组语义：任一满足即可 -->
        <el-button v-perm="['ticket:assign', 'ticket:transfer']">
          转移（assign 或 transfer）
        </el-button>
      </div>

      <p class="perm-demo__note">
        当前账号持有
        <code>ticket:assign</code>：{{ hasPerm('ticket:assign') ? '是' : '否' }}　·　
        <code>ticket:export</code>：{{ hasPerm('ticket:export') ? '是' : '否' }}　·　
        <code>user:create</code>：{{ hasPerm('user:create') ? '是' : '否' }}
      </p>
    </section>
  </PagePlaceholder>
</template>

<style scoped lang="scss">
.perm-demo {
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px dashed var(--el-border-color);

  &__title {
    display: flex;
    align-items: baseline;
    gap: 10px;
    margin: 0 0 14px;
    font-size: 14px;
    font-weight: 600;
  }

  &__hint {
    color: var(--el-text-color-secondary);
    font-size: 12px;
    font-weight: 400;
  }

  &__row {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    min-height: 32px;
  }

  &__note {
    margin: 14px 0 0;
    color: var(--el-text-color-secondary);
    font-size: 12px;

    code {
      padding: 1px 4px;
      background-color: var(--el-fill-color-light);
      border-radius: 3px;
    }
  }
}
</style>
