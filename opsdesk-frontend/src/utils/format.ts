/**
 * 时间 / 文件大小格式化。
 *
 * ⚠️ 后端时间字段返回的是 **ISO-8601 字符串**（例 `2026-10-27T21:21:36`，带 T、
 * 没有时区后缀），**不是** `yyyy-MM-dd HH:mm:ss`。直接把原值丢到页面上就会显示成
 * `2026-10-27T21:21:36` —— 所有时间字段必须过这里的函数。
 *
 * 不引 dayjs / moment：手写 20 行就够，多一个依赖不值得。
 */

/** 空值占位符（表格里统一显示这个，不要显示 null / 空字符串） */
const EMPTY_TEXT = '—'

/** 两位数补零 */
function pad2(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

/**
 * 把后端时间字符串解析成 Date。
 *
 * 不用 `new Date(str)` 的原因：`2026-10-27T21:21:36` 这种无时区写法在不同浏览器
 * 上解析行为不一致（Safari 老版本会当 UTC）。这里手动拆，一律按**本地时间**处理。
 */
function parseDateValue(value?: string | null): Date | null {
  if (!value) return null

  const matched = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.(\d{1,3}))?)?/.exec(value)
  if (!matched) {
    const fallback = new Date(value)
    return Number.isNaN(fallback.getTime()) ? null : fallback
  }

  const [, y, mo, d, h, mi, s, ms] = matched
  const date = new Date(
    Number(y),
    Number(mo) - 1,
    Number(d),
    Number(h ?? 0),
    Number(mi ?? 0),
    Number(s ?? 0),
    Number(ms ?? 0),
  )
  return Number.isNaN(date.getTime()) ? null : date
}

/**
 * `yyyy-MM-dd HH:mm:ss`。空值返回 `'—'`。
 *
 * @example formatDateTime('2026-10-27T21:21:36') // '2026-10-27 21:21:36'
 */
export function formatDateTime(value?: string | null): string {
  const date = parseDateValue(value)
  if (!date) return EMPTY_TEXT
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} ${pad2(
    date.getHours(),
  )}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`
}

/**
 * `yyyy-MM-dd`。空值返回 `'—'`。
 *
 * @example formatDate('2026-10-27T21:21:36') // '2026-10-27'
 */
export function formatDate(value?: string | null): string {
  const date = parseDateValue(value)
  if (!date) return EMPTY_TEXT
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
}

/** 一天的毫秒数 */
const DAY_MS = 24 * 60 * 60 * 1000

/**
 * 相对时间：`刚刚 / 3 分钟前 / 2 小时前 / 3 天前`，超过 7 天回落到 `formatDateTime`。
 *
 * @example formatRelative(new Date(Date.now() - 5 * 60_000).toISOString()) // '5 分钟前'
 */
export function formatRelative(value?: string | null): string {
  const date = parseDateValue(value)
  if (!date) return EMPTY_TEXT

  const diff = Date.now() - date.getTime()
  if (diff < 0) return '刚刚' // 后端时间比本地快（时钟漂移）时不要显示「-3 分钟前」
  if (diff < 60_000) return '刚刚'
  if (diff < 60 * 60_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < DAY_MS) return `${Math.floor(diff / (60 * 60_000))} 小时前`
  if (diff <= 7 * DAY_MS) return `${Math.floor(diff / DAY_MS)} 天前`
  return formatDateTime(value)
}

/** 文件大小单位，按 1024 进制 */
const SIZE_UNITS = ['B', 'KB', 'MB', 'GB', 'TB'] as const

/**
 * 文件大小：`'1.2 MB'`。非法值返回 `'—'`，0 返回 `'0 B'`。
 *
 * @example formatFileSize(1258291) // '1.2 MB'
 */
export function formatFileSize(bytes?: number | null): string {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes) || bytes < 0) {
    return EMPTY_TEXT
  }
  if (bytes === 0) return '0 B'

  let size = bytes
  let unitIndex = 0
  while (size >= 1024 && unitIndex < SIZE_UNITS.length - 1) {
    size /= 1024
    unitIndex += 1
  }

  // 字节数不带小数（"1024.0 B" 很怪），其余保留一位
  const text = unitIndex === 0 ? String(Math.round(size)) : size.toFixed(1)
  return `${text} ${SIZE_UNITS[unitIndex]}`
}
