/** 业务标签/颜色映射 */
import type {
  AlarmLevel,
  DeviceType,
  JobStatus,
  LedgerKind,
  LedgerMode,
  RotationItemStatus,
  RotationPlanStatus,
  ValveState
} from '@/types'

export const JOB_STATUS_COLOR: Record<JobStatus, string> = {
  RUNNING: 'processing',
  DONE: 'green',
  ABORTED: 'red',
  PLANNED: 'default'
}

export const JOB_STATUS_TEXT: Record<JobStatus, string> = {
  RUNNING: '灌溉中',
  DONE: '已完成',
  ABORTED: '已中止',
  PLANNED: '已计划'
}

export const TRIGGER_TEXT: Record<string, string> = {
  AUTO: '自动',
  MANUAL: '手动',
  SCHEDULED: '轮灌计划',
  SAFETY_OFF: '安全联锁'
}

export const LEDGER_KIND_TEXT: Record<LedgerKind, string> = {
  WATER: '灌水',
  FERTIGATION: '施肥'
}

export const LEDGER_KIND_COLOR: Record<LedgerKind, string> = {
  WATER: 'blue',
  FERTIGATION: 'gold'
}

export const LEDGER_MODE_TEXT: Record<LedgerMode, string> = {
  AUTO: '自动',
  MANUAL: '手动',
  SCHEDULED: '轮灌计划',
  SAFETY: '安全联锁'
}

export const ROTATION_PLAN_STATUS_TEXT: Record<RotationPlanStatus, string> = {
  DRAFT: '草稿',
  SCHEDULED: '已排程',
  RUNNING: '执行中',
  DONE: '已完成',
  CANCELLED: '已取消'
}

export const ROTATION_PLAN_STATUS_COLOR: Record<RotationPlanStatus, string> = {
  DRAFT: 'default',
  SCHEDULED: 'blue',
  RUNNING: 'processing',
  DONE: 'green',
  CANCELLED: 'red'
}

export const ROTATION_ITEM_STATUS_TEXT: Record<RotationItemStatus, string> = {
  PENDING: '待执行',
  RELEASED: '已释放',
  DONE: '已完成',
  SKIPPED: '已跳过',
  BLOCKED: '被拦截'
}

export const ROTATION_ITEM_STATUS_COLOR: Record<RotationItemStatus, string> = {
  PENDING: 'default',
  RELEASED: 'processing',
  DONE: 'green',
  SKIPPED: 'orange',
  BLOCKED: 'red'
}

export const ALARM_LEVEL_COLOR: Record<AlarmLevel, string> = {
  CRITICAL: 'red',
  WARN: 'orange',
  INFO: 'blue'
}

export const ALARM_LEVEL_TEXT: Record<AlarmLevel, string> = {
  CRITICAL: '严重',
  WARN: '警告',
  INFO: '信息'
}

export const DECISION_COLOR: Record<string, string> = {
  IRRIGATE: 'blue',
  HOLD: 'default',
  SKIP: 'cyan',
  FORBID: 'red'
}

export const DECISION_TEXT: Record<string, string> = {
  IRRIGATE: '建议灌溉',
  HOLD: '保持',
  SKIP: '雨养跳过',
  FORBID: '禁止开阀'
}

export const DEVICE_TYPE_TEXT: Record<DeviceType, string> = {
  SOIL_SENSOR: '土壤传感器',
  WEATHER_STATION: '气象站',
  VALVE: '电磁阀',
  FLOW_METER: '流量计',
  FERT_PUMP: '施肥泵',
  PRESSURE_SENSOR: '压力变送器'
}

export const VALVE_COLOR: Record<string, string> = {
  OPEN: 'green',
  CLOSED: 'default',
  OPENING: 'processing',
  CLOSING: 'processing',
  FAULT: 'red'
}

export const VALVE_TEXT: Record<string, string> = {
  OPEN: '已开启',
  CLOSED: '已关闭',
  OPENING: '开启中',
  CLOSING: '关闭中',
  FAULT: '阀门故障'
}

export function valveColor(v: ValveState): string {
  return VALVE_COLOR[v] ?? 'default'
}

export function valveText(v: ValveState): string {
  return VALVE_TEXT[v] ?? String(v)
}

export function deviceTypeText(t: DeviceType): string {
  return DEVICE_TYPE_TEXT[t] ?? t
}
