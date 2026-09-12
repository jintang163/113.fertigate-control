<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import type { Job } from '@/types'

interface Props {
  /** 时间轴（ISO 字符串，与 values 对齐） */
  times: string[]
  /** 土壤湿度（体积含水率 %） */
  values: Array<number | null>
  thetaFc?: number | null
  thetaStart?: number | null
  hardMax?: number | null
  /** 用于 markArea 标注的作业时段 */
  jobs?: Job[]
  loading?: boolean
  height?: string
}

const props = withDefaults(defineProps<Props>(), {
  thetaFc: null,
  thetaStart: null,
  hardMax: null,
  jobs: () => [],
  loading: false,
  height: '380px'
})

const el = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null
let ro: ResizeObserver | null = null

const COLORS = {
  moisture: '#1677ff',
  fc: '#1677ff',
  start: '#fa8c16',
  hardMax: '#f5222d',
  jobArea: 'rgba(82,196,26,0.10)',
  jobDone: 'rgba(250,173,20,0.08)'
}

function buildOption(): echarts.EChartsOption {
  const data = props.times.map((t, i) => [t, props.values[i] ?? null])

  const markLines: unknown[] = []
  if (props.thetaFc != null && !Number.isNaN(props.thetaFc)) {
    markLines.push({
      yAxis: props.thetaFc,
      lineStyle: { color: COLORS.fc, type: 'dashed', width: 1.5, opacity: 0.85 },
      label: { formatter: `θfc ${props.thetaFc}%`, position: 'insideEndTop', color: COLORS.fc, fontSize: 11 }
    })
  }
  if (props.thetaStart != null && !Number.isNaN(props.thetaStart)) {
    markLines.push({
      yAxis: props.thetaStart,
      lineStyle: { color: COLORS.start, type: 'dashed', width: 2 },
      label: { formatter: `θ_start ${props.thetaStart}%`, position: 'insideEndBottom', color: COLORS.start, fontSize: 11 }
    })
  }
  if (props.hardMax != null && !Number.isNaN(props.hardMax)) {
    markLines.push({
      yAxis: props.hardMax,
      lineStyle: { color: COLORS.hardMax, type: 'dashed', width: 1.5 },
      label: { formatter: `硬上限 ${props.hardMax}%`, position: 'insideStartTop', color: COLORS.hardMax, fontSize: 11 }
    })
  }

  // 作业时段 markArea：RUNNING / DONE
  const areas: unknown[] = (props.jobs || [])
    .filter((j) => j.startTime)
    .map((j) => [
      {
        xAxis: j.startTime as string,
        itemStyle: { color: j.status === 'RUNNING' ? COLORS.jobArea : COLORS.jobDone },
        label: {
          show: true,
          formatter: j.status === 'RUNNING' ? '灌溉中' : '作业',
          position: 'insideTop',
          color: j.status === 'RUNNING' ? '#389e0d' : '#ad6800',
          fontSize: 11
        }
      },
      { xAxis: j.endTime || new Date().toISOString() }
    ])

  const series: Record<string, unknown> = {
    name: '土壤湿度',
    type: 'line',
    smooth: 0.25,
    showSymbol: false,
    sampling: 'lttb',
    lineStyle: { width: 2, color: COLORS.moisture },
    itemStyle: { color: COLORS.moisture },
    areaStyle: {
      color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
        { offset: 0, color: 'rgba(22,119,255,0.30)' },
        { offset: 1, color: 'rgba(22,119,255,0.02)' }
      ])
    },
    connectNulls: false,
    data
  }
  if (markLines.length) series.markLine = { symbol: 'none', data: markLines }
  if (areas.length) series.markArea = { silent: true, data: areas }

  return {
    animationDuration: 400,
    grid: { left: 48, right: 24, top: 36, bottom: 64 },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line', lineStyle: { color: '#bbb', type: 'dashed' } },
      valueFormatter: (v) => (v == null ? '—' : `${Number(v).toFixed(2)}%`)
    },
    legend: {
      data: ['土壤湿度'],
      top: 4,
      right: 12,
      textStyle: { color: '#555' }
    },
    xAxis: {
      type: 'time',
      axisLine: { lineStyle: { color: '#d9d9d9' } },
      axisLabel: { color: '#888', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      name: '体积含水率 (%)',
      nameTextStyle: { color: '#888', fontSize: 11 },
      axisLabel: { color: '#888', formatter: '{value}%' },
      splitLine: { lineStyle: { color: '#f0f0f0' } }
    },
    dataZoom: [
      { type: 'inside', throttle: 60 },
      { type: 'slider', height: 18, bottom: 16, borderColor: 'transparent' }
    ],
    series: [series as echarts.SeriesOption]
  }
}

function render() {
  if (!chart) return
  chart.setOption(buildOption(), true)
}

onMounted(() => {
  if (!el.value) return
  chart = echarts.init(el.value)
  render()
  ro = new ResizeObserver(() => chart?.resize())
  ro.observe(el.value)
})

watch(
  () => [props.times, props.values, props.thetaFc, props.thetaStart, props.hardMax, props.jobs],
  render,
  { deep: true }
)

onBeforeUnmount(() => {
  ro?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div style="position: relative">
    <a-spin :spinning="loading">
      <div ref="el" :style="{ width: '100%', height }" />
    </a-spin>
    <div
      v-if="!loading && values.every((v) => v == null)"
      style="position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; pointer-events: none"
    >
      <a-empty description="暂无湿度遥测数据" />
    </div>
  </div>
</template>
