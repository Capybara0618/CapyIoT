<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Activity,
  CircleAlert,
  CircleCheck,
  Layers3,
  Network,
  RefreshCw,
  Route,
  Search,
} from 'lucide-vue-next'
import { request } from '../api'
import { deviceTypes, formatTime, type Device, type Locations } from '../domain'
import { relations } from '../alarms'
import { useNetworkData } from '../networkData'
import PageHeader from '../components/PageHeader.vue'
import TopologyGraph from '../components/TopologyGraph.vue'

const route = useRoute()
const router = useRouter()
const building = ref(String(route.query.buildingId ?? ''))
const selected = computed(() => String(route.query.nodeId ?? ''))
const highlight = ref('')
const list = ref(false)
const locations = ref<Locations>()
const isolated = ref<Device>()
const { topology, markers, error, loading, deviceLive, alarmLive, load } = useNetworkData()

const node = computed(
  () => topology.value.nodes.find((item) => item.id === selected.value) ?? isolated.value,
)
const marker = computed(() => markers.value.get(selected.value))
const downstream = computed(() =>
  topology.value.nodes.filter((device) => device.upstreamNodeId === selected.value),
)
const affected = computed(() =>
  topology.value.nodes.filter((device) => {
    const impact = markers.value.get(device.id)
    return device.id !== selected.value && impact?.rootNetworkNodeId === selected.value
  }),
)
const metrics = computed(() => [
  { name: '全部节点', value: topology.value.totalNodes, tone: 'cyan', icon: Network },
  {
    name: '在线节点',
    value: topology.value.nodes.filter((item) => item.connectivityStatus === 'ONLINE').length,
    tone: 'green',
    icon: CircleCheck,
  },
  {
    name: '离线节点',
    value: topology.value.nodes.filter((item) => item.connectivityStatus === 'OFFLINE').length,
    tone: 'red',
    icon: CircleAlert,
  },
  { name: '告警节点', value: markers.value.size, tone: 'amber', icon: Activity },
  {
    name: '受影响设备',
    value: [...markers.value.values()].filter(
      (item) => item.rootNetworkNodeId && item.rootNetworkNodeId !== item.deviceId,
    ).length,
    tone: 'cyan',
    icon: Route,
  },
])
const connected = computed(
  () => deviceLive.value === 'connected' && alarmLive.value === 'connected',
)

function choose(id: string) {
  void router.replace({ query: { ...route.query, nodeId: id } })
}
function filter() {
  void router.replace({
    query: { ...route.query, buildingId: building.value || undefined, nodeId: undefined },
  })
}
function search() {
  const term = highlight.value.trim().toLowerCase()
  const found = topology.value.nodes.find((item) =>
    `${item.name} ${item.deviceCode}`.toLowerCase().includes(term),
  )
  if (term && found) choose(found.id)
}
function status(device: Device) {
  return !device.lastHeartbeatAt
    ? '待上报'
    : device.connectivityStatus === 'ONLINE'
      ? '在线'
      : '离线'
}

watch(
  () => route.query.buildingId,
  () => {
    building.value = String(route.query.buildingId ?? '')
    void load(building.value)
  },
  { immediate: true },
)
watch(
  selected,
  async (id) => {
    isolated.value = undefined
    if (!id || topology.value.nodes.some((item) => item.id === id)) return
    try {
      const found = await request<Device>(`/devices/${encodeURIComponent(id)}`)
      if (selected.value === id) isolated.value = found
    } catch (failure) {
      if (selected.value === id) error.value = (failure as Error).message
    }
  },
  { immediate: true },
)
onMounted(async () => {
  try {
    locations.value = await request<Locations>('/locations')
  } catch (failure) {
    error.value = (failure as Error).message
  }
})
</script>

