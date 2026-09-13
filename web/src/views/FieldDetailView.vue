<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal, Empty } from 'ant-design-vue'
import dayjs from 'dayjs'
import {
  addStageRecord,
  deleteStageRecord,
  evaluateDecision,
  getDevices,
  getFieldStatus,
  getFields,
  getJobs,
  getStageRecords,
  getTelemetry,
  sendControl,
  updateField
} from '@/api'
import type {
  Decision,
  Device,
  Field,
  FieldConfig,
  FieldStatus,
  Job,
  StageRecord,
  TelemetryDTO
} from '@/types'
import { formatDuration, formatNum, formatPercent, formatTime, formatVolume } from '@/utils/format'
import { DECISION_COLOR, DECISION_TEXT, valveColor, valveText } from '@/utils/labels'
import MoistureChart from '@/components/MoistureChart.vue'
import MetricLineChart from '@/components/MetricLineChart.vue'

const route = useRoute()
const router = useRouter()
const fieldId = Number(route.params.id)

const loading = ref(false)
const field = ref<Field | null>(null)
const status = ref<FieldStatus | null>(null)
const runningJob = ref<Job | null>(null)
const todayJobs = ref<Job[]>([])

// ---- 遥测 ----
const range = ref<'6h' | '24h' | '72h' | '7d'>('24h')
const chartLoading = ref(false)
const times = ref<string[]>([])
const moistValues = ref<Array<number | null>>([])
const ecValues = ref<Array<number | null>>([])
const phValues = ref<Array<number | null>>([])

const AGG_MAP: Record<string, string> = { '6h': '5m', '24h': '10m', '72h': '30m', '7d': '1h' }
const RANGE_OPTIONS = [
  { label: '近 6 小时', value: '6h' },
  { label: '近 24 小时', value: '24h' },
  { label: '近 72 小时', value: '72h' },
  { label: '近 7 天', value: '7d' }
]

let pollTimer: ReturnType<typeof setInterval> | null = null

const hardMax = computed(() => {
  if (status.value?.hardMax != null) return status.value.hardMax
  const fc = status.value?.thetaFc ?? field.value?.config.thetaFc
  const off = field.value?.config.hardMaxOffsetPct ?? 3
  return fc != null ? fc + off : null
})

// ---- 手动控制 ----
const openVolume = ref<number>(2)
const forceOpen = ref(false)
const controlLoading = ref(false)

// ---- 决策试算 ----
const decisionLoading = ref(false)
const decision = ref<Decision | null>(null)

// ---- 联锁/禁用逻辑 ----
const interlockReasons = computed<string[]>(() => {
  const reasons: string[] = []
  const s = status.value
  const f = field.value
  if (!s || !f) return ['状态加载中']
  if (s.valveState === 'FAULT') reasons.push('阀门故障（INTERLOCK_VALVE_FAULT）')
  if (s.moisture == null) reasons.push('湿度数据缺失（可能传感器失联）')
  if (hardMax.value != null && s.moisture != null && s.moisture >= hardMax.value) {
    reasons.push('湿度 ≥ 硬上限（INTERLOCK_MOISTURE_HARD_MAX）')
  }
  for (const a of s.alarms || []) {
    if (a.level === 'CRITICAL') reasons.push(`未处理严重告警：${a.message || a.type || ''}`)
  }
  return reasons
})

const canOpen = computed(() => interlockReasons.value.length === 0 && status.value?.valveState !== 'OPEN')
const canClose = computed(() => status.value?.valveState === 'OPEN' || status.value?.valveState === 'OPENING')
const isAuto = computed(() => (status.value?.mode ?? field.value?.config.mode) === 'AUTO')
const jobProgress = computed(() => {
  const j = runningJob.value
  if (!j || j.plannedM3 == null || j.plannedM3 <= 0) return 0
  return Math.min(100, Math.round(((j.appliedM3 ?? 0) / j.plannedM3) * 100))
})

