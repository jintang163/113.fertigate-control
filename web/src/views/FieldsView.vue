<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { getFields, getFieldStatus, updateField } from '@/api'
import type { Field, FieldStatus } from '@/types'
import { formatPercent, formatTime, timeAgo } from '@/utils/format'
import { valveColor, valveText } from '@/utils/labels'

const router = useRouter()
const loading = ref(false)
const fields = ref<Field[]>([])
const statusMap = ref<Record<number, FieldStatus>>({})
let timer: ReturnType<typeof setInterval> | null = null

async function load() {
  loading.value = true
  try {
    fields.value = await getFields().catch(() => [])
    const results = await Promise.all(
      fields.value.map((f) => getFieldStatus(f.id).then((s) => [f.id, s] as const).catch(() => null))
    )
    const map: Record<number, FieldStatus> = {}
    results.forEach((r) => {
      if (r) map[r[0]] = r[1]
    })
    statusMap.value = map
  } finally {
    loading.value = false
  }
}

async function onModeChange(field: Field, mode: 'AUTO' | 'MANUAL') {
  const previous = field.config.mode
  field.config.mode = mode
  try {
    await updateField(field.id, { ...field, config: { ...field.config, mode } })
    message.success(`${field.name} 已切换为${mode === 'AUTO' ? '自动' : '手动'}模式`)
    await load()
  } catch {
    field.config.mode = previous
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
  <a-spin :spinning="loading">
    <a-empty
      v-if="!loading && !fields.length"
      description="暂无田块数据（后端未启动或尚未配置田块）"
      style="padding: 80px 0"
    >
      <a-button type="primary" @click="load">重新加载</a-button>
    </a-empty>

    <a-row :gutter="[16, 16]">
      <a-col v-for="f in fields" :key="f.id" :xs="24" :sm="12" :lg="8" :xl="6">
        <a-card hoverable>
          <template #title>
            <a-space>
              <span>{{ f.name }}</span>
              <a-tag color="blue">{{ f.cropCode }}</a-tag>
            </a-space>
          </template>
          <template #extra>
            <a-tag :color="valveColor(statusMap[f.id]?.valveState ?? 'CLOSED')">
              {{ valveText(statusMap[f.id]?.valveState ?? 'CLOSED') }}
            </a-tag>
          </template>
          <template #actions>
            <span key="mode" @click.stop>
              <a-space>
                <span style="font-size: 13px">AUTO</span>
                <a-switch
                  :checked="f.config.mode === 'AUTO'"
                  checked-children="自动"
                  un-checked-children="手动"
                  @change="(v: boolean) => onModeChange(f, v ? 'AUTO' : 'MANUAL')"
                />
              </a-space>
            </span>
            <a-button type="link" key="detail" @click="router.push(`/fields/${f.id}`)">详情 →</a-button>
          </template>

          <a-descriptions :column="1" size="small" colon>
            <a-descriptions-item label="灌溉方式">
              {{ f.irrigationMode === 'DRIP' ? '滴灌' : '喷灌' }} · {{ (f.areaM2 / 666.67).toFixed(1) }} 亩
            </a-descriptions-item>
            <a-descriptions-item label="当前湿度">
              <span style="font-size: 16px; font-weight: 600; color: #1677ff">
                {{ formatPercent(statusMap[f.id]?.moisture) }}
              </span>
              <a-tag
                v-if="statusMap[f.id]?.moisture != null && statusMap[f.id]?.thetaStart != null &&
                  (statusMap[f.id]?.moisture as number) <= (statusMap[f.id]?.thetaStart as number)"
                color="orange"
                style="margin-left: 8px"
              >
                低于启动阈值
              </a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="θ_start 阈值">
              {{ formatPercent(statusMap[f.id]?.thetaStart) }}
              <span style="color: #bbb">（θfc {{ formatPercent(statusMap[f.id]?.thetaFc ?? f.config.thetaFc) }}
                / θwp {{ formatPercent(statusMap[f.id]?.thetaWp ?? f.config.thetaWp) }}）</span>
            </a-descriptions-item>
            <a-descriptions-item label="当前作业">
              <a-tag v-if="statusMap[f.id]?.jobId" color="processing">作业 #{{ statusMap[f.id]?.jobId }} 进行中</a-tag>
              <span v-else style="color: #999">空闲</span>
            </a-descriptions-item>
            <a-descriptions-item label="最近灌溉">
              {{ timeAgo(statusMap[f.id]?.lastIrrigTime) }}
              <span style="color: #bbb; font-size: 12px">（{{ formatTime(statusMap[f.id]?.lastIrrigTime) }}）</span>
            </a-descriptions-item>
          </a-descriptions>
        </a-card>
      </a-col>
    </a-row>
  </a-spin>
</template>
