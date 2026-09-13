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

  // ---- 阈值策略：土壤湿度上下限 + 气象联动 + 安全联锁 ----
  moistureLowerPct?: number
  moistureUpperPct?: number
  weatherLinked?: boolean
  windMaxMs?: number
  tempMin?: number
  tempMax?: number
  humidityMin?: number
  rainTodaySkipMm?: number
  forecastSkipMm?: number
  forecastDays?: number
  /** 缺水联锁 */
  pressureMinKpa?: number
  flowMinM3h?: number
  waterLostDelaySec?: number
  /** 施肥泵过载电流 A */
  pumpOverloadA?: number
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
  /** 比例注肥泵编码 */
  fertPumpCode?: string | null
  /** 注肥比例 %（体积比） */
  injectRatioPct?: number
  /** 轮灌优先级（小者优先） */
  priority?: number
  /** 允许灌溉时间窗（一天内分钟） */
  windowStartMin?: number | null
  windowEndMin?: number | null
  /** 周允许位图 周一..周日 */
  windowDays?: string
  /** 注肥阶段 */
  fertPlan?: FertPhase[]
  /** 作物品种 */
  cropVariety?: string
  sensorCodes?: string[]
  config: FieldConfig
}

/** 注肥阶段（PRE_WATER 清水 / MID_RUN 注肥 / FLUSH 冲洗） */
export interface FertPhase {
  phase: 'PRE_WATER' | 'MID_RUN' | 'FLUSH' | string
  ratioPct: number
  durationFraction: number
}

/** 作物生育期记录 */
export interface StageRecord {
  id?: number
  fieldId?: number
  stageCode: string
  stageName?: string
  recordDate: string
  note?: string
  operator?: string
  createdAt?: string
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
  | 'FERT_PUMP'
  | 'PRESSURE_SENSOR'
  | string

/** 设备投运状态 */
export type DeviceOperationalStatus = 'UNKNOWN' | 'ENABLED' | 'DISABLED' | 'FAULT'

export interface Device {
  id?: number
  code: string
  name?: string
  type: DeviceType
  gatewaySn: string
  modbusAddr?: number
  protocolConfig?: Record<string, unknown>
  params?: Record<string, unknown>
  linkedField?: number | null
  online: boolean
  status?: DeviceOperationalStatus
  /** 执行器当前开度 0-100 */
  opening?: number | null
  lastHeartbeat?: string | null
  registeredAt?: string
  /** 最新遥测值 */
  latest?: Record<string, number | string | null>
}

export interface DevicePayload {
  code: string
  name?: string
  type: string
  gatewaySn?: string
  modbusAddr?: number
  protocolConfig?: Record<string, unknown>
  params?: Record<string, unknown>
  linkedField?: number | null
  status?: string
}

/** POST /api/devices/{code}/command */
export interface DeviceCommandPayload {
  action: 'START' | 'STOP' | 'SET_OPENING'
  opening?: number
  jobId?: number
}

export interface GatewayInterlocks {
  moistureHardMax?: number
  sensorLostSec?: number
  valveFaultSec?: number
  ecHigh?: number
  phLow?: number
  phHigh?: number
  pressureMinKpa?: number
  flowMinM3h?: number
  waterLostDelaySec?: number
  pumpOverloadA?: number
  commLostSec?: number
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

// ---------------- 轮灌计划 ----------------

export type RotationItemStatus = 'PENDING' | 'RELEASED' | 'DONE' | 'SKIPPED' | 'BLOCKED'
export type RotationPlanStatus =
  | 'DRAFT'
  | 'SCHEDULED'
  | 'RUNNING'
  | 'DONE'
  | 'CANCELLED'

export interface RotationPlanItem {
  id: number
  planId: number
  fieldId: number
  seq: number
  priority: number
  scheduledStart: string
  plannedVolumeM3?: number | null
  jobId?: number | null
  status: RotationItemStatus
  skipReason?: string | null
}

export interface RotationPlan {
  id: number
  name: string
  status: RotationPlanStatus
  planDate?: string | null
  generatedAt?: string
  generatedBy?: string
  note?: string
  totalPlannedM3?: number
  totalAppliedM3?: number
  items?: RotationPlanItem[]
}

export interface RotationGeneratePayload {
  name?: string
  planDate?: string
  generatedBy?: string
  note?: string
  fieldIds?: number[]
  startHour?: number
  startMinute?: number
}

// ---------------- 灌肥台账 ----------------

export type LedgerKind = 'WATER' | 'FERTIGATION'
export type LedgerMode = 'AUTO' | 'MANUAL' | 'SCHEDULED' | 'SAFETY'

export interface LedgerRecord {
  id: number
  fieldId: number
  fieldName?: string
  cropVariety?: string
  jobId?: number | null
  planItemId?: number | null
  kind: LedgerKind
  startTime: string
  endTime?: string | null
  waterM3: number
  fertilizerKg?: number
  fertilizerL?: number
  fertilizerName?: string
  injectRatioPct?: number
  executionMode: LedgerMode
  stopReason?: string
}

export interface LedgerSummary {
  date: string
  waterM3: number
  fertilizerL: number
  fertilizerKg: number
  events: number
}
