<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  deleteDevice,
  getDevices,
  getGateways,
  pushGatewayConfig,
  registerDevice,
  sendDeviceCommand,
  setDeviceStatus,
  updateDevice
} from '@/api'
import type { Device, DevicePayload, Gateway, GatewayConfigPayload, GatewayInterlocks } from '@/types'
import { deviceTypeText } from '@/utils/labels'
import { timeAgo, formatTime } from '@/utils/format'

const loading = ref(false)
const devices = ref<Device[]>([])
const gateways = ref<Gateway[]>([])

const typeFilter = ref<string | undefined>(undefined)
const gwFilter = ref<string | undefined>(undefined)

// ---------- 注册/编辑弹窗 ----------
const formOpen = ref(false)
const editingCode = ref<string | null>(null)
const form = reactive<{
  code: string
  name: string
  type: string
  gatewaySn?: string
  modbusAddr?: number
  status?: string
  paramsJson: string
}>({
  code: '',
  name: '',
  type: 'SOIL_SENSOR',
  gatewaySn: 'GW001',
  modbusAddr: undefined,
  status: 'ENABLED',
  paramsJson: '{}'
})

const TYPE_OPTIONS = [
  { value: 'SOIL_SENSOR', label: '土壤传感器' },
  { value: 'WEATHER_STATION', label: '气象站' },
  { value: 'VALVE', label: '电磁阀' },
  { value: 'FLOW_METER', label: '流量计' },
  { value: 'PRESSURE_SENSOR', label: '压力变送器' },
  { value: 'FERT_PUMP', label: '施肥泵' }
]

function openCreate() {
  editingCode.value = null
  Object.assign(form, {
    code: '', name: '', type: 'SOIL_SENSOR', gatewaySn: 'GW001',
    modbusAddr: undefined, status: 'ENABLED', paramsJson: '{}'
  })
  formOpen.value = true
}

function openEdit(d: Device) {
  editingCode.value = d.code
  Object.assign(form, {
    code: d.code, name: d.name ?? '', type: d.type, gatewaySn: d.gatewaySn,
    modbusAddr: d.modbusAddr, status: d.status === 'DISABLED' ? 'DISABLED' : 'ENABLED',
    paramsJson: JSON.stringify(d.params ?? {}, null, 2)
  })
  formOpen.value = true
}

async function saveDevice() {
  if (!form.code) {
    message.warning('请填写设备编码')
    return
  }
  let params: Record<string, unknown> = {}
  try {
    params = form.paramsJson.trim() ? JSON.parse(form.paramsJson) : {}
  } catch {
    message.error('参数 JSON 格式错误')
    return
  }
  const payload: DevicePayload = {
    code: form.code,
    name: form.name,
    type: form.type,
    gatewaySn: form.gatewaySn,
    modbusAddr: form.modbusAddr,
    status: form.status,
    params
  }
  try {
    if (editingCode.value) {
      await updateDevice(editingCode.value, payload)
      message.success('设备已更新')
    } else {
      await registerDevice(payload)
      message.success(`设备 ${form.code} 已注册`)
    }
    formOpen.value = false
    await load()
  } catch {
    /* http 拦截器已提示 */
  }
}

async function toggleEnable(d: Device) {
  const next = d.status === 'DISABLED' ? 'ENABLED' : 'DISABLED'
  await setDeviceStatus(d.code, next)
  message.success(`${d.code} 已${next === 'DISABLED' ? '停用' : '投运'}`)
  await load()
}

async function removeDevice(d: Device) {
  Modal.confirm({
    title: `删除设备 ${d.code}？`,
    content: '删除后该设备的注册信息将被移除（历史遥测与作业不受影响）。',
    okType: 'danger',
    onOk: async () => {
      await deleteDevice(d.code)
      message.success('已删除')
      await load()
    }
  })
}

// ---------- 施肥泵/执行器手动控制 ----------
async function pumpCommand(d: Device, action: 'START' | 'STOP', opening?: number) {
  const r = await sendDeviceCommand(d.code, { action, opening })
  if (r.accepted) {
    message.success(`${deviceTypeText(d.type)} ${d.code} ${action === 'START' ? '启动' : '停止'}指令已下发`)
  } else {
    message.warning(r.message || '指令未被接受')
  }
  await load()
}

// ---------- 网关配置弹窗 ----------
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
    phHigh: 8.0,
    pressureMinKpa: 80,
    flowMinM3h: 0.5,
    waterLostDelaySec: 15,
    pumpOverloadA: 8,
    commLostSec: 300
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
    phHigh: g.interlocks?.phHigh ?? 8.0,
    pressureMinKpa: g.interlocks?.pressureMinKpa ?? 80,
    flowMinM3h: g.interlocks?.flowMinM3h ?? 0.5,
    waterLostDelaySec: g.interlocks?.waterLostDelaySec ?? 15,
    pumpOverloadA: g.interlocks?.pumpOverloadA ?? 8,
    commLostSec: g.interlocks?.commLostSec ?? 300
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

