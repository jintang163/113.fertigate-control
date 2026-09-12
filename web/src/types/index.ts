// 按 REST API 契约定义的领域类型

/** 统一响应体 { code, msg, data } */
export interface ApiResponse<T> {
  code: number
  msg: string
  data: T
}

export type IrrigationMode = 'AUTO' | 'MANUAL'
export type IrrigationMethod = 'DRIP' | 'SPRINKLER'
export type ValveState = 'OPEN' | 'CLOSED' | 'OPENING' | 'CLOSING' | 'FAULT' | string

/** 田块灌溉配置 FieldConfig */
export interface FieldConfig {
  /** 田间持水量 θfc（体积含水率 %） */
  thetaFc: number
  /** 萎蔫点 θwp（%） */
  thetaWp: number
  /** 控制模式 AUTO / MANUAL */
  mode: IrrigationMode
  /** 硬上限偏移（百分点，默认 3，hardMax = θfc + offset） */
  hardMaxOffsetPct: number
  /** 湿度硬下限（%） */
  hardMin: number
  /** 单次最大灌溉时长（秒） */
  maxDurationSec: number
  /** 两次作业最小间隔（小时） */
  minIntervalH: number
  ecMin: number
  ecMax: number
  phMin: number
  phMax: number
  /** 跳过灌溉的降雨阈值 mm */
  rainSkipMm: number
  /** 湿润比 p_wet */
  wetRatio: number
  /** 灌溉水利用系数 η */
  efficiency: number
  enabled: boolean
}

export interface Field {
  id: number
  name: string
  cropCode: string
  areaM2: number
  irrigationMode: IrrigationMethod
  /** 滴头/系统总流量 L/h */
  emitterTotalLph: number
  valveCode?: string | null
  sensorCodes?: string[]
  config: FieldConfig
}

/** GET /fields/{id}/status 实时状态 */
export interface FieldStatusAlarm {
  type?: string
  level?: string
  message?: string
  [key: string]: unknown
}

export interface FieldStatus {
  fieldId: number
  mode: IrrigationMode
  valveState: ValveState
  /** 当前根区平均体积含水率 % */
  moisture: number | null
  /** 动态启动阈值 θ_start = θfc − p(θfc−θwp) */
  thetaStart: number | null
  thetaFc: number | null
  thetaWp: number | null
  /** 硬上限（后端可直接下发；无则前端按 θfc+offset 计算） */
  hardMax?: number | null
  ec?: number | null
  ph?: number | null
  stage?: string | null
  jobId: number | null
  lastIrrigTime?: string | null
  alarms?: FieldStatusAlarm[]
}

/** 作物生育期阶段（详细字段，兼容契约简写 days/kc/p） */
export interface CropStage {
  name: string
  startDay?: number
  endDay?: number
  days?: number
  startKc?: number
  endKc?: number
  kc?: number
  startP?: number
  endP?: number
  p?: number
  zrMm: number
}

export interface CropModel {
  code: string
  name: string
  stages: CropStage[]
}

export type JobStatus = 'PLANNED' | 'RUNNING' | 'DONE' | 'ABORTED'
export type TriggerType = 'AUTO' | 'MANUAL' | 'SAFETY_OFF'

/** 决策服务输出 */
export type DecisionAction = 'IRRIGATE' | 'HOLD' | 'SKIP' | 'FORBID'

export interface Decision {
  decision: DecisionAction
  stage?: string
  thetaStart: number
  thetaTarget: number
  /** 亏缺水深 mm */
  deficitMm: number
  /** 计划灌量 m³ */
  volumeM3: number
  /** 预计时长 秒 */
  durationSec: number
  clampReason?: string
  et0MmDay?: number
  etcMmDay?: number
  reasons?: string[]
}

export interface Job {
  id: number
  fieldId: number
  fieldName?: string
  triggerType?: TriggerType
  decision?: Decision
  startTime?: string | null
  endTime?: string | null
  plannedM3?: number
  appliedM3?: number
  status: JobStatus
  reason?: string
  stopReason?: string
  durationSec?: number
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}

export type AlarmLevel = 'INFO' | 'WARN' | 'CRITICAL'

export interface Alarm {
  id: number
  level: AlarmLevel
  type: string
  deviceId?: string | number | null
  deviceCode?: string | null
  fieldId?: number | null
  fieldName?: string | null
  message: string
  acknowledged: boolean
  time: string
}

export type DeviceType =
  | 'SOIL_SENSOR'
  | 'WEATHER_STATION'
  | 'VALVE'
  | 'FLOW_METER'
  | string

export interface Device {
  id?: number
  code: string
  type: DeviceType
  gatewaySn: string
  modbusAddr?: number
  protocolConfig?: Record<string, unknown>
  online: boolean
  lastHeartbeat?: string | null
  /** 最新遥测值 */
  latest?: Record<string, number | string | null>
}

export interface GatewayInterlocks {
  moistureHardMax?: number
  sensorLostSec?: number
  valveFaultSec?: number
  ecHigh?: number
  phLow?: number
  phHigh?: number
  [key: string]: number | undefined
}

export interface Gateway {
  sn: string
  name?: string
  online: boolean
  queueDepth: number
  lastHeartbeat?: string | null
  pollIntervalSec?: number
  interlocks?: GatewayInterlocks
}

/** POST /gateways/{sn}/config 请求体 */
export interface GatewayConfigPayload {
  pollIntervalSec: number
  interlocks: GatewayInterlocks
}

/** GET /telemetry 返回 { columns, rows } */
export interface TelemetryDTO {
  columns: string[]
  rows: Array<Array<string | number | null>>
}

/** 手动控制请求体 */
export interface ControlPayload {
  action: 'OPEN' | 'CLOSE'
  volumeM3?: number
  force?: boolean
}
