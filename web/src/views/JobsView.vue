<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getFields, getJobs } from '@/api'
import type { Field, Job, JobStatus, PageResult } from '@/types'
import { JOB_STATUS_COLOR, JOB_STATUS_TEXT } from '@/utils/labels'
import { formatDuration, formatTime, formatVolume } from '@/utils/format'

const router = useRouter()
const loading = ref(false)
const records = ref<Job[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const fieldIdFilter = ref<number | undefined>(undefined)
const statusFilter = ref<JobStatus | undefined>(undefined)
const fields = ref<Field[]>([])

async function load() {
  loading.value = true
  try {
    const res: PageResult<Job> | null = await getJobs({
      fieldId: fieldIdFilter.value,
      status: statusFilter.value,
      page: page.value,
      size: size.value
    }).catch(() => null)
    records.value = res?.records ?? []
    total.value = res?.total ?? 0
  } finally {
    loading.value = false
  }
}

function onPageChange(p: number, s: number) {
  page.value = p
  size.value = s
  load()
}

onMounted(async () => {
  fields.value = await getFields().catch(() => [])
  load()
})
</script>

<template>
  <a-card title="灌溉作业记录">
    <template #extra>
      <a-space>
        <a-select
          v-model:value="fieldIdFilter"
          allow-clear
          placeholder="按田块筛选"
          style="width: 180px"
          @change="() => { page = 1; load() }"
        >
          <a-select-option v-for="f in fields" :key="f.id" :value="f.id">{{ f.name }}</a-select-option>
        </a-select>
        <a-select
          v-model:value="statusFilter"
          allow-clear
          placeholder="按状态筛选"
          style="width: 140px"
          @change="() => { page = 1; load() }"
        >
          <a-select-option value="RUNNING">灌溉中</a-select-option>
          <a-select-option value="DONE">已完成</a-select-option>
          <a-select-option value="ABORTED">已中止</a-select-option>
          <a-select-option value="PLANNED">已计划</a-select-option>
        </a-select>
        <a-button @click="load" :loading="loading">查询</a-button>
      </a-space>
    </template>

    <a-table
      :data-source="records"
      :loading="loading"
      row-key="id"
      :pagination="{
        current: page,
        pageSize: size,
        total,
        showSizeChanger: true,
        showTotal: (t: number) => `共 ${t} 条`,
        onChange: onPageChange
      }"
      :locale="{ emptyText: '暂无作业记录（GET /api/jobs）' }"
    >
      <a-table-column title="ID" data-index="id" :width="80" />
      <a-table-column title="田块" :width="140">
        <template #default="{ record }">
          <a @click="router.push(`/fields/${record.fieldId}`)">
            {{ record.fieldName || `田块#${record.fieldId}` }}
          </a>
        </template>
      </a-table-column>
      <a-table-column title="触发方式" :width="100">
        <template #default="{ record }">
          <a-tag :color="record.triggerType === 'AUTO' ? 'blue' : record.triggerType === 'MANUAL' ? 'purple' : 'red'">
            {{ record.triggerType === 'AUTO' ? '自动' : record.triggerType === 'MANUAL' ? '手动' : '安全关断' }}
          </a-tag>
        </template>
      </a-table-column>
      <a-table-column title="状态" :width="100">
        <template #default="{ record }">
          <a-tag :color="JOB_STATUS_COLOR[record.status as JobStatus]">
            {{ JOB_STATUS_TEXT[record.status as JobStatus] }}
          </a-tag>
        </template>
      </a-table-column>
      <a-table-column title="开始 / 结束时间" :width="320">
        <template #default="{ record }">
          <div>{{ formatTime(record.startTime) }}</div>
          <div style="color: #999; font-size: 12px">{{ formatTime(record.endTime) }}</div>
        </template>
      </a-table-column>
      <a-table-column title="计划/实际水量" :width="180">
        <template #default="{ record }">
          {{ formatVolume(record.plannedM3) }} /
          <span :style="{ color: record.appliedM3 != null ? '#389e0d' : undefined }">
            {{ formatVolume(record.appliedM3) }}
          </span>
        </template>
      </a-table-column>
      <a-table-column title="时长" :width="120">
        <template #default="{ record }">{{ formatDuration(record.durationSec) }}</template>
      </a-table-column>

      <template #expandedRowRender="{ record }">
        <a-descriptions :column="2" size="small" bordered style="max-width: 900px">
          <a-descriptions-item label="停止原因 stopReason" :span="2">
            <a-tag v-if="record.stopReason" color="orange">{{ record.stopReason }}</a-tag>
            <span v-else style="color: #999">—</span>
          </a-descriptions-item>
          <a-descriptions-item label="原因备注" :span="2">{{ record.reason || '—' }}</a-descriptions-item>
          <template v-if="record.decision">
            <a-descriptions-item label="决策建议">
              <a-tag>{{ record.decision.decision }}</a-tag>
              生育期 {{ record.decision.stage || '—' }}
            </a-descriptions-item>
            <a-descriptions-item label="θ_start → θ_target">
              {{ record.decision.thetaStart }}% → {{ record.decision.thetaTarget }}%
            </a-descriptions-item>
            <a-descriptions-item label="亏缺 / 灌量 / 时长">
              {{ record.decision.deficitMm }} mm ｜ {{ formatVolume(record.decision.volumeM3) }}
              ｜ {{ formatDuration(record.decision.durationSec) }}
            </a-descriptions-item>
            <a-descriptions-item label="ET0 / ETc">
              {{ record.decision.et0MmDay ?? '—' }} / {{ record.decision.etcMmDay ?? '—' }} mm/d
            </a-descriptions-item>
            <a-descriptions-item label="决策依据 reasons" :span="2">
              <ul v-if="record.decision.reasons?.length" style="margin: 0; padding-left: 18px">
                <li v-for="(r, i) in record.decision.reasons" :key="i">{{ r }}</li>
              </ul>
              <span v-else style="color: #999">无</span>
            </a-descriptions-item>
            <a-descriptions-item v-if="record.decision.clampReason" label="限幅原因" :span="2">
              {{ record.decision.clampReason }}
            </a-descriptions-item>
          </template>
        </a-descriptions>
      </template>
    </a-table>
  </a-card>
</template>
