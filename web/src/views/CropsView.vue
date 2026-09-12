<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { getCropModels, updateCropModel } from '@/api'
import type { CropModel, CropStage } from '@/types'

const loading = ref(false)
const models = ref<CropModel[]>([])
const drawerOpen = ref(false)
const saving = ref(false)
const editing = ref<CropModel | null>(null)

async function load() {
  loading.value = true
  try {
    models.value = await getCropModels().catch(() => [])
  } finally {
    loading.value = false
  }
}

function edit(m: CropModel) {
  // 深拷贝，避免取消时污染表格
  editing.value = JSON.parse(JSON.stringify(m)) as CropModel
  drawerOpen.value = true
}

function addStage() {
  editing.value?.stages.push({
    name: 'new-stage',
    startDay: 0,
    endDay: 0,
    startKc: 0.6,
    endKc: 0.6,
    startP: 0.5,
    endP: 0.5,
    zrMm: 200
  })
}

function removeStage(i: number) {
  editing.value?.stages.splice(i, 1)
}

async function save() {
  if (!editing.value) return
  if (!editing.value.stages.length) {
    message.warning('至少保留一个生育期阶段')
    return
  }
  saving.value = true
  try {
    await updateCropModel(editing.value.code, editing.value)
    message.success(`作物模型 ${editing.value.name} 已保存`)
    drawerOpen.value = false
    await load()
  } finally {
    saving.value = false
  }
}

const stageColumns = [
  { title: '阶段名称', dataIndex: 'name', key: 'name', width: 120 },
  { title: '开始日 startDay', dataIndex: 'startDay', key: 'startDay', width: 100 },
  { title: '结束日 endDay', dataIndex: 'endDay', key: 'endDay', width: 100 },
  { title: '起始 Kc', dataIndex: 'startKc', key: 'startKc', width: 110 },
  { title: '结束 Kc', dataIndex: 'endKc', key: 'endKc', width: 110 },
  { title: '起始 p', dataIndex: 'startP', key: 'startP', width: 100 },
  { title: '结束 p', dataIndex: 'endP', key: 'endP', width: 100 },
  { title: '根深 zrMm', dataIndex: 'zrMm', key: 'zrMm', width: 110 },
  { title: '操作', key: 'op', width: 70 }
]

onMounted(load)
</script>

<template>
  <a-card title="作物模型（FAO-56 生育期参数）">
    <template #extra>
      <a-button @click="load" :loading="loading">刷新</a-button>
    </template>
    <a-table
      :data-source="models"
      :loading="loading"
      row-key="code"
      :pagination="false"
      :locale="{ emptyText: '暂无作物模型（GET /api/crop-models）' }"
    >
      <a-table-column title="作物编码" data-index="code" :width="160" />
      <a-table-column title="名称" data-index="name" :width="160" />
      <a-table-column title="生育期阶段">
        <template #default="{ record }">
          <a-space wrap>
            <a-tag v-for="(s, i) in (record.stages || [])" :key="i" color="blue">
              {{ s.name }}
              <span v-if="s.startDay != null || s.endDay != null">
                （{{ s.startDay ?? 0 }}–{{ s.endDay ?? s.days ?? '?' }}d）
              </span>
              <span v-else-if="s.days != null">（{{ s.days }}d）</span>
              Kc {{ s.startKc ?? s.kc }}<template v-if="s.endKc != null">→{{ s.endKc }}</template>
              ，p {{ s.startP ?? s.p }}
              ，zr {{ s.zrMm }}mm
            </a-tag>
          </a-space>
        </template>
      </a-table-column>
      <a-table-column title="操作" :width="100">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="edit(record)">编辑阶段</a-button>
        </template>
      </a-table-column>
    </a-table>

    <a-drawer
      v-model:open="drawerOpen"
      :title="editing ? `编辑作物模型：${editing.name}（${editing.code}）` : ''"
      width="1080"
      :mask-closable="false"
    >
      <template v-if="editing">
        <a-space style="margin-bottom: 12px">
          <span>作物名称</span>
          <a-input v-model:value="editing.name" style="width: 200px" />
          <a-button type="dashed" @click="addStage">+ 新增生育期行</a-button>
          <span style="color: #999; font-size: 12px">
            θ_start = θfc − p(θfc−θwp)，Kc/p 随生育期变化，zr 为根深（mm）
          </span>
        </a-space>
        <a-table
          :data-source="editing.stages"
          :columns="stageColumns"
          :pagination="false"
          row-key="name"
          size="small"
          bordered
        >
          <template #bodyCell="{ column, record, index }">
            <template v-if="column.key === 'name'">
              <a-input v-model:value="(record as CropStage).name" size="small" />
            </template>
            <template v-else-if="column.key === 'startDay'">
              <a-input-number v-model:value="(record as CropStage).startDay" :min="0" size="small" style="width: 100%" />
            </template>
            <template v-else-if="column.key === 'endDay'">
              <a-input-number v-model:value="(record as CropStage).endDay" :min="0" size="small" style="width: 100%" />
            </template>
            <template v-else-if="column.key === 'startKc'">
              <a-input-number v-model:value="(record as CropStage).startKc" :min="0" :step="0.05" size="small" style="width: 100%" />
            </template>
            <template v-else-if="column.key === 'endKc'">
              <a-input-number v-model:value="(record as CropStage).endKc" :min="0" :step="0.05" size="small" style="width: 100%" />
            </template>
            <template v-else-if="column.key === 'startP'">
              <a-input-number v-model:value="(record as CropStage).startP" :min="0" :max="1" :step="0.05" size="small" style="width: 100%" />
            </template>
            <template v-else-if="column.key === 'endP'">
              <a-input-number v-model:value="(record as CropStage).endP" :min="0" :max="1" :step="0.05" size="small" style="width: 100%" />
            </template>
            <template v-else-if="column.key === 'zrMm'">
              <a-input-number v-model:value="(record as CropStage).zrMm" :min="0" :step="10" addon-after="mm" size="small" style="width: 100%" />
            </template>
            <template v-else-if="column.key === 'op'">
              <a-popconfirm title="删除该阶段？" @confirm="removeStage(index)">
                <a-button type="link" danger size="small">删除</a-button>
              </a-popconfirm>
            </template>
          </template>
        </a-table>
      </template>
      <template #footer>
        <a-space style="float: right">
          <a-button @click="drawerOpen = false">取消</a-button>
          <a-button type="primary" :loading="saving" @click="save">保存（PUT /crop-models/{{ editing?.code }}）</a-button>
        </a-space>
      </template>
    </a-drawer>
  </a-card>
</template>
