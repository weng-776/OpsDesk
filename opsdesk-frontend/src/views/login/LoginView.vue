<script setup lang="ts">
/**
 * 登录页（F7-02）。
 *
 * 流程：表单校验 → POST /api/auth/login → 存 token + 用户 + 权限 → 回跳（或首页）。
 *
 * ⚠️ 失败文案必须由本页决定，不能交给全局拦截器：
 * 后端对「用户不存在」和「密码错误」返回**同一个 40100**（有意为之，避免泄露账号是否存在），
 * 全局文案是「登录已过期」，放在登录场景是错的。所以 `api/auth.ts` 的 login 带了
 * `silent + skipAuthRedirect`，提示语在这里按 code 映射。
 */
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'

import { ErrorCode } from '@/constants/errorCode'
import { Role, labelOf, tagTypeOf } from '@/constants/enums'
import { useUserStore } from '@/stores/user'
import { BizError } from '@/utils/request'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
/** 行内错误提示（比一闪而过的 toast 更容易被看到） */
const errorText = ref('')

const form = reactive({
  username: '',
  password: '',
})

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { max: 64, message: '用户名不超过 64 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { max: 64, message: '密码不超过 64 个字符', trigger: 'blur' },
  ],
}

/** 演示账号，点一下直接填进表单（方便演示时切角色看权限差异） */
const DEMO_ACCOUNTS = [
  { username: 'admin', role: 'ADMIN', department: '技术部' },
  { username: 'agent_zhang', role: 'AGENT', department: '技术部' },
  { username: 'agent_li', role: 'AGENT', department: '技术部' },
  { username: 'emp_wang', role: 'EMPLOYEE', department: '财务部' },
  { username: 'emp_zhao', role: 'EMPLOYEE', department: '人事部' },
] as const

/**
 * 登录失败文案映射。
 *
 * 40100 一律说「用户名或密码错误」——**不要**区分「账号不存在」和「密码错」，
 * 后端已经刻意合并成一个码，前端再拆开等于把账号是否存在泄露出去。
 */
function loginErrorText(error: BizError): string {
  if (error.code === ErrorCode.UNAUTHORIZED) return '用户名或密码错误'
  if (error.code === ErrorCode.TOO_MANY_REQUESTS) return '失败次数过多，请 10 分钟后再试'
  if (error.code === ErrorCode.PARAM_INVALID) return '用户名或密码格式不正确'
  return error.message
}

/**
 * 登录成功后的落点。
 *
 * 只接受站内路径（以单个 `/` 开头），挡掉 `//evil.com` 这种开放重定向。
 */
function resolveRedirect(): string {
  const raw = route.query.redirect
  const target = Array.isArray(raw) ? raw[0] : raw
  if (typeof target === 'string' && target.startsWith('/') && !target.startsWith('//')) {
    return target
  }
  return '/'
}

/** 点演示账号：填表单并清掉上一次的错误提示 */
function fillDemo(username: string): void {
  form.username = username
  form.password = '123456'
  errorText.value = ''
}

async function submit(): Promise<void> {
  if (loading.value || !formRef.value) return

  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  errorText.value = ''
  try {
    await userStore.login(form.username.trim(), form.password)
    await router.replace(resolveRedirect())
  } catch (error) {
    const message = loginErrorText(error as BizError)
    errorText.value = message
    ElMessage.error(message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <div class="login__card">
      <!-- 左侧品牌区：纯展示，窄屏隐藏 -->
      <aside class="login__brand">
        <h1 class="login__logo">OpsDesk</h1>
        <p class="login__slogan">单企业内部 IT 服务管理平台</p>
        <ul class="login__features">
          <li>工单全流程 · 状态流转与 SLA 计时</li>
          <li>知识库 · AI 助手 · 工单智能分析</li>
          <li>RBAC 权限 · 按部门的数据范围隔离</li>
        </ul>
      </aside>

      <!-- 右侧表单区 -->
      <section class="login__form">
        <h2 class="login__title">登录</h2>
        <p class="login__subtitle">请使用企业账号登录</p>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="submit"
        >
          <el-form-item label="用户名" prop="username">
            <el-input
              v-model="form.username"
              placeholder="请输入用户名"
              clearable
              :maxlength="64"
              autocomplete="username"
              @keyup.enter="submit"
            />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              show-password
              :maxlength="64"
              autocomplete="current-password"
              @keyup.enter="submit"
            />
          </el-form-item>

          <p v-if="errorText" class="login__error">{{ errorText }}</p>

          <el-button
            type="primary"
            size="large"
            class="login__submit"
            :loading="loading"
            native-type="submit"
          >
            登 录
          </el-button>
        </el-form>

        <el-divider class="login__divider">演示账号（密码统一 123456）</el-divider>

        <div class="login__demo">
          <el-tag
            v-for="account in DEMO_ACCOUNTS"
            :key="account.username"
            :type="tagTypeOf(Role, account.role)"
            class="login__demo-tag"
            disable-transitions
            @click="fillDemo(account.username)"
          >
            {{ account.username }} · {{ labelOf(Role, account.role) }} · {{ account.department }}
          </el-tag>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped lang="scss">
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px;
  background: linear-gradient(135deg, #eef3ff 0%, #f7f9fc 55%, #eef7f4 100%);

  &__card {
    display: flex;
    width: 100%;
    max-width: 880px;
    overflow: hidden;
    background-color: var(--el-bg-color);
    border-radius: 12px;
    box-shadow: 0 12px 40px rgb(31 45 61 / 12%);
  }

  &__brand {
    flex: 0 0 40%;
    display: flex;
    flex-direction: column;
    justify-content: center;
    gap: 12px;
    padding: 48px 36px;
    color: #fff;
    background: linear-gradient(150deg, #2f6fed 0%, #1b47b5 100%);
  }

  &__logo {
    margin: 0;
    font-size: 28px;
    font-weight: 700;
    letter-spacing: 1px;
  }

  &__slogan {
    margin: 0;
    font-size: 13px;
    opacity: 0.85;
  }

  &__features {
    margin: 20px 0 0;
    padding: 0;
    list-style: none;
    font-size: 13px;
    line-height: 2;
    opacity: 0.9;

    li::before {
      content: '·';
      margin-right: 8px;
      font-weight: 700;
    }
  }

  &__form {
    flex: 1;
    padding: 44px 48px;
  }

  &__title {
    margin: 0;
    font-size: 22px;
    font-weight: 600;
  }

  &__subtitle {
    margin: 6px 0 26px;
    color: var(--el-text-color-secondary);
    font-size: 13px;
  }

  &__error {
    margin: -4px 0 14px;
    color: var(--el-color-danger);
    font-size: 13px;
  }

  &__submit {
    width: 100%;
  }

  &__divider {
    margin: 26px 0 16px;

    :deep(.el-divider__text) {
      color: var(--el-text-color-secondary);
      font-size: 12px;
      font-weight: 400;
    }
  }

  &__demo {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  &__demo-tag {
    cursor: pointer;

    &:hover {
      opacity: 0.8;
    }
  }
}

@media (max-width: 720px) {
  .login__brand {
    display: none;
  }

  .login__form {
    padding: 32px 24px;
  }
}
</style>