<template>
  <section class="network-page">
    <PageHeader>
      <span class="live-status" :class="{ connected }"
        ><span></span>{{ connected ? '实时连接已建立' : '正在连接实时通知' }}</span
      >
    </PageHeader>
    <div class="network-metrics topology-metrics" :aria-busy="loading">
      <article v-for="item in metrics" :key="item.name" class="network-metric" :class="item.tone">
        <component :is="item.icon" :size="25" />
        <div>
          <small>{{ item.name }}</small><strong>{{
            loading && !topology.nodes.length ? '—' : item.value
          }}</strong>
        </div>
      </article>
    </div>
    <div v-if="error" class="network-error" role="alert">
      {{ error }} <button @click="load()">重试</button>
    </div>
    <div class="network-columns">
      <section class="network-panel">
        <div class="network-toolbar">
          <h2><Network :size="18" /> 园区网络</h2>
          <div class="network-controls">
            <select v-model="building" aria-label="所属楼宇" @change="filter">
              <option value="">全部楼宇</option>
              <option v-for="item in locations?.buildings" :key="item.id" :value="item.id">
                {{ item.name }}
              </option>
            </select>
            <form @submit.prevent="search">
              <input
                v-model="highlight"
                aria-label="搜索节点"
                placeholder="节点名称或编号"
              /><button aria-label="定位节点"><Search :size="15" /></button>
            </form>
            <button type="button" :aria-pressed="list" @click="list = !list">
              <Layers3 :size="15" />{{ list ? '图形拓扑' : '节点列表' }}
            </button>
            <button type="button" aria-label="刷新拓扑" @click="load()">
              <RefreshCw :size="15" />
            </button>
          </div>
        </div>
        <p v-if="topology.truncated" class="network-note">
          当前展示前 100 个节点，可按楼宇缩小范围。
        </p>
        <div v-if="list" class="network-list">
          <button
            v-for="item in topology.nodes"
            :key="item.id"
            :aria-pressed="item.id === selected"
            @click="choose(item.id)"
          >
            <span>{{ item.name }}</span><small
              >{{ item.buildingName }} · {{ item.deviceCode }}</small
            ><strong>{{ status(item) }}</strong>
          </button>
          <p v-if="!topology.nodes.length" class="network-note">当前范围暂无设备</p>
        </div>
        <TopologyGraph
          v-else
          :topology="topology"
          :markers="markers"
          :campus-name="locations?.campus.name ?? '演示园区'"
          :selected="selected"
          :highlight="highlight.trim()"
          @select="choose"
        />
        <div class="network-legend">
          <span><i></i>正常链路</span><span><i class="confirmed"></i>已确认链路</span
          ><span><i class="suspected"></i>疑似链路</span><span><i class="space"></i>空间归属</span>
        </div>
      </section>
      <aside class="network-side">
        <section class="network-panel">
          <div class="network-toolbar">
            <h2><Route :size="18" /> 节点信息</h2>
          </div>
          <div v-if="node" class="network-detail">
            <h3>{{ node.name }}</h3>
            <span class="network-chip" :class="status(node) === '离线' ? 'red' : 'green'">{{
              status(node)
            }}</span>
            <span
              v-if="marker"
              class="network-chip"
              :class="marker.relationKind === 'CONFIRMED_UPSTREAM' ? 'red' : 'amber'"
              >{{ relations[marker.relationKind] }}</span
            >
            <dl>
              <dt>设备编号</dt>
              <dd>{{ node.deviceCode }}</dd>
              <dt>设备类型</dt>
              <dd>{{ deviceTypes[node.type] }}</dd>
              <dt>所属位置</dt>
              <dd>{{ node.buildingName }} / {{ node.floorName }}</dd>
              <dt>上游节点</dt>
              <dd>
                <button
                  v-if="node.upstreamNodeId"
                  class="plain-button"
                  @click="choose(node.upstreamNodeId)"
                >
                  {{ node.upstreamNodeName }}</button><template v-else>未配置</template>
              </dd>
              <dt>最后心跳</dt>
              <dd>{{ formatTime(node.lastHeartbeatAt) }}</dd>
            </dl>
            <div class="network-links">
              <RouterLink :to="{ path: '/devices', query: { id: node.id } }">设备详情</RouterLink>
              <RouterLink v-if="marker" :to="{ path: '/alarms', query: { id: marker.alarmId } }"
                >查看关联告警</RouterLink
              >
            </div>
          </div>
          <div v-else class="network-empty minor">
            <Network :size="25" />
            <p>选择节点查看设备和关联告警</p>
          </div>
        </section>
        <section class="network-panel">
          <div class="network-toolbar">
            <h2>
              <Layers3 :size="18" /> {{ affected.length ? '受影响设备' : '直属下游' }}
              <small>{{ (affected.length ? affected : downstream).length }}</small>
            </h2>
          </div>
          <div class="network-list narrow">
            <button
              v-for="item in affected.length ? affected : downstream"
              :key="item.id"
              @click="choose(item.id)"
            >
              <span>{{ item.name }}</span><strong>{{ status(item) }}</strong>
            </button>
            <p v-if="!downstream.length && !affected.length" class="network-note">暂无关联设备</p>
          </div>
        </section>
      </aside>
    </div>
  </section>
</template>
