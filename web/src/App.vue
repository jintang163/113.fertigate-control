<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  DashboardOutlined,
  AppstoreOutlined,
  ExperimentOutlined,
  ApiOutlined,
  ScheduleOutlined,
  HistoryOutlined,
  FileTextOutlined,
  AlertOutlined
} from '@ant-design/icons-vue'
import { getGateways } from '@/api'
import type { Gateway } from '@/types'

const route = useRoute()
const router = useRouter()

const collapsed = ref(false)
const gateways = ref<Gateway[]>([])
const gwLoading = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const menuItems = [
  { key: '/dashboard', icon: () => h(DashboardOutlined), label: '总览仪表盘' },
  { key: '/fields', icon: () => h(AppstoreOutlined), label: '灌区管理' },
  { key: '/crops', icon: () => h(ExperimentOutlined), label: '作物模型' },
  { key: '/devices', icon: () => h(ApiOutlined), label: '设备管理' },
  { key: '/rotation', icon: () => h(ScheduleOutlined), label: '轮灌调度' },
  { key: '/jobs', icon: () => h(HistoryOutlined), label: '灌溉作业' },
  { key: '/ledger', icon: () => h(FileTextOutlined), label: '灌肥台账' },
  { key: '/alarms', icon: () => h(AlertOutlined), label: '告警中心' }
]

const selectedKeys = computed(() => {
  if (route.path.startsWith('/fields')) return ['/fields']
  return [route.path]
})

const onlineGw = computed(() => gateways.value.filter((g) => g.online).length)
const totalQueue = computed(() => gateways.value.reduce((s, g) => s + (g.queueDepth || 0), 0))

async function loadGateways() {
  gwLoading.value = true
  try {
    gateways.value = await getGateways()
  } catch {
    gateways.value = []
  } finally {
    gwLoading.value = false
  }
}

function onMenu({ key }: { key: string }) {
  router.push(key)
}

onMounted(() => {
  loadGateways()
  timer = setInterval(loadGateways, 30000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <a-layout style="min-height: 100vh">
    <a-layout-sider v-model:collapsed="collapsed" collapsible :style="{ overflow: 'auto' }" theme="dark">
      <div class="app-logo">
        <span class="logo-mark">🚜</span>
        <span v-if="!collapsed" class="logo-title">智慧灌溉控制</span>
      </div>
      <a-menu
        theme="dark"
        mode="inline"
        :selected-keys="selectedKeys"
        :items="menuItems"
        @click="onMenu"
      />
    </a-layout-sider>
    <a-layout>
      <a-header class="app-header">
        <div class="app-header-left">
          <span>{{ (route.meta.title as string) || '水肥一体化智能灌溉控制系统' }}</span>
        </div>
        <div class="app-header-right">
          <a-tooltip :title="gateways.length
            ? gateways.map((g) => `${g.sn}：${g.online ? '在线' : '离线'}，队列 ${g.queueDepth}`).join('；')
            : '暂无网关心跳，请确认后端（:8080）已启动'">
            <span class="gateway-status" :class="{ 'cursor-pointer': true }">
              <a-badge :status="onlineGw > 0 ? 'success' : 'error'" />
              <span>网关 {{ onlineGw }}/{{ gateways.length }} 在线</span>
              <a-tag v-if="totalQueue > 0" color="orange" style="margin-left: 4px">
                待发队列 {{ totalQueue }}
              </a-tag>
              <a-tag v-else-if="gateways.length > 0" color="green" style="margin-left: 4px">队列清空</a-tag>
              <a-tag v-else color="default" style="margin-left: 4px">{{ gwLoading ? '检测中' : '后端未连接' }}</a-tag>
            </span>
          </a-tooltip>
          <a-divider type="vertical" />
          <span style="font-size: 13px; color: #888">FAO-56 阈值灌溉</span>
        </div>
      </a-header>
      <a-content class="page-container">
        <router-view v-slot="{ Component }">
          <component :is="Component" />
        </router-view>
      </a-content>
      <a-footer style="text-align: center; color: #999; padding: 12px 50px">
        水肥一体化智能灌溉控制系统 · 边缘联锁 + 云端决策（FAO-56）
      </a-footer>
    </a-layout>
  </a-layout>
</template>
