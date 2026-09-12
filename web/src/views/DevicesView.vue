<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { getDevices, getGateways, pushGatewayConfig } from '@/api'
import type { Device, Gateway, GatewayConfigPayload, GatewayInterlocks } from '@/types'
import { deviceTypeText } from '@/utils/labels'
import { timeAgo, formatTime } from '@/utils/format'

const loading = ref(false)
const devices = ref<Device[]>([])
const gateways = ref<Gateway[]>([])

const typeFilter = ref<string | undefined>(undefined)
const gwFilter = ref<string | undefined>(undefined)

// 网关配置弹窗
const modalOpen = ref(false)
const saving = ref(false)
const editingSn = ref('')
const configForm = reactive<GatewayConfigPayload>({
  pollIntervalSec: 10,
  interlocks: {
    moistureHardMax: 33,
    sensorLostSec: 180,
    valveFaultSec: 60,
    ecHigh: 3.0,
    phLow: 5.0,
    phHigh: 8.0
  }
})

async function load() {
  loading.value = true
  try {
    const [d, g] = await Promise.all([
      getDevices({ type: typeFilter.value, gatewaySn: gwFilter.value }).catch(() => [] as Device[]),
      getGateways().catch(() => [] as Gateway[])
    ])
    devices.value = d
    gateways.value = g
  } finally {
    loading.value = false
  }
}

function openConfig(g: Gateway) {
  editingSn.value = g.sn
  configForm.pollIntervalSec = g.pollIntervalSec ?? 10
  configForm.interlocks = reactive<GatewayInterlocks>({
    moistureHardMax: g.interlocks?.moistureHardMax ?? 33,
    sensorLostSec: g.interlocks?.sensorLostSec ?? 180,
    valveFaultSec: g.interlocks?.valveFaultSec ?? 60,
    ecHigh: g.interlocks?.ecHigh ?? 3.0,
    phLow: g.interlocks?.phLow ?? 5.0,
    phHigh: g.interlocks?.phHigh ?? 8.0
  })
  modalOpen.value = true
}

async function saveConfig() {
  saving.value = true
  try {
    await pushGatewayConfig(editingSn.value, JSON.parse(JSON.stringify(configForm)))
    message.success(`网关 ${editingSn.value} 配置已下发（MQTT config）`)
    modalOpen.value = false
    await load()
  } finally {
    saving.value = false
  }
}

function latestText(d: Device): string {
  if (!d.latest) return '—'
  return Object.entries(d.latest)
    .map(([k, v]) => `${k}=${v}`)
    .join('，')
}

onMounted(load)
</script>

