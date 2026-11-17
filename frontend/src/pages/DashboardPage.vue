<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  BellRing,
  CircleCheck,
  ClipboardList,
  FlaskConical,
  HardDrive,
  Network,
  Route,
} from 'lucide-vue-next'
import { request } from '../api'
import { alarmTitle, relations, severityLabels, type Alarm, type Overview } from '../alarms'
import { formatTime, type Page } from '../domain'
import { useNetworkData } from '../networkData'
import { useLiveUpdates } from '../live'
import { workStream } from '../workorders'
import { phases, scenarioNames, type ScenarioRun } from '../scenarios'
import PageHeader from '../components/PageHeader.vue'
import TopologyGraph from '../components/TopologyGraph.vue'
import DashboardCharts from '../components/DashboardCharts.vue'

const router = useRouter()
const overview = ref<Overview>()
const recent = ref<Alarm[]>([])
const activity = ref<{ hour: number; online: number; offline: number; abnormal: number }[]>()
const scenario = ref<ScenarioRun | null>()
const scenarioError = ref('')
const fetchError = ref('')
let requestId = 0
const { topology, markers, error, deviceLive, alarmLive, load } = useNetworkData()
const workflowLive = useLiveUpdates(() => {
  void refresh()
}, workStream)
useLiveUpdates(
  () => {
    void refreshScenario()
  },
  '/api/v1/stream',
  ['scenario.updated'],
)
const overviewGraph = computed(() => {
  if (!topology.value.edges.length) return topology.value
  const connected = new Set(topology.value.edges.flatMap(({ source, target }) => [source, target]))
  const nodes = topology.value.nodes.filter(
    (item) => connected.has(item.id) || markers.value.has(item.id),
  )
  return { ...topology.value, nodes }
})
const extraDevices = computed(() => topology.value.nodes.length - overviewGraph.value.nodes.length)
const ready = computed(
  () =>
    deviceLive.value === 'connected' &&
    alarmLive.value === 'connected' &&
    workflowLive.value === 'connected',
)
const metrics = computed(() => [
  {
    name: '设备总数',
    value: overview.value?.devices.total,
    tone: 'cyan',
    icon: HardDrive,
    to: '/devices',
  },
  {
    name: '在线设备',
    value: overview.value?.devices.online,
    tone: 'green',
    icon: CircleCheck,
    to: '/devices?connectivity=ONLINE',
  },
  {
    name: '活跃告警',
    value: overview.value?.alarms.active,
    tone: 'amber',
    icon: BellRing,
    to: '/alarms',
  },
  {
    name: '待处理工单',
    value: overview.value?.workorders.pending,
    tone: 'cyan',
    icon: ClipboardList,
    to: '/workorders',
  },
])

async function refresh() {
  const id = ++requestId
  try {
    const [counts, alerts, history] = await Promise.all([
      request<Overview>('/dashboard/overview'),
      request<Page<Alarm>>('/alarms?size=5'),
      request<NonNullable<typeof activity.value>>('/devices/activity'),
    ])
    if (id !== requestId) return
    overview.value = counts
    recent.value = alerts.items.filter((item) => item.status !== 'RECOVERED')
    activity.value = history
    fetchError.value = ''
  } catch (failure) {
    if (id === requestId) fetchError.value = (failure as Error).message
  }
}
watch(topology, () => {
  void refresh()
})
async function refreshScenario() {
  try {
    scenario.value = (await request<ScenarioRun[]>('/scenario-runs'))[0] ?? null
    scenarioError.value = ''
  } catch (failure) {
    scenarioError.value = (failure as Error).message
  }
}
void refresh()
function reload() {
  void load()
  void refresh()
}
function select(id: string) {
  void router.push({ path: '/topology', query: { nodeId: id } })
}
</script>