// ---- 配置表单 ----
const configForm = reactive<FieldConfig>({
  thetaFc: 30,
  thetaWp: 12,
  mode: 'AUTO',
  hardMaxOffsetPct: 3,
  hardMin: 8,
  moistureLowerPct: 14,
  maxDurationSec: 900,
  minIntervalH: 0.5,
  ecMin: 1.2,
  ecMax: 2.6,
  phMin: 5.5,
  phMax: 7.5,
  rainSkipMm: 5,
  wetRatio: 0.8,
  efficiency: 0.9,
  enabled: true,
  weatherLinked: false,
  windMaxMs: 6,
  tempMin: 5,
  tempMax: 38,
  humidityMin: 30,
  rainTodaySkipMm: 5,
  forecastSkipMm: 10,
  forecastDays: 3,
  pressureMinKpa: 80,
  flowMinM3h: 0.5,
  waterLostDelaySec: 15,
  pumpOverloadA: 8
})
const configSaving = ref(false)

// ---- 灌区基础信息（作物品种/优先级/注肥泵/注肥比/灌溉时窗） ----
const baseSaving = ref(false)
const baseForm = reactive({
  cropVariety: '',
  priority: 100,
  fertPumpCode: undefined as string | undefined,
  injectRatioPct: 0,
  windowStartMin: null as number | null,
  windowEndMin: null as number | null
})
const devices = ref<Device[]>([])
const pumpOptions = computed(() => devices.value.filter((d) => d.type === 'FERT_PUMP'))

function syncBaseForm() {
  if (!field.value) return
  baseForm.cropVariety = field.value.cropVariety ?? ''
  baseForm.priority = field.value.priority ?? 100
  baseForm.fertPumpCode = field.value.fertPumpCode ?? undefined
  baseForm.injectRatioPct = field.value.injectRatioPct ?? 0
  baseForm.windowStartMin = field.value.windowStartMin ?? null
  baseForm.windowEndMin = field.value.windowEndMin ?? null
}

async function onSaveBase() {
  if (!field.value) return
  baseSaving.value = true
  try {
    await updateField(fieldId, {
      ...field.value,
      cropVariety: baseForm.cropVariety,
      priority: baseForm.priority,
      fertPumpCode: baseForm.fertPumpCode,
      injectRatioPct: baseForm.injectRatioPct,
      windowStartMin: baseForm.windowStartMin,
      windowEndMin: baseForm.windowEndMin
    })
    message.success('灌区信息已保存')
    await loadBase()
  } finally {
    baseSaving.value = false
  }
}

// ---- 生育期记录 ----
const stages = ref<StageRecord[]>([])
const stageSaving = ref(false)
const stageDate = ref(dayjs().format('YYYY-MM-DD'))
const stageForm = reactive({
  stageCode: 'initial',
  stageName: '',
  note: '',
  operator: ''
})
const STAGE_OPTIONS = [
  { value: 'initial', label: '苗期 initial' },
  { value: 'development', label: '发育期 development' },
  { value: 'mid', label: '中期 mid' },
  { value: 'late', label: '后期 late' }
]
const simpleImage = Empty.PRESENTED_IMAGE_SIMPLE
const todayStr = dayjs().format('YYYY-MM-DD')

function stageLabel(code: string) {
  return STAGE_OPTIONS.find((s) => s.value === code)?.label ?? code
}

function minToHHmm(v: number | null | undefined) {
  if (v == null) return undefined
  return `${String(Math.floor(v / 60)).padStart(2, '0')}:${String(v % 60).padStart(2, '0')}`
}
function hhmmToMin(v?: string) {
  if (!v) return null
  const [h, m] = v.split(':').map(Number)
  return h * 60 + m
}
const windowStart = computed({
  get: () => minToHHmm(baseForm.windowStartMin),
  set: (v?: string) => { baseForm.windowStartMin = hhmmToMin(v) }
})
const windowEnd = computed({
  get: () => minToHHmm(baseForm.windowEndMin),
  set: (v?: string) => { baseForm.windowEndMin = hhmmToMin(v) }
})

