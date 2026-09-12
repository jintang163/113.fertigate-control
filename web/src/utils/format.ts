import dayjs from 'dayjs'

/** 格式化水量 m³（保留合适小数） */
export function formatVolume(m3: number | null | undefined, digits = 2): string {
  if (m3 === null || m3 === undefined || Number.isNaN(m3)) return '—'
  return `${Number(m3).toFixed(digits)} m³`
}

/** 秒 → 时分秒/时分 展示 */
export function formatDuration(sec: number | null | undefined): string {
  if (sec === null || sec === undefined || Number.isNaN(sec)) return '—'
  const s = Math.max(0, Math.round(sec))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const rest = s % 60
  if (h > 0) return `${h}小时${m}分`
  if (m > 0) return `${m}分${rest}秒`
  return `${rest}秒`
}

/** 完整时间 YYYY-MM-DD HH:mm:ss */
export function formatTime(t?: string | number | Date | null): string {
  if (!t) return '—'
  const d = dayjs(t)
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm:ss') : String(t)
}

/** 短时间 MM-DD HH:mm */
export function formatTimeShort(t?: string | number | Date | null): string {
  if (!t) return '—'
  const d = dayjs(t)
  return d.isValid() ? d.format('MM-DD HH:mm') : String(t)
}

/** HH:mm（图表坐标轴用） */
export function formatAxisTime(t?: string | number | Date | null): string {
  if (!t) return ''
  const d = dayjs(t)
  return d.isValid() ? d.format('MM-DD HH:mm') : String(t)
}

/** 相对时间（距今） */
export function timeAgo(t?: string | number | Date | null): string {
  if (!t) return '—'
  const d = dayjs(t)
  if (!d.isValid()) return '—'
  const diffSec = dayjs().unix() - d.unix()
  if (diffSec < 60) return `${Math.max(0, diffSec)} 秒前`
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)} 分钟前`
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)} 小时前`
  return `${Math.floor(diffSec / 86400)} 天前`
}

/** 湿度百分比 */
export function formatPercent(v: number | null | undefined, digits = 1): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '—'
  return `${Number(v).toFixed(digits)}%`
}

/** 数字（null 安全） */
export function formatNum(v: number | null | undefined, digits = 2): string {
  if (v === null || v === undefined || Number.isNaN(v)) return '—'
  return Number(v).toFixed(digits)
}

/** 面积 m² → 亩 */
export function m2ToMu(m2: number): string {
  return `${(m2 / 666.67).toFixed(1)} 亩`
}
