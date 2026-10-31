<script setup lang="ts">
/**
 * 附件列表 + 上传（F7-06 建，F8-03 增强为多选串行上传）。
 *
 * 上传约束与后端 `FileTypeValidator` / `AttachmentServiceImpl` 对齐：
 * - 单文件 ≤ **10MB**
 * - 扩展名白名单 `jpg / png / gif / pdf / txt / log / docx / xlsx`
 *   （⚠️ **没有 jpeg** —— 后端只认 `jpg`）
 *
 * ⚠️ 这两条前端校验只是**体验优化**：让用户少等一次失败。
 * 真正的闸门在后端，配置可能被改，前端这份不能当安全措施。
 *
 * ⚠️ 多选是**逐个串行上传**，不是并发：一次丢 5 个 10MB 的文件并发上去，
 * 会把浏览器连接数和后端带宽一起打满，反而更慢，失败还不好定位是哪个。
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

/** 上传任务 */
interface UploadTask {
  id: number
  file: File
  status: 'pending' | 'uploading' | 'error'
  progress: number
  error?: string
}

const items = ref<AttachmentVO[]>([])
const loading = ref(false)
const errorText = ref('')

/** 上传队列（只保留进行中与失败的；成功的会进入下面的表格） */
const tasks = ref<UploadTask[]>([])
const uploading = ref(false)
const fileInput = ref<HTMLInputElement>()
let taskSeq = 0

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

/** 上传单个任务 */
async function uploadOne(task: UploadTask): Promise<void> {
  task.status = 'uploading'
  task.progress = 0
  task.error = undefined
  try {
    await uploadAttachment(props.ticketId, task.file, (percent) => {
      task.progress = percent
    })
  } catch (error) {
    task.status = 'error'
    task.error = (error as BizError).serverMessage
  }
}

/** 串行跑一批任务，跑完刷新列表 */
async function runQueue(list: UploadTask[]): Promise<void> {
  uploading.value = true
  try {
    for (const task of list) {
      await uploadOne(task)
    }
  } finally {
    uploading.value = false
    // 成功的已经进了下面的表格，队列里只留失败的（带重试按钮）
    tasks.value = tasks.value.filter((task) => task.status === 'error')
    await load()
  }
}

async function onFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  // 清空 input，否则连续选同一批文件不会触发 change
  input.value = ''
  if (files.length === 0) return

  const accepted: File[] = []
  for (const file of files) {
    const invalid = validate(file)
    if (invalid) {
      ElMessage.error(`「${file.name}」${invalid}`)
      continue
    }
    accepted.push(file)
  }
  if (accepted.length === 0) return

  const newTasks: UploadTask[] = accepted.map((file) => ({
    id: ++taskSeq,
    file,
    status: 'pending' as const,
    progress: 0,
  }))
  tasks.value = [...tasks.value, ...newTasks]

  await runQueue(newTasks)

  const failed = newTasks.filter((task) => task.status === 'error').length
  if (failed === 0) {
    ElMessage.success(
      accepted.length > 1 ? `${accepted.length} 个附件上传成功` : `「${accepted[0].name}」上传成功`,
    )
  } else {
    ElMessage.warning(`${failed} 个附件上传失败，可点「重试」`)
  }
}

/** 重试单个失败任务 */
async function retry(task: UploadTask): Promise<void> {
  task.status = 'pending'
  await runQueue([task])
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
            {{ uploading ? '上传中…' : '上传附件' }}
          </el-button>
          <!-- multiple：支持一次选多个，内部逐个串行上传 -->
          <input
            ref="fileInput"
            type="file"
            multiple
            class="attachment__input"
            :accept="ACCEPT"
            @change="onFileChange"
          />
        </div>
      </div>
    </template>

    <!-- 上传队列：进行中显示进度，失败显示原因 + 重试 -->
    <div v-if="tasks.length" class="queue">
      <div v-for="task in tasks" :key="task.id" class="queue__item">
        <span class="queue__name">{{ task.file.name }}</span>
        <el-progress
          v-if="task.status === 'uploading'"
          :percentage="task.progress"
          :stroke-width="6"
          class="queue__progress"
        />
        <template v-else>
          <span class="queue__error">{{ task.error }}</span>
          <el-button link type="primary" size="small" @click="retry(task)">重试</el-button>
        </template>
      </div>
    </div>

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

  &__error {
    margin-bottom: 12px;
  }
}

.queue {
  margin-bottom: 12px;
  padding: 10px 12px;
  background-color: var(--el-fill-color-light);
  border-radius: 4px;

  &__item {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 4px 0;
  }

  &__name {
    flex: 0 0 220px;
    overflow: hidden;
    color: var(--el-text-color-regular);
    font-size: 12px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__progress {
    flex: 1;
  }

  &__error {
    flex: 1;
    color: var(--el-color-danger);
    font-size: 12px;
  }
}
</style>