async function loadStages() {
  stages.value = await getStageRecords(fieldId).catch(() => [])
}

async function onAddStage() {
  stageSaving.value = true
  try {
    await addStageRecord(fieldId, {
      stageCode: stageForm.stageCode,
      stageName: STAGE_OPTIONS.find((s) => s.value === stageForm.stageCode)?.label,
      recordDate: stageDate.value,
      note: stageForm.note,
      operator: stageForm.operator
    })
    message.success('生育期已记录')
    stageForm.note = ''
    await loadStages()
  } finally {
    stageSaving.value = false
  }
}

async function onRemoveStage(r: StageRecord) {
  if (r.id == null) return
  await deleteStageRecord(fieldId, r.id)
  message.success('记录已删除')
  await loadStages()
}

function minToText(v: number | null | undefined) {
  if (v == null) return '不限'
  const h = Math.floor(v / 60)
  const m = v % 60
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
}

function syncConfigForm() {
  if (!field.value) return
  Object.assign(configForm, field.value.config)
}

async function loadBase() {
  loading.value = true
  try {
    const [fs, ds] = await Promise.all([
      getFields().catch(() => []),
      getDevices().catch(() => [] as Device[])
    ])
    devices.value = ds
    field.value = fs.find((x) => x.id === fieldId) || null
    if (!field.value) return
    syncConfigForm()
    syncBaseForm()
    await Promise.all([refreshStatus(), loadStages()])
  } finally {
    loading.value = false
  }
}

async function refreshStatus() {
  const s = await getFieldStatus(fieldId).catch(() => null)
  if (s) {
    status.value = s
    if (s.mode) configForm.mode = s.mode
  }
  const jobPage = await getJobs({ fieldId, status: 'RUNNING', page: 1, size: 1 }).catch(() => null)
  runningJob.value = jobPage?.records?.[0] || null
  const recentPage = await getJobs({ fieldId, page: 1, size: 20 }).catch(() => null)
  todayJobs.value = (recentPage?.records || []).filter((j) => j.startTime)
}

async function loadTelemetry() {
  chartLoading.value = true
  try {
    const dto = await getTelemetry({
      fieldId,
      metrics: 'soilMoist,ec,ph',
      range: range.value,
      agg: AGG_MAP[range.value]
    }).catch(() => null as TelemetryDTO | null)
    parseTelemetry(dto)
  } finally {
    chartLoading.value = false
  }
}

function parseTelemetry(dto: TelemetryDTO | null) {
  times.value = []
  moistValues.value = []
  ecValues.value = []
  phValues.value = []
  if (!dto?.columns || !dto?.rows) return
  const idx = (name: string) => dto.columns.findIndex((c) => c.toLowerCase() === name.toLowerCase())
  const iTs = dto.columns.findIndex((c) => c === 'ts' || c === 'time' || c.toLowerCase().includes('time'))
  const iM = idx('soilMoist')
  const iEc = idx('ec')
  const iPh = idx('ph')
  for (const row of dto.rows) {
    times.value.push(iTs >= 0 ? String(row[iTs]) : '')
    moistValues.value.push(iM >= 0 ? toNum(row[iM]) : null)
    ecValues.value.push(iEc >= 0 ? toNum(row[iEc]) : null)
    phValues.value.push(iPh >= 0 ? toNum(row[iPh]) : null)
  }
}

function toNum(v: unknown): number | null {
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isNaN(n) ? null : n
}

async function onModeChange(mode: 'AUTO' | 'MANUAL') {
  if (!field.value) return
  const prev = field.value.config.mode
  field.value.config.mode = mode
  configForm.mode = mode
  try {
    await updateField(fieldId, { ...field.value, config: { ...field.value.config, mode } })
    message.success(`已切换为${mode === 'AUTO' ? '自动（引擎自动评估启停）' : '手动（引擎仅监视）'}`)
    await refreshStatus()
  } catch {
    field.value.config.mode = prev
    configForm.mode = prev
  }
}

