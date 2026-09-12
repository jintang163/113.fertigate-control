<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  AppstoreOutlined,
  ThunderboltOutlined,
  ApiOutlined,
  AlertOutlined,
  ReloadOutlined
} from '@ant-design/icons-vue'
import { getAlarms, getDevices, getFields, getFieldStatus, getJobs } from '@/api'
import { ALARM_LEVEL_COLOR, ALARM_LEVEL_TEXT, JOB_STATUS_COLOR, JOB_STATUS_TEXT, valveColor, valveText } from '@/utils/labels'
import { formatPercent, formatTime, formatVolume } from '@/utils/format'
import type { Alarm, Field, FieldStatus, Job, PageResult } from '@/types'

const router = useRouter()

const loading = ref(false)
const fields = ref<Field[]>([])
const statusMap = ref<Record<number, FieldStatus>>({})
const onlineDevices = ref(0)
const totalDevices = ref(0)
const jobs = ref<Job[]>([])
const alarms = ref<Alarm[]>([])
const todayAlarmCount = ref(0)
const lastUpdate = ref<string>('')
let timer: ReturnType<typeof setInterval> | null = null

const irrigatingCount = computed(
  () => Object.values(statusMap.value).filter((s) => s?.valveState === 'OPEN' || s?.jobId != null).length
)

interface MoistRow {
  id: number
  name: string
  moisture: number | null
  thetaStart: number | null
  thetaFc: number | null
  valve: string
  percent: number
  needIrrig: boolean
}

const moistureRows = computed<MoistRow[]>(() =>
  fields.value.map((f) => {
    const st = statusMap.value[f.id]
    const m = st?.moisture ?? null
    const ts = st?.thetaStart ?? null
    const fc = st?.thetaFc ?? null
    // 相对阈值带位置：θwp→θfc 映射 0→100（θwp 缺失时用 θ_start 代替）
    const wp = st?.thetaWp ?? f.config?.thetaWp ?? null
    let percent: number
    if (m == null) percent = 0
    else if (fc != null && wp != null && fc > wp) percent = Math.min(100, Math.max(0, ((m - wp) / (fc - wp)) * 100))
    else if (fc != null && ts != null) percent = Math.min(100, Math.max(0, (m / fc) * 100))
    else percent = Math.min(100, m ?? 0)
    return {
      id: f.id,
      name: f.name,
      moisture: m,
      thetaStart: ts,
      thetaFc: fc,
      valve: st?.valveState ?? 'UNKNOWN',
      percent,
      needIrrig: m != null && ts != null && m <= ts
    }
  })
)

async function load() {
  loading.value = true
  try {
    const fs = await getFields().catch(() => [] as Field[])
    fields.value = fs
    const statuses = await Promise.all(
      fs.map((f) => getFieldStatus(f.id).then((s) => [f.id, s] as const).catch(() => null))
    )
    const map: Record<number, FieldStatus> = {}
    statuses.forEach((x) => {
      if (x) map[x[0]] = x[1]
    })
    statusMap.value = map

    const devices = await getDevices().catch(() => [])
    totalDevices.value = devices.length
    onlineDevices.value = devices.filter((d) => d.online).length

    const jobPage = await getJobs({ page: 1, size: 8 }).catch(() => null as PageResult<Job> | null)
    jobs.value = jobPage?.records ?? []

    const alarmPage = await getAlarms({ page: 1, size: 6 }).catch(() => null as PageResult<Alarm> | null)
    alarms.value = alarmPage?.records ?? []
    const allToday = await getAlarms({ page: 1, size: 1 }).catch(() => null)
    todayAlarmCount.value = allToday?.total ?? alarms.value.length
  } finally {
    loading.value = false
    lastUpdate.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  }
}

