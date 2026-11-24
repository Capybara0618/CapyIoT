<script setup lang="ts">
import { computed, ref } from 'vue'
import { Background, Controls, VueFlow, type Edge, type Node } from '@xyflow/vue'
import { label, type Topology } from '../video'
import '@xyflow/vue/style.css'
const props = defineProps<{ topology: Topology }>()
const emit = defineEmits<{ select: [deviceId: string] }>()
const kind = ref('NETWORK')
const nodes = computed<Node[]>(() => {
  const levels = new Map(props.topology.nodes.map((d) => [d.deviceId, 0]))
  for (let pass = 0; pass < props.topology.nodes.length; pass++) {
    let changed = false
    for (const edge of props.topology.relations.filter((r) => r.relationType === 'NETWORK')) {
      const next = (levels.get(edge.sourceDeviceId) ?? 0) + 1
      if (next > (levels.get(edge.targetDeviceId) ?? 0)) {
        levels.set(edge.targetDeviceId, next)
        changed = true
      }
    }
    if (!changed) break
  }
  const rows = new Map<number, number>()
  return props.topology.nodes.map((d) => {
    const depth = levels.get(d.deviceId) ?? 0,
      row = rows.get(depth) ?? 0
    rows.set(depth, row + 1)
    return {
      id: d.deviceId,
      position: { x: depth * 290 + 30, y: row * 120 + 30 },
      data: { label: `${d.name}\n${label(d.deviceType)} · ${label(d.connectivityStatus)}` },
      style: {
        width: '225px',
        padding: '16px',
        border: '1px solid #cad7e9',
        borderRadius: '8px',
        background: '#fff',
        color: '#1f2937',
        whiteSpace: 'pre-line',
      },
    }
  })
})
const edges = computed<Edge[]>(() =>
  props.topology.relations
    .filter((r) => r.relationType === kind.value)
    .map((r) => ({
      id: r.relationId,
      source: r.sourceDeviceId,
      target: r.targetDeviceId,
      label: label(r.relationType) + (r.channelNo ? ' ' + r.channelNo : ''),
      style: {
        stroke: kind.value === 'POWER' ? '#a7752a' : kind.value === 'VIDEO' ? '#39816b' : '#5488d0',
        strokeWidth: 2,
      },
    })),
)
</script>
<template>
  <div>
    <div class="video-toolbar">
      <button
        v-for="k in ['NETWORK', 'POWER', 'VIDEO']"
        :key="k"
        :class="{ primary: kind === k }"
        @click="kind = k"
      >
        {{ label(k) }}
      </button>
    </div>
    <div class="video-topology-graph">
      <VueFlow
        :nodes="nodes"
        :edges="edges"
        fit-view-on-init
        :nodes-draggable="false"
        @node-click="(event) => emit('select', event.node.id)"
        ><Background pattern-color="#e5eaf1" /><Controls
      /></VueFlow>
    </div>
  </div>
</template>