async function doOpen() {
  if (!canOpen.value) return
  controlLoading.value = true
  try {
    await sendControl(fieldId, {
      action: 'OPEN',
      volumeM3: openVolume.value,
      force: isAuto.value ? forceOpen.value : true
    })
    message.success('开阀指令已下发（幂等 commandId 由后端生成）')
    forceOpen.value = false
    await refreshStatus()
  } finally {
    controlLoading.value = false
  }
}

function onOpenClick() {
  if (isAuto.value && !forceOpen.value) {
    Modal.info({
      title: 'AUTO 模式下手动开阀需强制接管',
      content: `当前为 AUTO 自动模式，请先勾选“强制接管（force）”，计划灌量 ${openVolume.value} m³。边缘硬联锁仍然生效。`,
      okText: '我知道了'
    })
    return
  }
  if (isAuto.value) {
    Modal.confirm({
      title: '确认强制（force）手动开阀？',
      content: `将以 force=true 下发 OPEN，计划灌量 ${openVolume.value} m³。边缘硬联锁仍然生效，任何模式下触发联锁都会立即关阀。`,
      okText: '确认强制开阀',
      okType: 'danger',
      cancelText: '取消',
      onOk: doOpen
    })
    return
  }
  doOpen()
}

async function onClose() {
  controlLoading.value = true
  try {
    await sendControl(fieldId, { action: 'CLOSE' })
    message.success('关阀指令已下发')
    await refreshStatus()
  } finally {
    controlLoading.value = false
  }
}

async function onEvaluate() {
  decisionLoading.value = true
  try {
    decision.value = await evaluateDecision(fieldId)
  } finally {
    decisionLoading.value = false
  }
}

async function onSaveConfig() {
  if (!field.value) return
  configSaving.value = true
  try {
    const payload = { ...field.value, config: { ...configForm } }
    await updateField(fieldId, payload)
    field.value = { ...field.value, config: { ...configForm } }
    message.success('阈值/配置已保存')
    await refreshStatus()
  } finally {
    configSaving.value = false
  }
}

