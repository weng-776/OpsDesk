<script setup lang="ts">
/**
 * /login 占位页。
 *
 * ⚠️ 只放一句说明 + 一个返回按钮，**不要在这里写登录表单** ——
 * 登录页是 F7-02 的交付内容（表单校验、token 持久化、路由守卫）。
 * 这个文件的作用仅仅是让 401 跳转有个落点，验证「只跳一次」。
 */
import { useRouter } from 'vue-router'

import PagePlaceholder from '@/components/PagePlaceholder.vue'

const router = useRouter()
</script>

<template>
  <PagePlaceholder
    title="登录页占位"
    description="401 跳转会落到这里，说明请求层的「清 token + 跳登录」是通的。真正的登录页由 F7-02 实现。"
  >
    <el-button @click="router.push('/')">回到自检页</el-button>
  </PagePlaceholder>
</template>
