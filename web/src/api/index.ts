import { del, get, post, put } from './http'
import type {
  ControlPayload,
  CropModel,
  Decision,
  Device,
  DeviceCommandPayload,
  DevicePayload,
  Field,
  FieldStatus,
  Gateway,
  GatewayConfigPayload,
  LedgerKind,
  LedgerRecord,
  LedgerSummary,
  RotationGeneratePayload,
  RotationPlan,
  StageRecord,
  TelemetryDTO
} from '@/types'

// ---------- 田块 / 配置 ----------
export const getFields = () => get<Field[]>('/fields')
export const createField = (payload: Partial<Field>) => post<Field>('/fields', payload)
export const updateField = (id: number, payload: Partial<Field>) => put<Field>(`/fields/${id}`, payload)
export const getFieldStatus = (id: number) => get<FieldStatus>(`/fields/${id}/status`)

// ---------- 生育期记录 ----------
export const getStageRecords = (fieldId: number) =>
  get<StageRecord[]>(`/fields/${fieldId}/stages`)
export const addStageRecord = (fieldId: number, payload: Partial<StageRecord>) =>
  post<StageRecord>(`/fields/${fieldId}/stages`, payload)
export const deleteStageRecord = (fieldId: number, recordId: number) =>
  del<unknown>(`/fields/${fieldId}/stages/${recordId}`)

// ---------- 控制 ----------
export const evaluateDecision = (id: number) =>
  post<Decision>(`/fields/${id}/decision/evaluate`)
export const sendControl = (id: number, payload: ControlPayload) =>
  post<unknown>(`/fields/${id}/control`, payload)
export const runCycle = () => post<unknown>('/control/run-cycle')

// ---------- 作物模型 ----------
export const getCropModels = () => get<CropModel[]>('/crop-models')
export const getCropModel = (code: string) => get<CropModel>(`/crop-models/${code}`)
export const updateCropModel = (code: string, payload: CropModel) =>
  put<CropModel>(`/crop-models/${code}`, payload)

// ---------- 设备 / 网关 ----------
export const getDevices = (params?: { gatewaySn?: string; type?: string }) =>
  get<Device[]>('/devices', params)
export const getDevice = (code: string) => get<Device>(`/devices/${code}`)
export const registerDevice = (payload: DevicePayload) => post<Device>('/devices', payload)
export const updateDevice = (code: string, payload: Partial<DevicePayload>) =>
  put<Device>(`/devices/${code}`, payload)
export const deleteDevice = (code: string) => del<unknown>(`/devices/${code}`)
export const setDeviceStatus = (code: string, status: string) =>
  post<Device>(`/devices/${code}/status`, { status })
export const sendDeviceCommand = (code: string, payload: DeviceCommandPayload) =>
  post<{ accepted: boolean; commandId?: string; message?: string }>(
    `/devices/${code}/command`,
    payload
  )
export const getGateways = () => get<Gateway[]>('/gateways')
export const pushGatewayConfig = (sn: string, payload: GatewayConfigPayload) =>
  post<unknown>(`/gateways/${sn}/config`, payload)

// ---------- 作业 ----------
export interface JobQuery {
  fieldId?: number
  status?: string
  page?: number
  size?: number
}
export const getJobs = (params: JobQuery) =>
  get<import('@/types').PageResult<import('@/types').Job>>('/jobs', params)
export const getJob = (id: number) => get<import('@/types').Job>(`/jobs/${id}`)

// ---------- 轮灌计划 ----------
export const getRotationPlans = () => get<RotationPlan[]>('/rotation/plans')
export const getRotationPlan = (id: number) => get<RotationPlan>(`/rotation/plans/${id}`)
export const generateRotationPlan = (payload: RotationGeneratePayload) =>
  post<RotationPlan>('/rotation/plans', payload)
export const cancelRotationPlan = (id: number) =>
  post<RotationPlan>(`/rotation/plans/${id}/cancel`)

// ---------- 灌肥台账 ----------
export interface LedgerQuery {
  fieldId?: number
  kind?: LedgerKind | ''
  page?: number
  size?: number
}
export const getLedger = (params: LedgerQuery) =>
  get<import('@/types').PageResult<LedgerRecord>>('/ledger', params)
export const getLedgerSummary = (date?: string) =>
  get<LedgerSummary>('/ledger/summary', date ? { date } : {})

// ---------- 告警 ----------
export interface AlarmQuery {
  level?: string
  ack?: boolean
  page?: number
  size?: number
}
export const getAlarms = (params: AlarmQuery) =>
  get<import('@/types').PageResult<import('@/types').Alarm>>('/alarms', params)
export const ackAlarm = (id: number) => post<unknown>(`/alarms/${id}/ack`)
/** 批量确认：契约仅提供单条 ack，前端并发调用 */
export const ackAlarms = (ids: number[]) => Promise.all(ids.map((id) => ackAlarm(id)))

// ---------- 时序 ----------
export interface TelemetryQuery {
  fieldId: number
  metrics?: string
  range?: string
  agg?: string
}
export const getTelemetry = (params: TelemetryQuery) =>
  get<TelemetryDTO>('/telemetry', {
    metrics: 'soilMoist,ec,ph',
    range: '24h',
    agg: '10m',
    ...params
  })
export const getTelemetryLatest = (fieldId: number) =>
  get<Record<string, number | string | null>>('/telemetry/latest', { fieldId })