onMounted(() => {
  loadBase()
  loadTelemetry()
  pollTimer = setInterval(refreshStatus, 30000)
})
onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<template>
  <div v-if="!field && !loading">
    <a-result status="warning" :title="`未找到 ID=${fieldId} 的田块`" sub-title="请从田块列表进入，或确认后端服务已启动">
      <template #extra>
        <a-button type="primary" @click="router.push('/fields')">返回田块列表</a-button>
      </template>
    </a-result>
  </div>

  <a-spin v-else :spinning="loading">
    <!-- 顶部状态区 -->
    <a-card>
      <div style="display: flex; justify-content: space-between; align-items: flex-start; flex-wrap: wrap; gap: 12px">
        <a-space size="large" align="start" wrap>
          <div>
            <div style="color: #888; font-size: 13px">{{ field?.name }} · {{ field?.cropCode }} · {{ field?.irrigationMode === 'DRIP' ? '滴灌' : '喷灌' }}</div>
            <a-statistic
              title="当前土壤湿度（根区均值）"
              :value="status?.moisture ?? null"
              :precision="1"
              suffix="%"
              :value-style="{ color: '#1677ff', fontSize: 32 }"
            />
          </div>
          <a-divider type="vertical" style="height: 64px" />
          <a-descriptions :column="3" size="small" bordered style="max-width: 720px">
            <a-descriptions-item label="θ_start 启动阈值">
              <strong style="color: #fa8c16">{{ formatPercent(status?.thetaStart) }}</strong>
            </a-descriptions-item>
            <a-descriptions-item label="θfc 田持">{{ formatPercent(status?.thetaFc) }}</a-descriptions-item>
            <a-descriptions-item label="θwp 萎蔫点">{{ formatPercent(status?.thetaWp) }}</a-descriptions-item>
            <a-descriptions-item label="硬上限">{{ formatPercent(hardMax) }}</a-descriptions-item>
            <a-descriptions-item label="阀门状态">
              <a-tag :color="valveColor(status?.valveState ?? 'CLOSED')">
                {{ valveText(status?.valveState ?? 'CLOSED') }}
              </a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="生育期 / 最近灌溉">
              {{ status?.stage || '—' }} / {{ formatTime(status?.lastIrrigTime) }}
            </a-descriptions-item>
          </a-descriptions>
        </a-space>

        <div style="text-align: right">
          <div style="margin-bottom: 6px">
            <span style="color: #888; font-size: 13px; margin-right: 8px">控制模式</span>
            <a-switch
              :checked="(status?.mode ?? field?.config.mode) === 'AUTO'"
              checked-children="AUTO 自动"
              un-checked-children="MANUAL 手动"
              @change="(v: boolean) => onModeChange(v ? 'AUTO' : 'MANUAL')"
            />
          </div>
          <div v-if="runningJob" style="min-width: 260px">
            <div style="font-size: 12px; color: #888; margin-bottom: 2px">
              作业 #{{ runningJob.id }} 灌水进度
            </div>
            <a-progress :percent="jobProgress" status="active" />
            <div style="font-size: 12px; color: #666">
              计划 {{ formatVolume(runningJob.plannedM3) }} ｜ 已灌
              <strong style="color: #389e0d">{{ formatVolume(runningJob.appliedM3) }}</strong>
              ｜ 已运行 {{ formatDuration(runningJob.durationSec) }}
            </div>
          </div>
          <a-tag v-else color="default">当前无进行中作业</a-tag>
        </div>
      </div>
    </a-card>

    <!-- 控制区 -->
    <a-card title="手动控制" style="margin-top: 16px" size="small">
      <a-alert
        v-if="interlockReasons.length"
        type="error"
        show-icon
        style="margin-bottom: 12px"
        message="硬联锁 / 安全前置未满足，禁止开阀"
        :description="interlockReasons.join('；')"
      />
      <a-space wrap size="large">
        <a-input-number
          v-model:value="openVolume"
          :min="0.1"
          :step="0.1"
          :precision="2"
          addon-after="m³"
          style="width: 180px"
          :disabled="!canOpen"
        />
        <a-popconfirm
          v-if="!isAuto"
          title="确认手动开阀？"
          :description="`计划灌量 ${openVolume} m³，达到后自动关阀，仍受边缘硬联锁约束。`"
          ok-text="开阀"
          cancel-text="取消"
          @confirm="doOpen"
        >
          <a-button type="primary" :loading="controlLoading" :disabled="!canOpen">
            手动开阀（OPEN）
          </a-button>
        </a-popconfirm>
        <template v-else>
          <a-checkbox v-model:checked="forceOpen" :disabled="!canOpen">
            强制接管（force，AUTO 下必填）
          </a-checkbox>
          <a-button type="primary" danger :loading="controlLoading" :disabled="!canOpen || !forceOpen" @click="onOpenClick">
            强制开阀（force OPEN）
          </a-button>
        </template>
        <a-popconfirm
          title="确认关阀？"
          ok-text="关阀"
          cancel-text="取消"
          @confirm="onClose"
        >
          <a-button :loading="controlLoading" :disabled="!canClose">关阀（CLOSE）</a-button>
        </a-popconfirm>
        <span style="color: #999; font-size: 12px">
          手动优先：MANUAL 下引擎仅监视；任何模式下边缘联锁（湿度硬上限/传感器失联/EC/pH/阀故障）均可立即关阀。
        </span>
      </a-space>
    </a-card>

    <!-- 趋势图 -->
    <a-card
      class="chart-card"
      style="margin-top: 16px"
      title="最近 24 小时土壤湿度趋势（FAO-56 阈值带）"
    >
      <template #extra>
        <a-radio-group v-model:value="range" button-style="solid" size="small" @change="loadTelemetry">
          <a-radio-button v-for="o in RANGE_OPTIONS" :key="o.value" :value="o.value">{{ o.label }}</a-radio-button>
        </a-radio-group>
      </template>
      <MoistureChart
        :times="times"
        :values="moistValues"
        :theta-fc="status?.thetaFc ?? field?.config.thetaFc"
        :theta-start="status?.thetaStart"
        :hard-max="hardMax"
        :jobs="todayJobs"
        :loading="chartLoading"
      />
      <a-row :gutter="16" style="margin-top: 8px">
        <a-col :span="12">
          <MetricLineChart
            :times="times"
            :values="ecValues"
            metric="ec"
            :min="field?.config.ecMin"
            :max="field?.config.ecMax"
            :loading="chartLoading"
          />
        </a-col>
        <a-col :span="12">
          <MetricLineChart
            :times="times"
            :values="phValues"
            metric="ph"
            :min="field?.config.phMin"
            :max="field?.config.phMax"
            :loading="chartLoading"
          />
        </a-col>
      </a-row>
    </a-card>

    <!-- 决策试算 -->
    <a-card style="margin-top: 16px" size="small">
      <template #title>
        <a-space>
          <span>决策试算（调用 Python 决策服务，只评估不执行）</span>
          <a-button type="primary" size="small" :loading="decisionLoading" @click="onEvaluate">
            POST /fields/{{ fieldId }}/decision/evaluate
          </a-button>
        </a-space>
      </template>
      <a-empty v-if="!decision && !decisionLoading" description="点击按钮立即试算 IRRIGATE / HOLD / SKIP / FORBID" />
      <template v-else-if="decision">
        <a-descriptions bordered :column="3" size="small">
          <a-descriptions-item label="决策">
            <a-tag :color="DECISION_COLOR[decision.decision] || 'default'">
              {{ DECISION_TEXT[decision.decision] || decision.decision }}
            </a-tag>
            <span v-if="decision.clampReason" style="color: #999; font-size: 12px">
              （限幅原因：{{ decision.clampReason }}）
            </span>
          </a-descriptions-item>
          <a-descriptions-item label="生育期">{{ decision.stage || '—' }}</a-descriptions-item>
          <a-descriptions-item label="θ_start">{{ formatPercent(decision.thetaStart) }}</a-descriptions-item>
          <a-descriptions-item label="θ_target 恢复目标">{{ formatPercent(decision.thetaTarget) }}</a-descriptions-item>
          <a-descriptions-item label="亏缺水深 deficit">{{ formatNum(decision.deficitMm) }} mm</a-descriptions-item>
          <a-descriptions-item label="计划灌量">{{ formatVolume(decision.volumeM3) }}</a-descriptions-item>
          <a-descriptions-item label="预计时长">{{ formatDuration(decision.durationSec) }}</a-descriptions-item>
          <a-descriptions-item label="ET0 / ETc">
            {{ formatNum(decision.et0MmDay) }} / {{ formatNum(decision.etcMmDay) }} mm/d
          </a-descriptions-item>
          <a-descriptions-item label="判定依据" :span="3">
            <a-alert
              v-for="(r, i) in decision.reasons || []"
              :key="i"
              :message="r"
              type="info"
              show-icon
              style="margin-bottom: 4px"
            />
            <span v-if="!(decision.reasons || []).length" style="color: #999">无</span>
          </a-descriptions-item>
        </a-descriptions>
      </template>
    </a-card>

    <!-- 灌区基础信息（品种/优先级/注肥泵/时窗） -->
    <a-card v-if="field" title="灌区信息（作物品种 / 轮灌优先级 / 施肥泵 / 灌溉时窗）" style="margin-top: 16px" size="small">
      <a-form layout="vertical">
        <a-row :gutter="16">
          <a-col :span="6">
            <a-form-item label="作物品种">
              <a-input v-model:value="baseForm.cropVariety" placeholder="如 金棚一号" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="轮灌优先级（小者优先）">
              <a-input-number v-model:value="baseForm.priority" :min="1" :max="9999" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="比例注肥泵">
              <a-select v-model:value="baseForm.fertPumpCode" allow-clear placeholder="无（仅灌水）">
                <a-select-option v-for="p in pumpOptions" :key="p.code" :value="p.code">
                  {{ p.name || p.code }}{{ p.online ? '' : '（离线）' }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="注肥比例（体积 %）">
              <a-input-number v-model:value="baseForm.injectRatioPct" :min="0" :max="20" :step="0.1"
                addon-after="%" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item :label="`允许时窗起（${minToText(baseForm.windowStartMin)}）`">
              <a-time-picker v-model:value="windowStart" format="HH:mm" value-format="HH:mm"
                placeholder="不限制" allow-clear style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item :label="`允许时窗止（${minToText(baseForm.windowEndMin)}）`">
              <a-time-picker v-model:value="windowEnd" format="HH:mm" value-format="HH:mm"
                placeholder="不限制" allow-clear style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-button type="primary" :loading="baseSaving" @click="onSaveBase">保存灌区信息</a-button>
      </a-form>
    </a-card>

    <!-- 作物生育期记录 -->
    <a-card v-if="field" title="作物生育期记录" style="margin-top: 16px" size="small">
      <a-row :gutter="16">
        <a-col :span="14">
          <a-timeline>
            <a-timeline-item v-for="s in stages" :key="s.id" :color="s.recordDate <= todayStr ? 'green' : 'gray'">
              <a-space>
                <strong>{{ stageLabel(s.stageCode) }}</strong>
                <span>{{ s.recordDate }}</span>
                <a-tag v-if="s.operator" color="default">{{ s.operator }}</a-tag>
                <a-popconfirm title="删除该记录？" @confirm="onRemoveStage(s)">
                  <a-button type="link" size="small" danger>删除</a-button>
                </a-popconfirm>
              </a-space>
              <div v-if="s.note" style="color: #888; font-size: 12px">{{ s.note }}</div>
            </a-timeline-item>
            <a-empty v-if="!stages.length" :image="simpleImage" description="暂无生育期记录（默认按播期+作物模型推导当前生育期）" />
          </a-timeline>
        </a-col>
        <a-col :span="10">
          <a-form layout="vertical">
            <a-form-item label="生育期">
              <a-select v-model:value="stageForm.stageCode" :options="STAGE_OPTIONS" />
            </a-form-item>
            <a-form-item label="起始日期">
              <a-date-picker v-model:value="stageDate" value-format="YYYY-MM-DD" style="width: 100%" />
            </a-form-item>
            <a-form-item label="操作人">
              <a-input v-model:value="stageForm.operator" placeholder="农艺师姓名" />
            </a-form-item>
            <a-form-item label="备注">
              <a-textarea v-model:value="stageForm.note" :rows="2" placeholder="如 定植、始花期、坐果期…" />
            </a-form-item>
            <a-button type="primary" :loading="stageSaving" @click="onAddStage">登记生育期</a-button>
          </a-form>
        </a-col>
      </a-row>
    </a-card>

    <!-- 阈值/配置表单 -->
    <a-card v-if="field" title="阈值策略与安全联锁配置（FieldConfig）" style="margin-top: 16px" size="small">
      <a-form :model="configForm" layout="vertical">
        <a-row :gutter="16">
          <a-col :span="6">
            <a-form-item label="θfc 田间持水量（体积含水率 %）">
              <a-input-number v-model:value="configForm.thetaFc" :min="0" :max="100" :step="0.1" addon-after="%" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="θwp 萎蔫点（%）">
              <a-input-number v-model:value="configForm.thetaWp" :min="0" :max="100" :step="0.1" addon-after="%" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="硬上限偏移 hardMaxOffsetPct（θfc+N 个百分点）">
              <a-input-number v-model:value="configForm.hardMaxOffsetPct" :min="0" :step="0.5" addon-after="百分点" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="湿度硬下限 hardMin（%）">
              <a-input-number v-model:value="configForm.hardMin" :min="0" :max="100" :step="0.1" addon-after="%" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="单次最大时长 maxDurationSec">
              <a-input-number v-model:value="configForm.maxDurationSec" :min="60" :step="60" addon-after="秒" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="最小作业间隔 minIntervalH">
              <a-input-number v-model:value="configForm.minIntervalH" :min="0" :step="0.5" addon-after="小时" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="EC 下限 ecMin（mS/cm）">
              <a-input-number v-model:value="configForm.ecMin" :min="0" :step="0.1" addon-after="mS/cm" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="EC 上限 ecMax（mS/cm）">
              <a-input-number v-model:value="configForm.ecMax" :min="0" :step="0.1" addon-after="mS/cm" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="pH 下限 phMin">
              <a-input-number v-model:value="configForm.phMin" :min="0" :max="14" :step="0.1" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="pH 上限 phMax">
              <a-input-number v-model:value="configForm.phMax" :min="0" :max="14" :step="0.1" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="降雨跳过阈值 rainSkipMm">
              <a-input-number v-model:value="configForm.rainSkipMm" :min="0" :step="1" addon-after="mm" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="湿润比 wetRatio（p_wet）">
              <a-input-number v-model:value="configForm.wetRatio" :min="0" :max="1" :step="0.05" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="灌溉水利用系数 efficiency（η）">
              <a-input-number v-model:value="configForm.efficiency" :min="0.1" :max="1" :step="0.05" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="启用自动评估 enabled">
              <a-switch v-model:checked="configForm.enabled" checked-children="启用" un-checked-children="停用" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="土壤湿度下限 moistureLowerPct（旱情告警 %）">
              <a-input-number v-model:value="configForm.moistureLowerPct" :min="0" :max="100" :step="0.5" addon-after="%" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-divider orientation="left" plain style="font-size: 13px">气象联动条件（仅自动模式）</a-divider>
        <a-row :gutter="16">
          <a-col :span="6">
            <a-form-item label="启用气象联动">
              <a-switch v-model:checked="configForm.weatherLinked" checked-children="开" un-checked-children="关" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="风速上限（m/s）">
              <a-input-number v-model:value="configForm.windMaxMs" :min="0" :step="0.5" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="气温下限（℃）">
              <a-input-number v-model:value="configForm.tempMin" :step="0.5" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="气温上限（℃）">
              <a-input-number v-model:value="configForm.tempMax" :step="0.5" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="空气湿度下限（%）">
              <a-input-number v-model:value="configForm.humidityMin" :min="0" :max="100" addon-after="%" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="当日降雨跳过（mm）">
              <a-input-number v-model:value="configForm.rainTodaySkipMm" :min="0" :step="1" addon-after="mm" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="预报降雨跳过（mm）">
              <a-input-number v-model:value="configForm.forecastSkipMm" :min="0" :step="1" addon-after="mm" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="预报天数">
              <a-input-number v-model:value="configForm.forecastDays" :min="1" :max="7" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-divider orientation="left" plain style="font-size: 13px">安全联锁阈值（云端 + 边缘双重生效）</a-divider>
        <a-row :gutter="16">
          <a-col :span="6">
            <a-form-item label="主管道水压下限（kPa）">
              <a-input-number v-model:value="configForm.pressureMinKpa" :min="0" :step="5" addon-after="kPa" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="阀开最低瞬时流量（m³/h）">
              <a-input-number v-model:value="configForm.flowMinM3h" :min="0" :step="0.1" addon-after="m³/h" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="缺水判定延时（秒）">
              <a-input-number v-model:value="configForm.waterLostDelaySec" :min="1" :step="1" addon-after="秒" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="施肥泵过载电流（A）">
              <a-input-number v-model:value="configForm.pumpOverloadA" :min="0" :step="0.5" addon-after="A" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-space>
          <a-button type="primary" :loading="configSaving" @click="onSaveConfig">保存配置（PUT /fields/{{ fieldId }}）</a-button>
          <a-button @click="syncConfigForm">重置</a-button>
          <span style="color: #999; font-size: 12px">
            θ_start 由决策服务按当前生育期动态计算：θ_start = θfc − p(θfc − θwp)
          </span>
        </a-space>
      </a-form>
    </a-card>
  </a-spin>
</template>
