<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

interface Props {
  times: string[]
  values: Array<number | null>
  /** EC | PH */
  metric: 'ec' | 'ph'
  loading?: boolean
  height?: string
  /** 允许区间，绘制上下限虚线 */
  min?: number | null
  max?: number | null
}

const props = withDefaults(defineProps<Props>(), {
  loading: false,
  height: '180px',
  min: null,
  max: null
})

const META = {
  ec: { name: 'EC 电导率', unit: 'mS/cm', color: '#52c41a' },
  ph: { name: 'pH 酸碱度', unit: '', color: '#faad14' }
}

const el = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null
let ro: ResizeObserver | null = null

function buildOption(): echarts.EChartsOption {
  const meta = META[props.metric]
  const data = props.times.map((t, i) => [t, props.values[i] ?? null])
  const lines: unknown[] = []
  if (props.max != null && !Number.isNaN(props.max)) {
    lines.push({
      yAxis: props.max,
      lineStyle: { color: '#f5222d', type: 'dotted', width: 1.2 },
      label: { formatter: `上限 ${props.max}`, position: 'insideEndTop', color: '#f5222d', fontSize: 10 }
    })
  }
  if (props.min != null && !Number.isNaN(props.min)) {
    lines.push({
      yAxis: props.min,
      lineStyle: { color: '#fa8c16', type: 'dotted', width: 1.2 },
      label: { formatter: `下限 ${props.min}`, position: 'insideEndBottom', color: '#fa8c16', fontSize: 10 }
    })
  }
  const series: Record<string, unknown> = {
    name: meta.name,
    type: 'line',
    smooth: 0.25,
    showSymbol: false,
    sampling: 'lttb',
    lineStyle: { width: 1.8, color: meta.color },
    itemStyle: { color: meta.color },
    areaStyle: {
      color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
        { offset: 0, color: meta.color + '33' },
        { offset: 1, color: meta.color + '05' }
      ])
    },
    data
  }
  if (lines.length) series.markLine = { symbol: 'none', data: lines }

  return {
    animationDuration: 300,
    grid: { left: 44, right: 16, top: 28, bottom: 28 },
    title: { text: meta.name, left: 8, top: 2, textStyle: { fontSize: 12, color: '#555', fontWeight: 600 } },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line', lineStyle: { color: '#bbb', type: 'dashed' } },
      valueFormatter: (v) => (v == null ? '—' : `${Number(v).toFixed(2)} ${meta.unit}`)
    },
    xAxis: {
      type: 'time',
      axisLabel: { color: '#999', fontSize: 10 }
    },
    yAxis: {
      type: 'value',
      scale: true,
      name: meta.unit,
      nameTextStyle: { color: '#999', fontSize: 10 },
      axisLabel: { color: '#999', fontSize: 10 },
      splitLine: { lineStyle: { color: '#f5f5f5' } }
    },
    series: [series as echarts.SeriesOption]
  }
}

function render() {
  chart?.setOption(buildOption(), true)
}

onMounted(() => {
  if (!el.value) return
  chart = echarts.init(el.value)
  render()
  ro = new ResizeObserver(() => chart?.resize())
  ro.observe(el.value)
})

watch(() => [props.times, props.values, props.min, props.max], render, { deep: true })

onBeforeUnmount(() => {
  ro?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div style="position: relative">
    <a-spin :spinning="loading" size="small">
      <div ref="el" :style="{ width: '100%', height }" />
    </a-spin>
    <div
      v-if="!loading && values.every((v) => v == null)"
      style="position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; pointer-events: none"
    >
      <a-empty :description="metric === 'ec' ? '暂无 EC 数据' : '暂无 pH 数据'" :image="undefined" />
    </div>
  </div>
</template>
