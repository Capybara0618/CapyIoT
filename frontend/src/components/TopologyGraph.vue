<script setup lang="ts">
import { computed, shallowRef, watch } from 'vue'
import {
  Background,
  Controls,
  Handle,
  MiniMap,
  Position,
  VueFlow,
  type Edge,
  type Node,
  type VueFlowInstance,
} from '@xyflow/vue'
import { Building2, Camera, Cable, DoorOpen, Network, Router, Thermometer } from 'lucide-vue-next'
import { deviceTypes, type Device } from '../domain'
import type { ImpactMarker, Topology } from '../alarms'
import '@xyflow/vue/style.css'

const props = defineProps<{
  topology: Topology
  markers: Map<string, ImpactMarker>
  campusName: string
  selected?: string
  highlight?: string
  compact?: boolean
}>()
const emit = defineEmits<{ select: [id: string] }>()
type GraphNode = Node<
  { label: string; role: string; state: string; hint: string; selected: boolean },
  'asset'
>
const instance = shallowRef<VueFlowInstance<GraphNode>>()

function groupDevices<Key>(key: (device: Device) => Key) {
  const groups = new Map<Key, Device[]>()
  for (const device of props.topology.nodes) {
    const id = key(device)
    const group = groups.get(id)
    if (group) group.push(device)
    else groups.set(id, [device])
  }
  return groups
}

const graphic = computed(() => {
  const nodes: GraphNode[] = []
  const edges: Edge[] = []
  const devices = new Map(props.topology.nodes.map((device) => [device.id, device]))
  const buildings = groupDevices((device) => device.buildingId)
  const buildingWidth = 314

  function edge(source: string, target: string, spatial = false) {
    const signal = props.markers.get(target)
    const grouping = signal?.rootNetworkNodeId === source ? signal.relationKind : ''
    const tone = spatial
      ? '#406d81'
      : grouping === 'CONFIRMED_UPSTREAM'
        ? '#ff6775'
        : grouping === 'SUSPECTED_UPSTREAM'
          ? '#fbb453'
          : '#29bfd5'
    edges.push({
      id: `${source}:${target}`,
      source,
      target,
      type: 'smoothstep',
      selectable: false,
      style: {
        stroke: tone,
        strokeWidth: grouping ? 2.7 : 1.5,
        strokeDasharray: grouping === 'SUSPECTED_UPSTREAM' || spatial ? '6 5' : undefined,
      },
    })
  }

  function node(
    id: string,
    label: string,
    x: number,
    y: number,
    role: string,
    state = '',
    hint = '',
  ) {
    nodes.push({
      id,
      type: 'asset',
      position: { x: x - 68, y },
      data: { label, role, state, hint, selected: props.selected === id },
      selectable: role !== 'campus' && role !== 'building',
      class: `graph-node graph-${role}`,
      style: {
        opacity:
          props.highlight &&
          !`${label} ${devices.get(id)?.deviceCode ?? ''}`
            .toLowerCase()
            .includes(props.highlight.toLowerCase()) &&
          role !== 'campus' &&
          role !== 'building'
            ? 0.4
            : 1,
      },
      ariaLabel: `${label} ${hint}`.trim(),
    })
  }

  function drawDevice(device: Device, x: number, y: number) {
    const marker = props.markers.get(device.id)
    const suspect =
      marker?.relationKind === 'SUSPECTED_UPSTREAM' && marker.rootNetworkNodeId === device.id
    const confirmed =
      marker?.relationKind === 'CONFIRMED_UPSTREAM' && marker.rootNetworkNodeId === device.id
    const tone =
      confirmed || (device.lastHeartbeatAt && device.connectivityStatus === 'OFFLINE')
        ? 'offline'
        : marker || device.healthStatus === 'ABNORMAL'
          ? 'warning'
          : device.connectivityStatus === 'ONLINE'
            ? 'online'
            : 'neutral'
    const status = confirmed
      ? '已确认根因'
      : suspect
        ? '疑似根因'
        : device.healthStatus === 'ABNORMAL'
          ? '异常'
          : marker && device.connectivityStatus === 'ONLINE'
            ? '在线 · 有告警'
            : !device.lastHeartbeatAt
              ? '待上报'
              : device.connectivityStatus === 'ONLINE'
                ? '在线'
                : '离线'
    node(device.id, device.name, x, y, device.type, tone, status)
  }

  const ordered = [...buildings].sort((a, b) =>
    a[1][0].buildingName.localeCompare(b[1][0].buildingName, 'zh-CN'),
  )
  ordered.forEach(([id, group], index) => {
    const center = index * buildingWidth
    const levels = new Map<number, Device[]>()
    const detached: Device[] = []
    for (const device of [...group].sort((a, b) => a.deviceCode.localeCompare(b.deviceCode))) {
      let depth = 0
      let ancestor = devices.get(device.upstreamNodeId ?? '')
      if (!ancestor && device.type !== 'SWITCH') {
        detached.push(device)
        continue
      }
      while (ancestor?.buildingId === id) {
        depth++
        ancestor = devices.get(ancestor.upstreamNodeId ?? '')
      }
      const level = levels.get(depth)
      if (level) level.push(device)
      else levels.set(depth, [device])
    }
    let y = 190
    const tiers = [...levels].sort(([first], [second]) => first - second).map(([, items]) => items)
    if (detached.length) tiers.push(detached)
    for (const items of tiers) {
      items.forEach((device, slot) => {
        const single = slot % 2 === 0 && slot === items.length - 1
        const x = single ? center : center + (slot % 2 === 0 ? -74 : 74)
        drawDevice(device, x, y + Math.floor(slot / 2) * 77)
        if (!devices.has(device.upstreamNodeId ?? '')) edge(`building:${id}`, device.id, true)
      })
      y += Math.ceil(items.length / 2) * 77 + 22
    }
    node(
      `building:${id}`,
      group[0].buildingName,
      center,
      95,
      'building',
      '',
      `${group.length} 台设备`,
    )
    edge('campus', `building:${id}`, true)
  })
  node(
    'campus',
    props.campusName,
    (Math.max(0, ordered.length - 1) * buildingWidth) / 2,
    0,
    'campus',
  )
  for (const link of props.topology.edges) edge(link.source, link.target)
  return { nodes, edges }
})

