<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import {
  cancelRotationPlan,
  generateRotationPlan,
  getFields,
  getRotationPlan,
  getRotationPlans,
  runCycle
} from '@/api'
import type { Field, RotationPlan, RotationPlanItem } from '@/types'
import {
  ROTATION_ITEM_STATUS_COLOR,
  ROTATION_ITEM_STATUS_TEXT,
  ROTATION_PLAN_STATUS_COLOR,
  ROTATION_PLAN_STATUS_TEXT
} from '@/utils/labels'
import { formatTime } from '@/utils/format'

const loading = ref(false)
const plans = ref<RotationPlan[]>([])
const fields = ref<Field[]>([])
const detail = ref<RotationPlan | null>(null)

const generating = ref(false)
const genForm = ref<{
  name: string
  startHour: number
  fieldIds: number[]
}>({
  name: '',
  startHour: dayjs().hour(),
  fieldIds: []
})

async function load() {
  loading.value = true
  try {
    const [p, f] = await Promise.all([
      getRotationPlans().catch(() => [] as RotationPlan[]),
      getFields().catch(() => [] as Field[])
    ])
    plans.value = p
    fields.value = f
    if (genForm.value.fieldIds.length === 0) {
      genForm.value.fieldIds = f.map((x) => x.id)
    }
  } finally {
    loading.value = false
  }
}

async function generate() {
  generating.value = true
  try {
    const plan = await generateRotationPlan({
      name: genForm.value.name || undefined,
      startHour: genForm.value.startHour,
      fieldIds: genForm.value.fieldIds,
      generatedBy: 'MANUAL'
    })
    message.success(`轮灌计划 #${plan.id} 已生成（${plan.items?.length ?? 0} 个灌区）`)
    detail.value = plan
    await load()
    const fresh = plans.value.find((x) => x.id === plan.id)
    if (fresh) detail.value = fresh
  } finally {
    generating.value = false
  }
}

async function refreshDetail(p: RotationPlan) {
  detail.value = await getRotationPlan(p.id)
}

async function cancel(p: RotationPlan) {
  await cancelRotationPlan(p.id)
  message.success(`计划 #${p.id} 已取消`)
  await load()
}

async function triggerCycle() {
  await runCycle()
  message.success('已触发一轮调度评估（到期计划项将释放）')
  await load()
}

function fieldName(id: number) {
  return fields.value.find((f) => f.id === id)?.name ?? `灌区#${id}`
}

function itemStatus(s: RotationPlanItem['status']) {
  return ROTATION_ITEM_STATUS_TEXT[s] ?? s
}
function itemColor(s: RotationPlanItem['status']) {
  return ROTATION_ITEM_STATUS_COLOR[s] ?? 'default'
}
function planStatus(s: RotationPlan['status']) {
  return ROTATION_PLAN_STATUS_TEXT[s] ?? s
}
function planColor(s: RotationPlan['status']) {
  return ROTATION_PLAN_STATUS_COLOR[s] ?? 'default'
}

onMounted(load)
</script>

<template>
  <a-space direction="vertical" :size="16" style="width: 100%">
    <a-card title="分区轮灌调度" size="small">
      <template #extra>
        <a-space>
          <a-button size="small" @click="triggerCycle">立即调度一次</a-button>
          <a-button size="small" type="primary" ghost @click="load" :loading="loading">刷新</a-button>
        </a-space>
      </template>
      <a-alert type="info" show-icon style="margin-bottom: 12px"
        message="系统按灌区优先级（数字小者优先）与墒情（越旱越优先）排序，结合各灌区灌溉时窗顺序排定开灌时间；同一主管道同一时间只灌一个灌区，作业完成自动切换下一个。" />
      <a-form layout="inline">
        <a-form-item label="计划名称">
          <a-input v-model:value="genForm.name" placeholder="留空自动命名" style="width: 180px" />
        </a-form-item>
        <a-form-item label="首灌开始小时">
          <a-input-number v-model:value="genForm.startHour" :min="0" :max="23" addon-after="时" style="width: 110px" />
        </a-form-item>
        <a-form-item label="纳入灌区">
          <a-select v-model:value="genForm.fieldIds" mode="multiple" style="min-width: 320px" placeholder="选择灌区">
            <a-select-option v-for="f in fields" :key="f.id" :value="f.id">
              {{ f.name }}（优先级 {{ f.priority ?? 100 }}）
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item>
          <a-button type="primary" :loading="generating" @click="generate">生成轮灌计划</a-button>
        </a-form-item>
      </a-form>
    </a-card>

    <a-card title="轮灌计划列表" size="small">
      <a-table :data-source="plans" row-key="id" :loading="loading" size="small"
        :pagination="{ pageSize: 10 }" :locale="{ emptyText: '暂无轮灌计划' }">
        <a-table-column title="#" data-index="id" :width="70" />
        <a-table-column title="名称" data-index="name" :width="200" />
        <a-table-column title="日期" data-index="planDate" :width="120" />
        <a-table-column title="状态" :width="100">
          <template #default="{ record }">
            <a-tag :color="planColor(record.status)">{{ planStatus(record.status) }}</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="计划/实灌 (m³)" :width="160">
          <template #default="{ record }">
            {{ record.totalPlannedM3 ?? 0 }} / {{ record.totalAppliedM3 ?? 0 }}
          </template>
        </a-table-column>
        <a-table-column title="生成方式" :width="100">
          <template #default="{ record }">{{ record.generatedBy === 'AUTO' ? '自动' : '人工' }}</template>
        </a-table-column>
        <a-table-column title="操作">
          <template #default="{ record }">
            <a-space>
              <a-button size="small" @click="refreshDetail(record)">查看排程</a-button>
              <a-button v-if="['SCHEDULED', 'RUNNING'].includes(record.status)" size="small" danger ghost
                @click="cancel(record)">取消</a-button>
            </a-space>
          </template>
        </a-table-column>
      </a-table>
    </a-card>

    <a-card v-if="detail" :title="`计划 #${detail.id} 排程明细：${detail.name}`" size="small">
      <a-table :data-source="detail.items ?? []" row-key="id" size="small" :pagination="false">
        <a-table-column title="序" data-index="seq" :width="60" />
        <a-table-column title="灌区" :width="180">
          <template #default="{ record }">{{ fieldName(record.fieldId) }}</template>
        </a-table-column>
        <a-table-column title="优先级" data-index="priority" :width="90" />
        <a-table-column title="计划开灌时间" :width="200">
          <template #default="{ record }">{{ formatTime(record.scheduledStart) }}</template>
        </a-table-column>
        <a-table-column title="计划水量 m³" data-index="plannedVolumeM3" :width="120" />
        <a-table-column title="状态" :width="100">
          <template #default="{ record }">
            <a-tag :color="itemColor(record.status)">{{ itemStatus(record.status) }}</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="作业/原因">
          <template #default="{ record }">
            <span v-if="record.jobId">作业 #{{ record.jobId }}</span>
            <span v-else-if="record.skipReason" style="color: #cf1322">{{ record.skipReason }}</span>
            <span v-else>—</span>
          </template>
        </a-table-column>
      </a-table>
    </a-card>
  </a-space>
</template>
