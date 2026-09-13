<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { getFields, getLedger, getLedgerSummary } from '@/api'
import type { Field, LedgerKind, LedgerRecord, LedgerSummary } from '@/types'
import {
  LEDGER_KIND_COLOR,
  LEDGER_KIND_TEXT,
  LEDGER_MODE_TEXT
} from '@/utils/labels'
import { formatTime } from '@/utils/format'

const loading = ref(false)
const records = ref<LedgerRecord[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(15)

const fields = ref<Field[]>([])
const fieldFilter = ref<number | undefined>(undefined)
const kindFilter = ref<LedgerKind | ''>('')
const summary = ref<LedgerSummary | null>(null)
const summaryDate = ref(dayjs().format('YYYY-MM-DD'))

const stats = computed(() => [
  { title: '今日灌水', value: summary.value?.waterM3 ?? 0, unit: 'm³', color: '#1677ff' },
  { title: '今日肥液', value: summary.value?.fertilizerL ?? 0, unit: 'L', color: '#d48806' },
  { title: '今日次数', value: summary.value?.events ?? 0, unit: '次', color: '#52c41a' }
])

async function load() {
  loading.value = true
  try {
    const [pageData, s, f] = await Promise.all([
      getLedger({
        fieldId: fieldFilter.value,
        kind: kindFilter.value || undefined,
        page: page.value - 1,
        size: pageSize.value
      }).catch(() => ({ records: [] as LedgerRecord[], total: 0 })),
      getLedgerSummary(summaryDate.value).catch(() => null),
      getFields().catch(() => [] as Field[])
    ])
    records.value = pageData.records
    total.value = pageData.total
    summary.value = s
    fields.value = f
  } finally {
    loading.value = false
  }
}

function onFilter() {
  page.value = 1
  load()
}

function durationMin(r: LedgerRecord) {
  if (!r.endTime) return '进行中'
  const sec = dayjs(r.endTime).diff(dayjs(r.startTime), 'second')
  return `${Math.round(sec / 6) / 10} 分钟`
}

const kindText = (k: string) => LEDGER_KIND_TEXT[k as LedgerKind] ?? k
const kindColor = (k: string) => LEDGER_KIND_COLOR[k as LedgerKind] ?? 'default'
const modeText = (m: string) => LEDGER_MODE_TEXT[m as keyof typeof LEDGER_MODE_TEXT] ?? m

onMounted(load)
</script>

<template>
  <a-space direction="vertical" :size="16" style="width: 100%">
    <a-row :gutter="16">
      <a-col v-for="s in stats" :key="s.title" :span="8">
        <a-card size="small">
          <a-statistic :title="s.title" :value="s.value" :suffix="s.unit"
            :value-style="{ color: s.color }" />
        </a-card>
      </a-col>
    </a-row>

    <a-card title="灌肥台账" size="small">
      <template #extra>
        <a-space>
          <a-select v-model:value="fieldFilter" allow-clear placeholder="全部灌区" style="width: 170px"
            @change="onFilter">
            <a-select-option v-for="f in fields" :key="f.id" :value="f.id">{{ f.name }}</a-select-option>
          </a-select>
          <a-select v-model:value="kindFilter" allow-clear placeholder="全部类型" style="width: 130px"
            @change="onFilter">
            <a-select-option value="WATER">灌水</a-select-option>
            <a-select-option value="FERTIGATION">施肥</a-select-option>
          </a-select>
          <a-date-picker v-model:value="summaryDate" value-format="YYYY-MM-DD" @change="load" />
          <a-button size="small" @click="load" :loading="loading">刷新</a-button>
        </a-space>
      </template>

      <a-table :data-source="records" row-key="id" :loading="loading" size="small"
        :pagination="{
          current: page,
          pageSize,
          total,
          showSizeChanger: true,
          onChange: (p: number, ps: number) => { page = p; pageSize = ps; load() }
        }"
        :locale="{ emptyText: '暂无台账记录' }">
        <a-table-column title="开始时间" :width="180">
          <template #default="{ record }">{{ formatTime(record.startTime) }}</template>
        </a-table-column>
        <a-table-column title="结束时间" :width="180">
          <template #default="{ record }">{{ formatTime(record.endTime) }}</template>
        </a-table-column>
        <a-table-column title="灌区" :width="150">
          <template #default="{ record }">
            {{ record.fieldName || `灌区#${record.fieldId}` }}
            <div style="font-size: 12px; color: #999">{{ record.cropVariety }}</div>
          </template>
        </a-table-column>
        <a-table-column title="类型" :width="90">
          <template #default="{ record }">
            <a-tag :color="kindColor(record.kind)">{{ kindText(record.kind) }}</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="执行方式" :width="100">
          <template #default="{ record }">
            <a-tag :color="record.executionMode === 'MANUAL' ? 'orange' : 'blue'">
              {{ modeText(record.executionMode) }}
            </a-tag>
          </template>
        </a-table-column>
        <a-table-column title="时长" :width="110">
          <template #default="{ record }">{{ durationMin(record) }}</template>
        </a-table-column>
        <a-table-column title="水量 m³" data-index="waterM3" :width="100" />
        <a-table-column title="肥液 L" :width="100">
          <template #default="{ record }">{{ record.fertilizerL || '—' }}</template>
        </a-table-column>
        <a-table-column title="注肥比" :width="90">
          <template #default="{ record }">
            {{ record.injectRatioPct != null ? `${record.injectRatioPct}%` : '—' }}
          </template>
        </a-table-column>
        <a-table-column title="停止原因" :width="180">
          <template #default="{ record }">
            <a-tooltip v-if="record.stopReason" :title="record.stopReason">{{ record.stopReason }}</a-tooltip>
            <span v-else>—</span>
          </template>
        </a-table-column>
        <a-table-column title="作业" :width="90">
          <template #default="{ record }">#{{ record.jobId ?? '—' }}</template>
        </a-table-column>
      </a-table>
    </a-card>
  </a-space>
</template>