const roles = {
  campus: Network,
  building: Building2,
  SWITCH: Router,
  CAMERA: Camera,
  ACCESS_CONTROL: DoorOpen,
  SENSOR: Thermometer,
} as const
watch(
  () => graphic.value.nodes.map((node) => node.id).join(','),
  () => {
    void instance.value?.fitView({ padding: 0.2, duration: 180, maxZoom: 1.05 })
  },
  { flush: 'post' },
)
function select(id: string) {
  if (props.topology.nodes.some((device) => device.id === id)) emit('select', id)
}
</script>

<template>
  <div class="topology-graph" :class="{ compact }">
    <VueFlow
      v-if="topology.nodes.length"
      :nodes="graphic.nodes"
      :edges="graphic.edges"
      :nodes-draggable="false"
      :nodes-connectable="false"
      :elements-selectable="false"
      :delete-key-code="null"
      :min-zoom="0.12"
      :max-zoom="1.8"
      :fit-view="true"
      :fit-view-options="{ padding: 0.16, maxZoom: 1.05 }"
      color-mode="dark"
      @init="instance = $event"
      @node-click="select($event.node.id)"
    >
      <template #node-asset="{ id, data }">
        <Handle v-if="data.role !== 'campus'" type="target" :position="Position.Top" />
        <component :is="roles[data.role as keyof typeof roles] ?? Cable" :size="20" />
        <button
          v-if="data.role !== 'campus' && data.role !== 'building'"
          type="button"
          class="graph-label"
          :class="{ 'graph-selected': data.selected }"
          :title="`${data.label} · ${deviceTypes[data.role as keyof typeof deviceTypes]}`"
          @click.stop="select(id)"
        >
          {{ data.label }}
        </button>
        <b v-else>{{ data.label }}</b>
        <small v-if="data.hint" :class="String(data.state)">{{ data.hint }}</small>
        <Handle
          v-if="['campus', 'building', 'SWITCH'].includes(String(data.role))"
          type="source"
          :position="Position.Bottom"
        />
      </template>
      <Background :gap="20" :size="1" pattern-color="#31516a" />
      <Controls :show-interactive="false" position="bottom-left" />
      <MiniMap
        v-if="!compact"
        pannable
        zoomable
        position="bottom-right"
        :width="144"
        :height="98"
      />
    </VueFlow>
    <div v-else class="network-empty">
      <Network :size="34" />
      <p>当前范围暂无设备</p>
    </div>
  </div>
</template>
