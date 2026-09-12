import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getDevices, getGateways } from '@/api'
import type { Device, Gateway } from '@/types'

export const useDevicesStore = defineStore('devices', () => {
  const devices = ref<Device[]>([])
  const gateways = ref<Gateway[]>([])
  const loading = ref(false)

  async function load() {
    loading.value = true
    try {
      const [d, g] = await Promise.all([getDevices().catch(() => [] as Device[]), getGateways().catch(() => [] as Gateway[])])
      devices.value = d
      gateways.value = g
    } finally {
      loading.value = false
    }
  }

  const onlineCount = () => devices.value.filter((d) => d.online).length

  return { devices, gateways, loading, load, onlineCount }
})