<template>
  <section class="network-page dashboard-page">
    <PageHeader
      ><span class="live-status" :class="{ connected: ready }"
        ><span></span>{{ ready ? '实时连接已建立' : '正在连接实时通知' }}</span
      ><RouterLink class="scenario-secondary" to="/scenarios"
        ><FlaskConical :size="15" /> 演示模式</RouterLink
      ></PageHeader
    >
    <div class="network-metrics">
      <RouterLink
        v-for="item in metrics"
        :key="item.name"
        :to="item.to"
        class="network-metric"
        :class="item.tone"
      >
        <component :is="item.icon" :size="26" />
        <div>
          <small>{{ item.name }}</small><strong>{{ item.value ?? '—' }}</strong>
        </div>
      </RouterLink>
    </div>
    <div v-if="error || fetchError" class="network-error" role="alert">
      {{ error || fetchError }}
      <button @click="reload">重试</button>
    </div>
    <div class="network-columns dashboard-columns">
      <section class="network-panel">
        <div class="network-toolbar">
          <h2><Network :size="18" /> 园区网络拓扑</h2>
          <RouterLink to="/topology">打开拓扑中心 →</RouterLink>
        </div>
        <TopologyGraph
          :topology="overviewGraph"
          :markers="markers"
          campus-name="演示园区"
          compact
          @select="select"
        />
        <div class="network-legend">
          <span><i></i>正常链路</span><span><i class="confirmed"></i>已确认链路</span
          ><span><i class="suspected"></i>疑似链路</span>
          <RouterLink v-if="extraDevices" to="/topology"
            >另有 {{ extraDevices }} 台未关联设备 →</RouterLink
          >
        </div>
      </section>
      <aside class="network-side">
        <section class="network-panel">
          <div class="network-toolbar">
            <h2><BellRing :size="18" /> 实时告警</h2>
            <RouterLink to="/alarms">查看全部 →</RouterLink>
          </div>
          <div v-if="recent.length" class="dashboard-alarms">
            <RouterLink
              v-for="item in recent"
              :key="item.id"
              :to="{ path: '/alarms', query: { id: item.id } }"
            >
              <span class="network-chip" :class="item.severity === 'CRITICAL' ? 'red' : 'amber'">{{
                severityLabels[item.severity]
              }}</span>
              <div>
                <strong>{{ alarmTitle(item) }}</strong><small
                  >{{ relations[item.relationKind] }} · {{ formatTime(item.lastSeenAt) }}</small
                >
              </div>
            </RouterLink>
          </div>
          <p v-else class="network-note">{{ overview ? '暂无活跃告警' : '正在读取告警' }}</p>
        </section>
        <section class="network-panel">
          <div class="network-toolbar">
            <h2><Route :size="18" /> 告警影响</h2>
          </div>
          <dl class="network-totals">
            <div>
              <dt><RouterLink to="/devices?connectivity=OFFLINE">离线设备</RouterLink></dt>
              <dd>{{ overview?.devices.offline ?? '—' }}</dd>
            </div>
            <div>
              <dt>聚合主告警</dt>
              <dd>{{ overview?.alarms.grouped ?? '—' }}</dd>
            </div>
            <div>
              <dt>受影响设备</dt>
              <dd>{{ overview?.alarms.affected ?? '—' }}</dd>
            </div>
          </dl>
        </section>
      </aside>
    </div>
    <DashboardCharts :activity="activity" :alarms="overview?.alarms">
      <section class="network-panel dashboard-scenario">
        <div class="network-toolbar">
          <h2><FlaskConical :size="17" /> 场景模拟</h2>
        </div>
        <div class="dashboard-scenario-body">
          <p v-if="scenarioError" class="scenario-hint">{{ scenarioError }}</p>
          <template v-else-if="scenario">
            <strong>{{ scenarioNames[scenario.template] }}</strong>
            <span class="scenario-hint"
              >{{ phases[scenario.phase] }} · {{ scenario.targetIds.length }} 台目标设备</span
            >
          </template>
          <p v-else class="scenario-hint">
            {{ scenario === null ? '暂无运行记录' : '正在读取运行记录…' }}
          </p>
          <RouterLink
            class="scenario-secondary"
            :to="{ path: '/scenarios', query: scenario ? { runId: scenario.id } : {} }"
          >
            {{ scenario ? '查看运行记录' : '打开场景模拟' }} →
          </RouterLink>
        </div>
      </section>
    </DashboardCharts>
  </section>
</template>