function isActuator(t: string) {
  return t === 'FERT_PUMP' || t === 'VALVE'
}

const STATUS_TEXT: Record<string, string> = {
  ENABLED: '投运',
  DISABLED: '停用',
  FAULT: '故障',
  UNKNOWN: '未知'
}
function statusText(s?: string) {
  return STATUS_TEXT[s || 'UNKNOWN'] ?? s ?? '未知'
}
function statusColor(s?: string) {
  return s === 'ENABLED' ? 'green' : s === 'FAULT' ? 'red' : 'default'
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
      <a-table :data-source="gateways" :loading="loading" row-key="sn" :pagination="false" size="small"
        :locale="{ emptyText: '暂无网关心跳（GET /api/gateways）' }">
        <a-table-column title="网关 SN" data-index="sn" :width="180" />
        <a-table-column title="在线状态" :width="120">
          <template #default="{ record }">
            <a-badge :status="record.online ? 'success' : 'error'" :text="record.online ? '在线' : '离线'" />
          </template>
        </a-table-column>
        <a-table-column title="待发队列" :width="120">
          <template #default="{ record }">
            <a-tag :color="record.queueDepth > 0 ? 'orange' : 'green'">{{ record.queueDepth ?? 0 }}</a-tag>
          </template>
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
    <a-card title="设备列表（传感器 / 电磁阀 / 施肥泵）" size="small">
      <template #extra>
        <a-space>
          <a-select v-model:value="gwFilter" allow-clear placeholder="按网关" style="width: 140px" @change="load">
            <a-select-option v-for="g in gateways" :key="g.sn" :value="g.sn">{{ g.sn }}</a-select-option>
          </a-select>
          <a-select v-model:value="typeFilter" allow-clear placeholder="按类型" style="width: 150px" @change="load">
            <a-select-option v-for="t in TYPE_OPTIONS" :key="t.value" :value="t.value">{{ t.label }}</a-select-option>
          </a-select>
          <a-button size="small" @click="load" :loading="loading">查询</a-button>
          <a-button type="primary" size="small" @click="openCreate">注册设备</a-button>
        </a-space>
      </template>
      <a-table :data-source="devices" :loading="loading" row-key="code"
        :pagination="{ pageSize: 12, showSizeChanger: true }" size="small"
        :locale="{ emptyText: '暂无设备' }">
        <a-table-column title="编码" data-index="code" :width="130" />
        <a-table-column title="名称" :width="150">
          <template #default="{ record }">{{ record.name || '—' }}</template>
        </a-table-column>
        <a-table-column title="类型" :width="110">
          <template #default="{ record }">
            <a-tag color="geekblue">{{ deviceTypeText(record.type) }}</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="网关/地址" :width="130">
          <template #default="{ record }">{{ record.gatewaySn }}#{{ record.modbusAddr ?? '—' }}</template>
        </a-table-column>
        <a-table-column title="通信" :width="90">
          <template #default="{ record }">
            <a-badge :status="record.online ? 'success' : 'error'" :text="record.online ? '在线' : '离线'" />
          </template>
        </a-table-column>
        <a-table-column title="投运" :width="90">
          <template #default="{ record }">
            <a-tag :color="statusColor(record.status)">{{ statusText(record.status) }}</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="开度" :width="80">
          <template #default="{ record }">
            <a-tag v-if="isActuator(record.type) && record.opening != null" color="purple">{{ record.opening }}%</a-tag>
            <span v-else>—</span>
          </template>
        </a-table-column>
        <a-table-column title="心跳" :width="160">
          <template #default="{ record }">{{ timeAgo(record.lastHeartbeat) }}</template>
        </a-table-column>
        <a-table-column title="操作" :width="280">
          <template #default="{ record }">
            <a-space :size="4">
              <a-button v-if="record.type === 'FERT_PUMP'" size="small" type="primary" ghost
                :disabled="!record.online" @click="pumpCommand(record, 'START', 100)">启泵</a-button>
              <a-button v-if="record.type === 'FERT_PUMP'" size="small" danger ghost
                @click="pumpCommand(record, 'STOP')">停泵</a-button>
              <a-button size="small" @click="openEdit(record)">编辑</a-button>
              <a-button size="small" @click="toggleEnable(record)">
                {{ record.status === 'DISABLED' ? '投运' : '停用' }}
              </a-button>
              <a-popconfirm title="确认删除该设备？" @confirm="removeDevice(record)">
                <a-button size="small" type="link" danger>删除</a-button>
              </a-popconfirm>
            </a-space>
          </template>
        </a-table-column>
      </a-table>
    </a-card>

    <!-- 注册/编辑弹窗 -->
    <a-modal v-model:open="formOpen" :title="editingCode ? `编辑设备 ${editingCode}` : '注册新设备'"
      ok-text="保存" cancel-text="取消" @ok="saveDevice">
      <a-form layout="vertical">
        <a-row :gutter="12">
          <a-col :span="12">
            <a-form-item label="设备编码" required>
              <a-input v-model:value="form.code" placeholder="如 SOIL-B2 / FP-02" :disabled="!!editingCode" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="设备名称">
              <a-input v-model:value="form.name" placeholder="如 2号棚土壤湿度1" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="设备类型">
              <a-select v-model:value="form.type" :options="TYPE_OPTIONS" :disabled="!!editingCode" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="所属网关">
              <a-input v-model:value="form.gatewaySn" placeholder="GW001" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="Modbus 地址">
              <a-input-number v-model:value="form.modbusAddr" :min="0" style="width: 100%" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="投运状态">
              <a-select v-model:value="form.status" style="width: 100%">
                <a-select-option value="ENABLED">投运</a-select-option>
                <a-select-option value="DISABLED">停用</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>
        <a-alert v-if="form.type === 'FERT_PUMP'" type="info" show-icon style="margin-bottom: 8px"
          message="施肥泵参数：请在 params JSON 中提供 capacityLph（额定流量 L/h），系统据此折算肥液量与开度。" />
        <a-form-item label="设备参数 params（JSON）">
          <a-textarea v-model:value="form.paramsJson" :auto-size="{ minRows: 2, maxRows: 6 }" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 网关配置弹窗 -->
    <a-modal v-model:open="modalOpen" :title="`下发网关配置：${editingSn}`" :confirm-loading="saving"
      ok-text="下发" cancel-text="取消" width="680px" @ok="saveConfig">
      <a-alert type="info" show-icon style="margin-bottom: 16px"
        message="配置经云端转 MQTT config 下发；联锁阈值在任何模式下均本地生效（最高优先级），通信中断超限时网关自动紧急关阀停泵。" />
      <a-form layout="vertical">
        <a-form-item label="轮询周期（秒）">
          <a-input-number v-model:value="configForm.pollIntervalSec" :min="1" addon-after="秒" style="width: 100%" />
        </a-form-item>
        <a-divider orientation="left" plain style="font-size: 13px">边缘硬联锁 interlocks</a-divider>
        <a-row :gutter="12">
          <a-col :span="8"><a-form-item label="湿度硬上限 %"><a-input-number v-model:value="configForm.interlocks.moistureHardMax" :min="0" :max="100" :step="0.5" style="width:100%" /></a-form-item></a-col>
          <a-col :span="8"><a-form-item label="传感器失联 s"><a-input-number v-model:value="configForm.interlocks.sensorLostSec" :min="10" :step="10" style="width:100%" /></a-form-item></a-col>
          <a-col :span="8"><a-form-item label="EC 高限 mS/cm"><a-input-number v-model:value="configForm.interlocks.ecHigh" :min="0" :step="0.1" style="width:100%" /></a-form-item></a-col>
          <a-col :span="8"><a-form-item label="pH 低限"><a-input-number v-model:value="configForm.interlocks.phLow" :min="0" :max="14" :step="0.1" style="width:100%" /></a-form-item></a-col>
          <a-col :span="8"><a-form-item label="pH 高限"><a-input-number v-model:value="configForm.interlocks.phHigh" :min="0" :max="14" :step="0.1" style="width:100%" /></a-form-item></a-col>
          <a-col :span="8"><a-form-item label="水压下限 kPa"><a-input-number v-model:value="configForm.interlocks.pressureMinKpa" :min="0" :step="5" style="width:100%" /></a-form-item></a-col>
          <a-col :span="8"><a-form-item label="阀开最低流量 m³/h"><a-input-number v-model:value="configForm.interlocks.flowMinM3h" :min="0" :step="0.1" style="width:100%" /></a-form-item></a-col>
          <a-col :span="8"><a-form-item label="缺水判定延时 s"><a-input-number v-model:value="configForm.interlocks.waterLostDelaySec" :min="1" :step="1" style="width:100%" /></a-form-item></a-col>
          <a-col :span="8"><a-form-item label="泵过载电流 A"><a-input-number v-model:value="configForm.interlocks.pumpOverloadA" :min="0" :step="0.5" style="width:100%" /></a-form-item></a-col>
          <a-col :span="8"><a-form-item label="通信中断紧急停车 s"><a-input-number v-model:value="configForm.interlocks.commLostSec" :min="30" :step="10" style="width:100%" /></a-form-item></a-col>
        </a-row>
      </a-form>
    </a-modal>
  </a-space>
</template>