<template>
  <a-space direction="vertical" :size="16" style="width: 100%">
    <!-- 网关区 -->
    <a-card title="边缘网关" size="small">
      <template #extra>
        <a-button size="small" @click="load" :loading="loading">刷新</a-button>
      </template>
      <a-table
        :data-source="gateways"
        :loading="loading"
        row-key="sn"
        :pagination="false"
        size="small"
        :locale="{ emptyText: '暂无网关心跳（GET /api/gateways）' }"
      >
        <a-table-column title="网关 SN" data-index="sn" :width="180" />
        <a-table-column title="名称" data-index="name" :width="160">
          <template #default="{ record }">{{ record.name || '—' }}</template>
        </a-table-column>
        <a-table-column title="在线状态" :width="120">
          <template #default="{ record }">
            <a-badge :status="record.online ? 'success' : 'error'" :text="record.online ? '在线' : '离线'" />
          </template>
        </a-table-column>
        <a-table-column title="待发队列 queueDepth" :width="160">
          <template #default="{ record }">
            <a-tag :color="record.queueDepth > 0 ? 'orange' : 'green'">{{ record.queueDepth ?? 0 }}</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="轮询周期" :width="120">
          <template #default="{ record }">{{ record.pollIntervalSec ?? 10 }} 秒</template>
        </a-table-column>
        <a-table-column title="最后心跳">
          <template #default="{ record }">{{ timeAgo(record.lastHeartbeat) }}（{{ formatTime(record.lastHeartbeat) }}）</template>
        </a-table-column>
        <a-table-column title="操作" :width="140">
          <template #default="{ record }">
            <a-button type="primary" size="small" ghost :disabled="!record.online" @click="openConfig(record)">
              下发配置
            </a-button>
          </template>
        </a-table-column>
      </a-table>
    </a-card>

    <!-- 设备区 -->
    <a-card title="设备列表" size="small">
      <template #extra>
        <a-space>
          <a-select
            v-model:value="gwFilter"
            allow-clear
            placeholder="按网关过滤"
            style="width: 200px"
            @change="load"
          >
            <a-select-option v-for="g in gateways" :key="g.sn" :value="g.sn">{{ g.sn }}</a-select-option>
          </a-select>
          <a-select
            v-model:value="typeFilter"
            allow-clear
            placeholder="按类型过滤"
            style="width: 180px"
            @change="load"
          >
            <a-select-option value="SOIL_SENSOR">土壤传感器</a-select-option>
            <a-select-option value="WEATHER_STATION">气象站</a-select-option>
            <a-select-option value="VALVE">电磁阀</a-select-option>
            <a-select-option value="FLOW_METER">流量计</a-select-option>
          </a-select>
          <a-button size="small" @click="load" :loading="loading">查询</a-button>
        </a-space>
      </template>
      <a-table
        :data-source="devices"
        :loading="loading"
        row-key="code"
        :pagination="{ pageSize: 12, showSizeChanger: true }"
        size="small"
        :locale="{ emptyText: '暂无设备（GET /api/devices）' }"
      >
        <a-table-column title="设备编码 code" data-index="code" :width="180" />
        <a-table-column title="类型" :width="130">
          <template #default="{ record }">
            <a-tag color="geekblue">{{ deviceTypeText(record.type) }}</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="所属网关" data-index="gatewaySn" :width="180" />
        <a-table-column title="Modbus 地址" :width="120">
          <template #default="{ record }">{{ record.modbusAddr ?? '—' }}</template>
        </a-table-column>
        <a-table-column title="在线" :width="90">
          <template #default="{ record }">
            <a-badge :status="record.online ? 'success' : 'error'" :text="record.online ? '在线' : '离线'" />
          </template>
        </a-table-column>
        <a-table-column title="最后心跳" :width="200">
          <template #default="{ record }">{{ timeAgo(record.lastHeartbeat) }}</template>
        </a-table-column>
        <a-table-column title="最新数据">
          <template #default="{ record }">{{ latestText(record) }}</template>
        </a-table-column>
      </a-table>
    </a-card>

    <!-- 网关配置弹窗 -->
    <a-modal
      v-model:open="modalOpen"
      :title="`下发网关配置：${editingSn}`"
      :confirm-loading="saving"
      ok-text="下发（POST /gateways/{sn}/config）"
      cancel-text="取消"
      width="640px"
      @ok="saveConfig"
    >
      <a-alert
        type="info"
        show-icon
        style="margin-bottom: 16px"
        message="配置经云端转 MQTT config 下发到边缘网关；联锁阈值在任何模式下均本地生效（最高优先级）。"
      />
      <a-form layout="vertical">
        <a-form-item label="轮询周期 pollIntervalSec（秒）">
          <a-input-number v-model:value="configForm.pollIntervalSec" :min="1" :step="1" addon-after="秒" style="width: 100%" />
        </a-form-item>
        <a-divider orientation="left" plain style="font-size: 13px">边缘硬联锁 interlocks</a-divider>
        <a-row :gutter="12">
          <a-col :span="12">
            <a-form-item label="湿度硬上限 moistureHardMax（%）">
              <a-input-number v-model:value="configForm.interlocks.moistureHardMax" :min="0" :max="100" :step="0.5" addon-after="%" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="传感器失联超时 sensorLostSec（秒）">
              <a-input-number v-model:value="configForm.interlocks.sensorLostSec" :min="10" :step="10" addon-after="秒" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="阀位不一致超时 valveFaultSec（秒）">
              <a-input-number v-model:value="configForm.interlocks.valveFaultSec" :min="10" :step="10" addon-after="秒" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="EC 高限 ecHigh（mS/cm）">
              <a-input-number v-model:value="configForm.interlocks.ecHigh" :min="0" :step="0.1" addon-after="mS/cm" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="pH 低限 phLow">
              <a-input-number v-model:value="configForm.interlocks.phLow" :min="0" :max="14" :step="0.1" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="pH 高限 phHigh">
              <a-input-number v-model:value="configForm.interlocks.phHigh" :min="0" :max="14" :step="0.1" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>
      </a-form>
    </a-modal>
  </a-space>
</template>
