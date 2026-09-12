import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getFields } from '@/api'
import type { Field } from '@/types'

export const useFieldsStore = defineStore('fields', () => {
  const fields = ref<Field[]>([])
  const loading = ref(false)
  const loaded = ref(false)

  async function load(force = false) {
    if (loading.value || (loaded.value && !force)) return
    loading.value = true
    try {
      fields.value = await getFields()
      loaded.value = true
    } catch {
      fields.value = []
    } finally {
      loading.value = false
    }
  }

  function byId(id: number): Field | undefined {
    return fields.value.find((f) => f.id === id)
  }

  return { fields, loading, loaded, load, byId }
})
