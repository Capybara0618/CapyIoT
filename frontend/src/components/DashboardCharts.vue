<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use, type EChartsType, type ComposeOption } from 'echarts/core'
import { LineChart, PieChart, type LineSeriesOption, type PieSeriesOption } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  TooltipComponent,
  type GridComponentOption,
  type LegendComponentOption,
  type TooltipComponentOption,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { AlarmSummary } from '../alarms'
import { readChartStyle } from '../chartStyle'

use([LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])
type ChartOption = ComposeOption<
  | LineSeriesOption
  | PieSeriesOption
  | GridComponentOption
  | LegendComponentOption
  | TooltipComponentOption
>
const props = defineProps<{
  activity?: { hour: number; online: number; offline: number; abnormal: number }[]
  alarms?: AlarmSummary
}>()
const activityEl = ref<HTMLElement>()
const alarmEl = ref<HTMLElement>()
let activityChart: EChartsType | undefined
let alarmChart: EChartsType | undefined
let observer: ResizeObserver | undefined
const hourLabel = new Intl.DateTimeFormat('zh-CN', {
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})
const activitySeries = [
  ['online', '恢复在线', 'success'],
  ['offline', '转为离线', 'danger'],
  ['abnormal', '健康异常', 'warning'],
] as const

function render() {
  const style = readChartStyle()
  const points = props.activity ?? []
  activityChart?.setOption<ChartOption>(
    {
      animation: false,
      textStyle: style.textStyle,
      grid: { left: 40, right: 24, bottom: 28, top: 40 },
      legend: { top: 6, textStyle: { color: style.muted } },
      tooltip: { trigger: 'axis', ...style.tooltip },
      xAxis: {
        type: 'category',
        data: points.map(({ hour }) => hourLabel.format(new Date(hour * 1000))),
        axisLabel: { color: style.muted },
        axisLine: { lineStyle: { color: style.border } },
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        axisLabel: { color: style.muted },
        splitLine: { lineStyle: { color: style.border } },
      },
      series: activitySeries.map(([key, name, color]) => ({
        name,
        type: 'line',
        showSymbol: false,
        itemStyle: { color: style[color] },
        data: points.map((point) => point[key]),
      })),
    },
    true,
  )
  alarmChart?.setOption<ChartOption>(
    {
      animation: false,
      textStyle: style.textStyle,
      tooltip: { trigger: 'item', ...style.tooltip },
      legend: { bottom: 0, textStyle: { color: style.muted } },
      color: [style.danger, style.warning],
      series: [
        {
          type: 'pie',
          radius: ['49%', '72%'],
          center: ['50%', '45%'],
          label: { show: false },
          stillShowZeroSum: false,
          data: props.alarms
            ? [
                { name: '严重', value: props.alarms.critical },
                { name: '警告', value: props.alarms.active - props.alarms.critical },
              ]
            : [],
        },
      ],
    },
    true,
  )
}

onMounted(async () => {
  await document.fonts.ready
  if (!activityEl.value?.isConnected || !alarmEl.value?.isConnected) return
  activityChart = init(activityEl.value)
  alarmChart = init(alarmEl.value)
  observer = new ResizeObserver(() => {
    activityChart?.resize()
    alarmChart?.resize()
  })
  observer.observe(activityEl.value)
  observer.observe(alarmEl.value)
  render()
})
watch(() => [props.activity, props.alarms], render)
onBeforeUnmount(() => {
  observer?.disconnect()
  activityChart?.dispose()
  alarmChart?.dispose()
})
</script>

<template>
  <div class="dashboard-charts">
    <section class="network-panel">
      <div class="network-toolbar">
        <h2>设备状态变化</h2>
        <small>近 24 小时 · 按小时汇总</small>
      </div>
      <div
        ref="activityEl"
        class="dashboard-chart"
        role="img"
        aria-label="每小时设备上线、离线与健康异常次数"
      ></div>
    </section>
    <section class="network-panel">
      <div class="network-toolbar">
        <h2>活跃告警分布</h2>
        <RouterLink to="/alarms">查看告警 →</RouterLink>
      </div>
      <div class="dashboard-donut">
        <div
          ref="alarmEl"
          class="dashboard-chart"
          role="img"
          aria-label="活跃告警严重程度分布"
        ></div>
        <span class="dashboard-chart-count">{{ alarms ? alarms.active : '—' }}</span>
      </div>
    </section>
    <slot />
  </div>
</template>