onMounted(() => {
  load()
  timer = setInterval(load, 30000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div>
    <div style="display: flex; justify-content: flex-end; margin-bottom: 12px">
      <a-space>
        <span style="color: #999; font-size: 12px">最近更新 {{ lastUpdate || '—' }}（30s 自动刷新）</span>
        <a-button size="small" :icon="h(ReloadOutlined)" :loading="loading" @click="load">刷新</a-button>
      </a-space>
    </div>

    <a-row :gutter="16">
      <a-col :span="6">
        <a-card class="stat-card" :loading="loading">
          <a-statistic title="田块总数" :value="fields.length" :value-style="{ color: '#1677ff' }">
            <template #prefix><AppstoreOutlined /></template>
            <template #suffix>块</template>
          </a-statistic>
        </a-card>
      </a-col>
      <a-col :span="6">
        <a-card class="stat-card" :loading="loading">
          <a-statistic title="灌溉中田块" :value="irrigatingCount" :value-style="{ color: '#389e0d' }">
            <template #prefix><ThunderboltOutlined /></template>
            <template #suffix>块</template>
          </a-statistic>
        </a-card>
      </a-col>
      <a-col :span="6">
        <a-card class="stat-card" :loading="loading">
          <a-statistic title="在线设备" :value="onlineDevices" :value-style="{ color: '#13c2c2' }">
            <template #prefix><ApiOutlined /></template>
            <template #suffix>/ {{ totalDevices }}</template>
          </a-statistic>
        </a-card>
      </a-col>
      <a-col :span="6">
        <a-card class="stat-card" :loading="loading">
          <a-statistic title="告警总数" :value="todayAlarmCount" :value-style="{ color: todayAlarmCount ? '#cf1322' : '#3f3f3f' }">
            <template #prefix><AlertOutlined /></template>
            <template #suffix>条</template>
          </a-statistic>
        </a-card>
      </a-col>
    </a-row>

    <a-row :gutter="16" style="margin-top: 16px">
      <a-col :span="10">
        <a-card class="chart-card" title="各田块土壤湿度 vs θ_start 启动阈值">
          <a-empty v-if="!loading && !moistureRows.length" description="暂无田块，请先在后端配置" />
          <a-spin :spinning="loading">
            <div v-for="row in moistureRows" :key="row.id" style="margin-bottom: 14px; cursor: pointer"
                 @click="router.push(`/fields/${row.id}`)">
              <div style="display: flex; justify-content: space-between; font-size: 13px; margin-bottom: 2px">
                <span>
                  <a-tag :color="valveColor(row.valve)" style="margin-right: 6px">{{ valveText(row.valve) }}</a-tag>
                  <strong>{{ row.name }}</strong>
                  <a-tag v-if="row.needIrrig" color="orange" style="margin-left: 6px">低于 θ_start</a-tag>
                </span>
                <span style="color: #666">
                  当前 <strong :style="{ color: row.needIrrig ? '#fa8c16' : '#1677ff' }">{{ formatPercent(row.moisture) }}</strong>
                  / θ_start {{ formatPercent(row.thetaStart) }} / θfc {{ formatPercent(row.thetaFc) }}
                </span>
              </div>
              <a-progress
                class="moisture-bar"
                :percent="row.percent"
                :stroke-color="row.needIrrig ? '#fa8c16' : '#1677ff'"
                :show-info="false"
                size="small"
              />
            </div>
          </a-spin>
        </a-card>
      </a-col>
      <a-col :span="14">
        <a-card class="chart-card" title="最近灌溉作业">
          <template #extra>
            <a-button type="link" size="small" @click="router.push('/jobs')">全部作业 →</a-button>
          </template>
          <a-table
            :data-source="jobs"
            :loading="loading"
            :pagination="false"
            size="small"
            row-key="id"
            :locale="{ emptyText: '暂无作业记录' }"
          >
            <a-table-column title="作业ID" data-index="id" :width="80" />
            <a-table-column title="田块" :width="120">
              <template #default="{ record }">
                <a @click="router.push(`/fields/${record.fieldId}`)">{{ record.fieldName || record.fieldId }}</a>
              </template>
            </a-table-column>
            <a-table-column title="状态" :width="100">
              <template #default="{ record }">
                <a-tag :color="JOB_STATUS_COLOR[record.status as keyof typeof JOB_STATUS_COLOR]">
                  {{ JOB_STATUS_TEXT[record.status as keyof typeof JOB_STATUS_TEXT] }}
                </a-tag>
              </template>
            </a-table-column>
            <a-table-column title="开始时间">
              <template #default="{ record }">{{ formatTime(record.startTime) }}</template>
            </a-table-column>
            <a-table-column title="计划/实际水量">
              <template #default="{ record }">
                {{ formatVolume(record.plannedM3) }} / {{ formatVolume(record.appliedM3) }}
              </template>
            </a-table-column>
          </a-table>
        </a-card>
      </a-col>
    </a-row>

    <a-row style="margin-top: 16px">
      <a-col :span="24">
        <a-card class="chart-card" title="最新告警">
          <template #extra>
            <a-button type="link" size="small" @click="router.push('/alarms')">告警中心 →</a-button>
          </template>
          <a-list :data-source="alarms" :loading="loading" size="small" :locale="{ emptyText: '暂无告警' }">
            <template #renderItem="{ item }">
              <a-list-item>
                <a-list-item-meta>
                  <template #title>
                    <a-tag :color="ALARM_LEVEL_COLOR[item.level as keyof typeof ALARM_LEVEL_COLOR]">
                      {{ ALARM_LEVEL_TEXT[item.level as keyof typeof ALARM_LEVEL_TEXT] }}
                    </a-tag>
                    <a-tag>{{ item.type }}</a-tag>
                    <span :style="{ fontWeight: item.acknowledged ? 400 : 600 }">{{ item.message }}</span>
                  </template>
                  <template #description>{{ formatTime(item.time) }} · {{ item.fieldName || item.deviceCode || '—' }}</template>
                </a-list-item-meta>
                <template #actions>
                  <a-tag v-if="!item.acknowledged" color="red">未确认</a-tag>
                  <a-tag v-else color="default">已确认</a-tag>
                </template>
              </a-list-item>
            </template>
          </a-list>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>
