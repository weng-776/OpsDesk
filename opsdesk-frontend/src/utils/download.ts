/**
 * 带鉴权的文件下载。
 *
 * ⚠️ 这类接口（附件下载、工单导出）都要 `Authorization` 头，所以**不能**用
 * `window.open(url)` / `<a href>` 直接打开 —— 那样不会带 token，必然 401。
 * 必须走 axios 拿 blob，再 `URL.createObjectURL` 触发另存为。
 */

import type { AxiosResponse } from 'axios'

import { request } from '@/utils/request'

/**
 * 从 `Content-Disposition` 里取文件名。
 *
 * 后端格式（RFC 5987，见 `TicketController#export`）：
 * `attachment; filename="tickets-20261027.xlsx"; filename*=UTF-8''%E5%B7%A5...`
 *
 * 优先级：`filename*=UTF-8''<URL 编码>` > `filename="..."` > 调用方给的 fallbackName。
 */
function filenameFromDisposition(disposition?: string): string | null {
  if (!disposition) return null

  // ① filename*=UTF-8''<encoded>（中文文件名只在这里，decodeURIComponent 一次即可）
  const encoded = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(disposition)
  if (encoded?.[1]) {
    const raw = encoded[1].trim().replace(/^["']|["']$/g, '')
    try {
      return decodeURIComponent(raw)
    } catch {
      return raw
    }
  }

  // ② filename="ascii-name"
  const plain = /filename\s*=\s*"?([^";]+)"?/i.exec(disposition)
  if (plain?.[1]) return plain[1].trim()

  return null
}

/**
 * 下载一个需要鉴权的文件。
 *
 * 失败时（后端返回 JSON 错误被当成 blob）由 `utils/request.ts` 的拦截器负责
 * `await blob.text()` 解析并 reject 成 `BizError`，这里不用重复处理。
 *
 * @param url 接口路径，例如 `/ticket/export`（会拼上 baseURL `/api`）
 * @param params 查询参数
 * @param fallbackName 响应头里没有文件名时的兜底名
 */
export async function downloadFile(
  url: string,
  params?: Record<string, unknown>,
  fallbackName = 'download',
): Promise<void> {
  const response = await request<AxiosResponse<Blob>>({
    url,
    method: 'get',
    params,
    responseType: 'blob',
  })

  const blob = response.data
  const disposition = response.headers['content-disposition'] as string | undefined
  const filename = filenameFromDisposition(disposition) ?? fallbackName

  const objectUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = filename
  link.style.display = 'none'

  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)

  // 延迟释放：立即 revoke 在部分浏览器（Safari / 老版 Chrome）会打断下载。
  // 但一定要释放，否则每次下载都留一份 blob 在内存里，导出几次就顶不住了。
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
}
