<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { ackAlarm, ackAlarms, getAlarms } from '@/api'
import type { Alarm, AlarmLevel, PageResult } from '@/types'
import { ALARM_LEVEL_COLOR, ALARM_LEVEL_TEXT } from '@/utils/labels'
import { formatTime } from '@/utils/format'

const loading = ref(false)
const records = ref<Alarm[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const levelFilter = ref<AlarmLevel | undefined>(undefined)
const ackFilter = ref<boolean | undefined>(undefined)
const selectedRowKeys = ref<number[]>([])
const batchLoading = ref(false)

async function load() {
  loading.value = true
  try {
    const res: PageResult<Alarm> | null = await getAlarms({
      level: levelFilter.value,
      ack: ackFilter.value,
      page: page.value,
      size: size.value
    }).catch(() => null)
    records.value = res?.records ?? []
    total.value = res?.total ?? 0
  } finally {
    loading.value = false
  }
}

async function onAck(row: Alarm) {
  try {
    await ackAlarm(row.id)
    message.success(`告警 #${row.id} 已确认`)
    await load()
  } catch {
    /* http 拦截器已提示 */
  }
}

async function onBatchAck() {
  const ids = selectedRowKeys.value
  if (!ids.length) return
  batchLoading.value = true
  try {
    await ackAlarms(ids)
    message.success(`已确认 ${ids.length} 条告警`)
    selectedRowKeys.value = []
    await load()
  } finally {
    batchLoading.value = false
  }
}

function onPageChange(p: number, s: number) {
  page.value = p
  size.value = s
  load()
}

const rowSelection = computed(() => ({
  selectedRowKeys: selectedRowKeys.value,
  onChange: (keys: (number | string)[]) => {
    selectedRowKeys.value = keys.map(Number)
  },
  getCheckboxProps: (r: Alarm) => ({ disabled: r.acknowledged })
}))

onMounted(load)
</script>

<template>
  <a-card title="告警中心">
    <template #extra>
      <a-space>
        <a-select
          v-model:value="levelFilter"
          allow-clear
          placeholder="按级别筛选"
          style="width: 140px"
          @change="() => { page = 1; load() }"
        >
          <a-select-option value="CRITICAL">严重 CRITICAL</a-select-option>
          <a-select-option value="WARN">警告 WARN</a-select-option>
          <a-select-option value="INFO">信息 INFO</a-select-option>
        </a-select>
        <a-select
          v-model:value="ackFilter"
          allow-clear
          placeholder="确认状态"
          style="width: 140px"
          @change="() => { page = 1; load() }"
        >
          <a-select-option :value="false">未确认</a-select-option>
          <a-select-option :value="true">已确认</a-select-option>
        </a-select>
        <a-button :loading="batchLoading" :disabled="!selectedRowKeys.length" @click="onBatchAck">
          批量确认（{{ selectedRowKeys.length }}）
        </a-button>
        <a-button @click="load" :loading="loading">刷新</a-button>
      </a-space>
    </template>

    <a-table
      :data-source="records"
      :loading="loading"
      row-key="id"
      :row-selection="rowSelection"
      :row-class-name="(r: Alarm) => (r.acknowledged ? '' : 'alarm-unacked')"
      :pagination="{
        current: page,
        pageSize: size,
        total,
        showSizeChanger: true,
        showTotal: (t: number) => `共 ${t} 条`,
        onChange: onPageChange
      }"
      :locale="{ emptyText: '暂无告警（GET /api/alarms）' }"
    >
      <a-table-column title="级别" :width="110">
        <template #default="{ record }">
          <a-tag :color="ALARM_LEVEL_COLOR[record.level as AlarmLevel]">
            {{ ALARM_LEVEL_TEXT[record.level as AlarmLevel] }}
          </a-tag>
        </template>
      </a-table-column>
      <a-table-column title="类型" data-index="type" :width="260" />
      <a-table-column title="消息" data-index="message">
        <template #default="{ record }">
          <span :style="{ fontWeight: record.acknowledged ? 400 : 600 }">{{ record.message }}</span>
        </template>
      </a-table-column>
      <a-table-column title="关联对象" :width="160">
        <template #default="{ record }">{{ record.fieldName || record.deviceCode || (record.fieldId != null ? `田块#${record.fieldId}` : '—') }}</template>
      </a-table-column>
      <a-table-column title="时间" :width="180">
        <template #default="{ record }">{{ formatTime(record.time) }}</template>
      </a-table-column>
      <a-table-column title="状态 / 操作" :width="150">
        <template #default="{ record }">
          <a-tag v-if="record.acknowledged" color="default">已确认</a-tag>
          <a-button v-else type="link" size="small" @click="onAck(record)">确认（ack）</a-button>
        </template>
      </a-table-column>
    </a-table>
  </a-card>
</template>

<style>
.alarm-unacked td {
  background: #fff7e6 !important;
}
.alarm-unacked:hover td {
  background: #fff1d6 !important;
}
</style>
