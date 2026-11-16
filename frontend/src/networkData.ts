import { shallowRef, ref, onBeforeUnmount } from 'vue'
import { request } from './api'
import { useLiveUpdates } from './live'
import type { ImpactMarker, Topology } from './alarms'

export function useNetworkData() {
  const topology = shallowRef<Topology>({
    version: 0,
    nodes: [],
    edges: [],
    totalNodes: 0,
    truncated: false,
  })
  const markers = shallowRef(new Map<string, ImpactMarker>())
  const error = ref('')
  const loading = ref(false)
  let generation = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  let building = ''

  async function load(buildingId = building) {
    building = buildingId
    const current = ++generation
    loading.value = true
    try {
      const graph = await request<Topology>(
        `/topology/snapshot?${new URLSearchParams({ buildingId })}`,
      )
      const found = graph.nodes.length
        ? await request<ImpactMarker[]>(
            `/alarms/impact?${new URLSearchParams({ deviceIds: graph.nodes.map((node) => node.id).join(',') })}`,
          )
        : []
      if (generation !== current) return
      topology.value = graph
      const mapped = new Map<string, ImpactMarker>()
      for (const item of found) if (!mapped.has(item.deviceId)) mapped.set(item.deviceId, item)
      markers.value = mapped
      error.value = ''
    } catch (failure) {
      if (generation === current) error.value = (failure as Error).message
    } finally {
      if (generation === current) loading.value = false
    }
  }

  function refresh() {
    if (timer !== undefined) return
    timer = setTimeout(() => {
      timer = undefined
      void load()
    }, 400)
  }
  const deviceLive = useLiveUpdates(refresh)
  const alarmLive = useLiveUpdates(refresh, '/api/v1/alarms/stream')
  onBeforeUnmount(() => {
    generation++
    clearTimeout(timer)
  })
  return { topology, markers, error, loading, deviceLive, alarmLive, load }
}
