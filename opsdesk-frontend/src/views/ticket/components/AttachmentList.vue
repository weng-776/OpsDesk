<script setup lang="ts">
/**
 * 附件列表 + 上传（详情页区块 ③）。
 *
 * 上传约束与后端 `FileTypeValidator` / `AttachmentServiceImpl` 对齐：
 * - 单文件 ≤ **10MB**
 * - 扩展名白名单 `jpg / png / gif / pdf / txt / log / docx / xlsx`
 *   （⚠️ **没有 jpeg** —— 后端只认 `jpg`，给 `.jpeg` 文件加 `image/jpeg` 也会被拒）
 *
 * ⚠️ 这两条前端校验只是**体验优化**：让用户少等一次失败。
 * 真正的闸门在后端，配置可能被改，前端这份不能当安全措施。
 */
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { attachments, uploadAttachment } from '@/api/ticket'
import { downloadFile } from '@/utils/download'
import { formatDateTime, formatFileSize } from '@/utils/format'
import { BizError } from '@/utils/request'
import type { AttachmentVO } from '@/types/ticket'

const props = defineProps<{
  /** 工单 ID */
  ticketId: number
}>()

/** 单文件大小上限：10MB（与后端一致） */
const MAX_SIZE = 10 * 1024 * 1024
/** 扩展名白名单（与后端一致，注意没有 jpeg） */
const ALLOWED_EXT = ['jpg', 'png', 'gif', 'pdf', 'txt', 'log', 'docx', 'xlsx']
/** 文件选择框的 accept */
const ACCEPT = ALLOWED_EXT.map((ext) => `.${ext}`).join(',')

const items = ref<AttachmentVO[]>([])
const loading = ref(false)
const errorText = ref('')
const uploading = ref(false)
const progress = ref(0)
const fileInput = ref<HTMLInputElement>()

/** 拉附件列表（后端不分页，按时间升序） */
async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    items.value = await attachments(props.ticketId)
  } catch (error) {
    errorText.value = (error as BizError).serverMessage
    items.value = []
  } finally {
    loading.value = false
  }
}

/** 前端预校验，返回错误文案；通过返回 null */
function validate(file: File): string | null {
  const ext = file.name.split('.').pop()?.toLowerCase() ?? ''
  if (!ALLOWED_EXT.includes(ext)) {
    return `不支持的文件类型${ext ? `：.${ext}` : ''}，仅支持 ${ALLOWED_EXT.join(' / ')}`
  }
  if (file.size > MAX_SIZE) {
    return `文件不能超过 10MB（当前 ${formatFileSize(file.size)}）`
  }
  return null
}

function pickFile(): void {
  fileInput.value?.click()
}

async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 清空 input，否则连续选同一个文件不会触发 change
  input.value = ''
  if (!file) return

  const invalid = validate(file)
  if (invalid) {
    ElMessage.error(invalid)
    return
  }

  uploading.value = true
  progress.value = 0
  try {
    await uploadAttachment(props.ticketId, file, (percent) => {
      progress.value = percent
    })
    ElMessage.success(`「${file.name}」上传成功`)
    await load()
  } catch (error) {
    ElMessage.error((error as BizError).serverMessage)
  } finally {
    uploading.value = false
  }
}

/**
 * 下载。
 *
 * ⚠️ 必须走 `utils/download.ts`（axios blob + 解析 Content-Disposition）：
 * 这个接口要 `Authorization` 头，`<a href>` / `window.open` 不带 token，会 401。
 */
async function download(item: AttachmentVO): Promise<void> {
  try {
    await downloadFile(`/attachments/${item.id}/download`, undefined, item.fileName)
  } catch {
    // 拦截器已经弹过提示（含 blob 里解析出的业务错误），这里不再重复弹
  }
}

onMounted(load)
</script>

<template>
  <el-card shadow="never" class="attachment">
    <template #header>
      <div class="attachment__header">
        <span class="attachment__title">附件</span>
        <div class="attachment__actions">
          <el-button size="small" type="primary" :loading="uploading" @click="pickFile">
            上传附件
          </el-button>
          <input
            ref="fileInput"
            type="file"
            class="attachment__input"
            :accept="ACCEPT"
            @change="onFileChange"
          />
        </div>
      </div>
    </template>

    <el-progress
      v-if="uploading"
      :percentage="progress"
      :stroke-width="6"
      class="attachment__progress"
    />

    <el-alert
      v-if="errorText"
      type="error"
      :closable="false"
      show-icon
      :title="`附件加载失败：${errorText}`"
      class="attachment__error"
    />

    <el-table v-loading="loading" :data="items" size="small" class="attachment__table">
      <el-table-column prop="fileName" label="文件名" min-width="200" show-overflow-tooltip />
      <el-table-column label="大小" width="100">
        <template #default="{ row }">{{ formatFileSize(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column prop="uploaderName" label="上传人" width="110" />
      <el-table-column label="上传时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <!-- el-table 的 row 是 DefaultRow（any-ish），这里显式断言回 AttachmentVO -->
          <el-button link type="primary" @click="download(row as AttachmentVO)">下载</el-button>
        </template>
      </el-table-column>

      <template #empty>
        <el-empty description="暂无附件" :image-size="70" />
      </template>
    </el-table>
  </el-card>
</template>

<style scoped lang="scss">
.attachment {
  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__title {
    font-size: 15px;
    font-weight: 600;
  }

  &__input {
    display: none;
  }

  &__progress {
    margin-bottom: 12px;
  }

  &__error {
    margin-bottom: 12px;
  }
}
</style>
