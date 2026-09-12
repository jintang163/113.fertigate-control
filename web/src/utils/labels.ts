/** 业务标签/颜色映射 */
import type { AlarmLevel, DeviceType, JobStatus, ValveState } from '@/types'

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
  FLOW_METER: '流量计'
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
