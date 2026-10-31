<script setup lang="ts">
/**
 * 创建工单（F7-05）。
 *
 * 两个不能省的东西：
 * 1. **幂等键**：`Idempotency-Key` 请求头，**整个表单会话只生成一次**。
 *    重试时重新生成 = 没做幂等，后端会当成新请求再建一张。
 * 2. **提交中禁用按钮**：与幂等键双保险 —— 幂等挡住「重复建单」，禁用挡住「重复请求」。
 *
 * ⚠️ 创建时不调任何 AI 接口：AI 分析是异步的（§8.1 明确「不依赖 AI」）。
 */
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'

import { create } from '@/api/ticket'
import { ErrorCode } from '@/constants/errorCode'
import { TicketCategory, TicketPriority, TicketType } from '@/constants/enums'
import { BizError } from '@/utils/request'
import type { TicketCategoryValue, TicketPriorityValue, TicketTypeValue } from '@/constants/enums'
import type { TicketCreatePayload } from '@/types/ticket'

const router = useRouter()

const formRef = ref<FormInstance>()
/** 提交中：按钮 loading + 直接拦掉重复点击 */
const submitting = ref(false)
/** 常驻错误文案（比一闪而过的 toast 更容易被看到） */
const errorText = ref('')

/**
 * 表单数据。
 *
 * `type` / `category` 刻意**不给默认值** —— 默认成 OTHER 会让用户懒得选，
 * 分类数据一脏，后面的统计和 SLA 全都跟着歪。`priority` 按文档默认 P3。
 */
const form = reactive({
  title: '',
  description: '',
  type: '' as TicketTypeValue | '',
  category: '' as TicketCategoryValue | '',
  priority: 'P3' as TicketPriorityValue,
})

/** 校验规则：与后端 `TicketCreateDTO` 的 @NotBlank / @Size 保持一致 */
const rules: FormRules = {
  title: [
    { required: true, message: '请输入工单标题', trigger: 'blur' },
    { max: 128, message: '标题不能超过 128 个字符', trigger: 'blur' },
  ],
  description: [
    { required: true, message: '请描述遇到的问题', trigger: 'blur' },
    { max: 4000, message: '描述不能超过 4000 个字符', trigger: 'blur' },
  ],
  type: [{ required: true, message: '请选择工单类型', trigger: 'change' }],
  category: [{ required: true, message: '请选择工单分类', trigger: 'change' }],
}

/**
 * 生成幂等键。
 *
 * `crypto.randomUUID()` 只在**安全上下文**（https / localhost）可用，
 * 内网 http 部署时会 undefined —— 兜底一个时间戳 + 随机串，保证永远有值。
 */
function createIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
}

/**
 * 本次表单会话的幂等键。
 *
 * ⚠️ 用 `const` 而不是 `ref` 是故意的：**它不允许变**。
 * 提交失败后用户再点一次，带的必须是同一个 key —— 这样后端能识别出「这是重试」，
 * 而不是「又一张新工单」。只有重新进入本页（新组件实例）才会换新 key。
 */
const idempotencyKey = createIdempotencyKey()

/** 失败文案：优先用后端原文（40900 的真实含义是「同一请求处理中」） */
function errorTextOf(error: BizError): string {
  if (error.code === ErrorCode.CONFLICT) {
    return error.serverMessage || '同一请求正在处理中，请勿重复提交'
  }
  return error.serverMessage
}

/** 提交 */
async function submit(): Promise<void> {
  if (submitting.value || !formRef.value) return

  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  errorText.value = ''

  const payload: TicketCreatePayload = {
    title: form.title.trim(),
    description: form.description.trim(),
    type: form.type as TicketTypeValue,
    category: form.category as TicketCategoryValue,
    priority: form.priority,
  }

  try {
    const created = await create(payload, idempotencyKey)
    ElMessage.success(`工单 ${created.ticketNo} 创建成功`)
    // 用 replace：创建页不该留在历史栈里，否则用户点「返回」会回到一个已提交的表单
    await router.replace({ name: 'TicketDetail', params: { id: created.id } })
  } catch (error) {
    errorText.value = errorTextOf(error as BizError)
  } finally {
    submitting.value = false
  }
}

/** 重置表单（幂等键保持不变，因为还没提交成功过） */
function resetForm(): void {
  formRef.value?.resetFields()
  form.type = ''
  form.category = ''
  form.priority = 'P3'
  errorText.value = ''
}
</script>

<template>
  <div class="ticket-create">
    <el-card shadow="never" class="ticket-create__card">
      <template #header>
        <div class="ticket-create__header">
          <span class="ticket-create__title">提交工单</span>
          <span class="ticket-create__hint">描述越具体，处理越快</span>
        </div>
      </template>

      <el-alert
        v-if="errorText"
        type="error"
        :closable="false"
        show-icon
        class="ticket-create__error"
        :title="errorText"
      />

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-width="96px"
        class="ticket-create__form"
        @submit.prevent="submit"
      >
        <el-form-item label="标题" prop="title">
          <el-input
            v-model="form.title"
            class="ticket-create__title-input"
            placeholder="一句话说明问题，例如：公司 VPN 无法连接"
            maxlength="128"
            show-word-limit
            clearable
          />
        </el-form-item>

        <el-form-item label="问题描述" prop="description">
          <el-input
            v-model="form.description"
            class="ticket-create__desc-input"
            type="textarea"
            :rows="6"
            placeholder="请描述现象、发生时间、已尝试过的操作，便于快速定位"
            maxlength="4000"
            show-word-limit
          />
        </el-form-item>

        <el-row :gutter="16">
          <el-col :xs="24" :sm="12">
            <el-form-item label="工单类型" prop="type">
              <el-select
                v-model="form.type"
                placeholder="请选择"
                class="ticket-create__type"
                clearable
              >
                <el-option
                  v-for="item in TicketType"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
          </el-col>

          <el-col :xs="24" :sm="12">
            <el-form-item label="工单分类" prop="category">
              <el-select
                v-model="form.category"
                placeholder="请选择"
                class="ticket-create__category"
                clearable
              >
                <el-option
                  v-for="item in TicketCategory"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="优先级" prop="priority">
          <el-radio-group v-model="form.priority">
            <el-radio-button v-for="item in TicketPriority" :key="item.value" :value="item.value">
              {{ item.label }}
            </el-radio-button>
          </el-radio-group>
          <span class="ticket-create__tip">默认「普通」，紧急故障再选「紧急」</span>
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            class="ticket-create__submit"
            :loading="submitting"
            native-type="submit"
          >
            提交工单
          </el-button>
          <el-button :disabled="submitting" @click="resetForm">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped lang="scss">
.ticket-create {
  padding: 16px;

  &__card {
    max-width: 880px;
    margin: 0 auto;
  }

  &__header {
    display: flex;
    align-items: baseline;
    gap: 12px;
  }

  &__title {
    font-size: 16px;
    font-weight: 600;
  }

  &__hint {
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }

  &__error {
    margin-bottom: 18px;
  }

  &__type,
  &__category {
    width: 100%;
  }

  &__tip {
    margin-left: 12px;
    color: var(--el-text-color-secondary);
    font-size: 12px;
  }
}
</style>
