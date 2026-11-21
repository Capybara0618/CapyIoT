<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { init, use, type EChartsType } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])
const props = defineProps<{
  points: { at: number; value: number | null }[]
  from: number
  to: number
  label: string
  color: string
}>()
const element = ref<HTMLDivElement>()
let chart: EChartsType | undefined
let observer: ResizeObserver | undefined

function update() {
  chart?.setOption(
    {
      animation: false,
      grid: { top: 12, left: 42, right: 15, bottom: 25 },
      tooltip: { trigger: 'axis' },
      xAxis: {
        type: 'time',
        min: props.from * 1000,
        max: props.to * 1000,
        splitNumber: 3,
        axisLabel: { color: '#9ab1c6', hideOverlap: true },
        axisLine: { lineStyle: { color: '#315168' } },
      },
      yAxis: {
        type: 'value',
        min: 0,
        splitNumber: 3,
        axisLabel: { color: '#9ab1c6' },
        splitLine: { lineStyle: { color: '#213a50' } },
      },
      series: [
        {
          name: props.label,
          type: 'line',
          showSymbol: false,
          lineStyle: { color: props.color, width: 2 },
          itemStyle: { color: props.color },
          areaStyle: { color: props.color, opacity: 0.12 },
          data: props.points.map(({ at, value }) => [at * 1000, value]),
        },
      ],
    },
    true,
  )
}
onMounted(() => {
  if (!element.value) return
  chart = init(element.value)
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(element.value)
  update()
})
watch(() => [props.points, props.from, props.to], update)
onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
})
</script>

<template>
  <div ref="element" class="monitor-trend" role="img" :aria-label="label"></div>
</template>
