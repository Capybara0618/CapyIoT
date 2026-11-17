<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Activity,
  Cable,
  CircleCheck,
  Clock3,
  FlaskConical,
  Network,
  Play,
  RefreshCw,
  RotateCcw,
  Square,
  Thermometer,
} from 'lucide-vue-next'
import { request } from '../api'
import { eventTypes, formatTime } from '../domain'
import { useLiveUpdates } from '../live'
import { useNetworkData } from '../networkData'
import { session } from '../session'
import {
  phases,
  scenarioNames,
  type Catalogue,
  type ScenarioDetail,
  type ScenarioRun,
  type TemplateId,
} from '../scenarios'
import PageHeader from '../components/PageHeader.vue'
import TopologyGraph from '../components/TopologyGraph.vue'

const templateCards = [
  {
    id: 'NETWORK_UPSTREAM_FAILURE',
    icon: Cable,
    tone: 'red',
    description: '使交换机上报故障并暂停下游心跳，观察主子告警。',
  },
  {
    id: 'DEVICE_FLAPPING',
    icon: Activity,
    tone: 'amber',
    description: '让单台设备两次离线再上线，观察告警抖动抑制。',
  },
  {
    id: 'SENSOR_THRESHOLD_BREACH',
    icon: Thermometer,
    tone: 'cyan',
    description: '传感器保持在线，上报超过阈值的温度读数。',
  },
] as const
const route = useRoute()
const router = useRouter()
const catalogue = ref<Catalogue>()
const recent = ref<ScenarioRun[]>([])
const detail = ref<ScenarioDetail>()
const template = ref<TemplateId>('NETWORK_UPSTREAM_FAILURE')
const building = ref('')
const deviceId = ref('')
const duration = ref(55)
const temperature = ref(41)
const loading = ref(true)
const runningAction = ref(false)
const error = ref('')
const actionError = ref('')
const previewDraft = ref(false)
const now = ref(Date.now())
const admin = computed(() => session.value?.user.role === 'ADMIN')
const current = computed(() => detail.value?.run)
const active = computed(() => recent.value.find((run) => !run.endedAt))
const selectedId = computed(() =>
  typeof route.query.runId === 'string' ? route.query.runId : recent.value[0]?.id,
)
const {
  topology,
  markers,
  error: graphError,
  loading: graphLoading,
  load: loadGraph,
} = useNetworkData()
const policy = computed(() =>
  catalogue.value?.templates.find((option) => option.id === template.value),
)
const candidates = computed(() => {
  const all = catalogue.value?.scope.devices ?? []
  return all.filter((device) => {
    if (template.value === 'DEVICE_FLAPPING') return device.type !== 'SWITCH'
    if (template.value === 'SENSOR_THRESHOLD_BREACH') return device.type === 'SENSOR'
    const count = all.filter((child) => child.upstreamId === device.id).length
    return device.type === 'SWITCH' && count >= 2 && count <= 11
  })
})
const buildings = computed(() => [
  ...new Map(candidates.value.map((item) => [item.buildingId, item.buildingName])).entries(),
])
const matchingDevices = computed(() =>
  candidates.value.filter((item) => item.buildingId === building.value),
)
const stagedIds = computed(() => {
  const id = deviceId.value
  if (!id) return []
  const children =
    template.value === 'NETWORK_UPSTREAM_FAILURE'
      ? (catalogue.value?.scope.devices
          .filter((item) => item.upstreamId === id)
          .map((item) => item.id) ?? [])
      : []
  return [id, ...children]
})
const previewRun = computed(() => (previewDraft.value ? undefined : detail.value))
const previewIds = computed(() => new Set(previewRun.value?.run.targetIds ?? stagedIds.value))
const previewBuilding = computed(() => previewRun.value?.targets[0]?.buildingId ?? building.value)
const preview = computed(() => {
  const nodes =
    previewRun.value?.targets ??
    topology.value.nodes.filter((item) => previewIds.value.has(item.id))
  return {
    ...topology.value,
    nodes,
    edges: topology.value.edges.filter(
      (edge) => previewIds.value.has(edge.source) && previewIds.value.has(edge.target),
    ),
    totalNodes: nodes.length,
    truncated: false,
  }
})
const related = computed(() => [
  ...new Set(
    preview.value.nodes
      .map((device) => markers.value.get(device.id)?.alarmId)
      .filter((id) => id !== undefined),
  ),
])

watch(
  candidates,
  () => {
    if (!buildings.value.some(([id]) => id === building.value))
      building.value = buildings.value[0]?.[0] ?? ''
  },
  { immediate: true },
)
watch(matchingDevices, () => {
  if (!matchingDevices.value.some((item) => item.id === deviceId.value))
    deviceId.value = matchingDevices.value[0]?.id ?? ''
})
watch(policy, (value, previous) => {
  if (value && value.id !== previous?.id) duration.value = value.defaultSeconds
})
watch(previewBuilding, (id) => {
  void loadGraph(id)
})

const steps = computed(() =>
  current.value?.endMode
    ? (['RECOVERING'] as const)
    : current.value?.template === 'DEVICE_FLAPPING'
      ? (['WARMING', 'OBSERVING', 'REBOUNDING', 'OBSERVING_AGAIN', 'RECOVERING'] as const)
      : (['WARMING', 'OBSERVING', 'RECOVERING'] as const),
)
const stage = computed(() => {
  if (!current.value) return -1
  if (current.value.endedAt) return steps.value.length
  if (current.value.phase === 'RECOVERY_REQUIRED') return steps.value.length - 1
  return steps.value.findIndex((step) => step === current.value?.phase)
})
const remaining = computed(() => {
  const run = current.value
  if (!run || !['OBSERVING', 'OBSERVING_AGAIN'].includes(run.phase)) return null
  const first = Math.floor(run.durationSeconds / 2)
  const planned =
    run.template !== 'DEVICE_FLAPPING'
      ? run.durationSeconds
      : run.phase === 'OBSERVING'
        ? first
        : run.durationSeconds - first
  return Math.max(0, planned - Math.floor((now.value - Date.parse(run.phaseAt)) / 1000))
})
const elapsed = computed(() => {
  const run = current.value
  if (!run) return 0
  return Math.max(
    0,
    Math.floor(
      ((run.endedAt ? Date.parse(run.endedAt) : now.value) - Date.parse(run.startedAt)) / 1000,
    ),
  )
})
const progress = computed(() => {
  const run = current.value
  if (!run) return 0
  if (run.phase === 'RECOVERED' || run.phase === 'STOPPED') return 100
  const complete = Math.max(stage.value, 0)
  return Math.min(95, Math.round((100 * complete) / steps.value.length))
})

let alive = true
let revision = 0
let refreshing = false
let refreshQueued = false
let clockOffset = 0
let clock: ReturnType<typeof setInterval>

async function loadCatalogue() {
  try {
    catalogue.value = await request<Catalogue>('/scenarios/templates')
  } catch (failure) {
    actionError.value = (failure as Error).message
  }
}

async function refresh() {
  if (!alive) return
  if (refreshing) {
    refreshQueued = true
    return
  }
  refreshing = true
  const version = revision
  try {
    recent.value = await request<ScenarioRun[]>('/scenario-runs')
    const id = selectedId.value
    const next = id
      ? await request<ScenarioDetail>(`/scenario-runs/${encodeURIComponent(id)}`)
      : undefined
    if (!alive || version !== revision) return
    detail.value = next
    if (next) clockOffset = Date.parse(next.checkedAt) - Date.now()
    now.value = Date.now() + clockOffset
    error.value = ''
  } catch (failure) {
    if (alive && version === revision) error.value = (failure as Error).message
  } finally {
    loading.value = false
    refreshing = false
    if (refreshQueued && alive) {
      refreshQueued = false
      void refresh()
    }
  }
}

const live = useLiveUpdates(
  () => {
    void refresh()
  },
  '/api/v1/stream',
  ['scenario.updated'],
)
watch(
  () => route.query.runId,
  () => {
    revision++
    previewDraft.value = false
    detail.value = undefined
    loading.value = true
    void refresh()
  },
)

async function command(action: 'start' | 'stop' | 'recover') {
  if (runningAction.value || !admin.value) return
  const id = current.value?.id
  if (action !== 'start' && !id) return
  runningAction.value = true
  actionError.value = ''
  try {
    const run = await request<ScenarioRun>(
      action === 'start' ? '/scenario-runs' : `/scenario-runs/${id}/${action}`,
      {
        method: 'POST',
        body:
          action === 'start'
            ? JSON.stringify({
                template: template.value,
                deviceId: deviceId.value,
                durationSeconds: duration.value,
                temperature:
                  template.value === 'SENSOR_THRESHOLD_BREACH' ? temperature.value : null,
              })
            : undefined,
      },
    )
    if (action === 'start') await router.replace({ query: { runId: run.id } })
  } catch (failure) {
    actionError.value = (failure as Error).message
  } finally {
    runningAction.value = false
    void refresh()
  }
}
function retry() {
  actionError.value = ''
  void loadCatalogue()
  void refresh()
  void loadGraph()
}
function openTopology(id: string) {
  void router.push({ path: '/topology', query: { nodeId: id } })
}
function chooseTemplate(id: TemplateId) {
  template.value = id
  previewDraft.value = true
}
onMounted(() => {
  retry()
  let seconds = 0
  clock = setInterval(() => {
    now.value = Date.now() + clockOffset
    if (++seconds % 3 === 0 && (active.value || live.value !== 'connected')) void refresh()
  }, 1000)
})
onBeforeUnmount(() => {
  alive = false
  clearInterval(clock)
})
</script>

<template>
  <section class="network-page scenarios-page">
    <PageHeader>
      <span class="live-status" :class="{ connected: live === 'connected' }">
        <span></span>{{ live === 'connected' ? '实时连接已建立' : '正在重连 · 定时查询运行状态' }}
      </span>
    </PageHeader>
    <div class="scenario-templates" aria-label="场景模板">
      <button
        v-for="card in templateCards"
        :key="card.id"
        class="scenario-template"
        :class="card.tone"
        type="button"
        :aria-pressed="template === card.id"
        @click="chooseTemplate(card.id)"
      >
        <span class="scenario-symbol"><component :is="card.icon" :size="22" /></span>
        <span
          ><strong>{{ scenarioNames[card.id] }}</strong
          ><small>{{ card.description }}</small></span
        >
      </button>
    </div>
    <div v-if="error || actionError" class="network-error" role="alert">
      <span>{{ actionError || error }}</span><button type="button" @click="retry">重试查询</button>
    </div>
    <div class="scenario-workspace">
      <section class="network-panel">
        <div class="network-toolbar">
          <h2><FlaskConical :size="17" /> 场景参数</h2>
        </div>
        <form class="scenario-form" @submit.prevent="command('start')">
          <div v-if="catalogue?.scope.setupMessage" class="scenario-hint">
            {{ catalogue.scope.setupMessage }}
          </div>
          <div v-else-if="!catalogue" class="scenario-hint">正在读取演示设备…</div>
          <label
            >所属楼宇
            <select
              v-model="building"
              aria-label="所属楼宇"
              :disabled="!buildings.length"
              required
              @change="previewDraft = true"
            >
              <option value="" disabled>选择所属楼宇</option>
              <option v-for="[id, name] in buildings" :key="id" :value="id">{{ name }}</option>
            </select>
          </label>
          <label
            >目标设备
            <select
              v-model="deviceId"
              aria-label="目标设备"
              :disabled="!matchingDevices.length"
              required
              @change="previewDraft = true"
            >
              <option value="" disabled>选择目标设备</option>
              <option v-for="device in matchingDevices" :key="device.id" :value="device.id">
                {{ device.name }} · {{ device.code }}
              </option>
            </select>
          </label>
          <label
            >持续时间（秒）
            <input
              v-model.number="duration"
              type="number"
              :min="policy?.minimumSeconds"
              :max="policy?.maximumSeconds"
              required
            />
          </label>
          <label v-if="template === 'SENSOR_THRESHOLD_BREACH'"
            >模拟温度（°C）
            <input v-model.number="temperature" type="number" min="35" max="80" required />
          </label>
          <p v-if="catalogue" class="scenario-hint">
            {{ catalogue.heartbeatSeconds }} 秒心跳 · {{ catalogue.timeoutSeconds }} 秒超时 ·
            {{ catalogue.sweepSeconds }} 秒巡检
          </p>
          <p v-if="!admin" class="scenario-hint">
            管理员可启动和恢复场景，运维账号可查看执行记录。
          </p>
          <p v-else-if="active" class="scenario-hint">园区已有运行，请先完成恢复。</p>
          <p
            v-else-if="catalogue && !catalogue.scope.setupMessage && !candidates.length"
            class="scenario-hint"
          >
            当前场景没有符合条件的演示设备。
          </p>
          <button
            class="scenario-primary"
            :disabled="!admin || !deviceId || !policy || !!active || runningAction || loading"
          >
            <Play :size="15" /> 开始模拟
          </button>
        </form>
      </section>

      <section class="network-panel">
        <div class="network-toolbar">
          <h2><Clock3 :size="17" /> 执行进度</h2>
          <button
            class="scenario-icon-button"
            type="button"
            title="刷新运行记录"
            aria-label="刷新运行记录"
            @click="refresh"
          >
            <RefreshCw :size="15" />
          </button>
        </div>
        <div class="scenario-progress">
          <label v-if="recent.length" class="scenario-history"
            >运行记录
            <select
              :value="selectedId"
              @change="
                router.replace({ query: { runId: ($event.target as HTMLSelectElement).value } })
              "
            >
              <option
                v-if="current && !recent.some((run) => run.id === current?.id)"
                :value="current.id"
              >
                {{ scenarioNames[current.template] }}
              </option>
              <option v-for="run in recent" :key="run.id" :value="run.id">
                {{ scenarioNames[run.template] }} · {{ formatTime(run.startedAt) }}
              </option>
            </select>
          </label>
          <RouterLink
            v-if="active && active.id !== selectedId"
            :to="{ query: { runId: active.id } }"
            >查看当前运行 →</RouterLink
          >
          <p v-if="loading" class="network-note" role="status">正在读取运行记录…</p>
          <template v-else-if="current">
            <div class="scenario-run-status">
              <strong>{{ phases[current.phase] }}</strong>
              <span>{{ elapsed }} 秒 · {{ current.operator }}</span>
            </div>
            <p class="scenario-hint">
              {{ scenarioNames[current.template] }} · 设定时长 {{ current.durationSeconds }} 秒<span
                v-if="current.temperature"
              >
                · {{ current.temperature }} °C</span
              >
            </p>
            <progress :value="progress" max="100" aria-label="场景阶段进度"></progress>
            <ol class="scenario-steps">
              <li
                v-for="(step, index) in steps"
                :key="step"
                :class="{ complete: index < stage, current: index === stage }"
              >
                <CircleCheck v-if="index < stage" :size="15" /><i v-else></i>
                <span>{{ phases[step] }}</span>
                <small v-if="index === stage && remaining !== null">{{
                  remaining > 0 ? `剩余 ${remaining} 秒` : '等待状态确认'
                }}</small>
              </li>
            </ol>
            <div v-if="!current.endedAt && admin" class="scenario-buttons">
              <button
                type="button"
                :disabled="
                  runningAction ||
                  current.phase === 'RECOVERING' ||
                  current.phase === 'RECOVERY_REQUIRED'
                "
                @click="command('stop')"
              >
                <Square :size="14" /> 停止
              </button>
              <button
                type="button"
                :disabled="runningAction || current.phase === 'RECOVERING'"
                @click="command('recover')"
              >
                <RotateCcw :size="14" />
                {{ current.phase === 'RECOVERY_REQUIRED' ? '重试恢复' : '立即恢复' }}
              </button>
            </div>
            <small class="scenario-run-id mono" :title="current.id">{{ current.id }}</small>
          </template>
          <div v-else class="scenario-idle">
            <FlaskConical :size="30" />
            <p>{{ error ? '无法读取运行记录' : '暂无运行记录' }}</p>
          </div>
        </div>
      </section>

      <section class="network-panel scenario-preview">
        <div class="network-toolbar">
          <h2><Network :size="17" /> 拓扑预览</h2>
          <div class="network-controls">
            <button v-if="previewDraft && current" type="button" @click="previewDraft = false">
              预览本次运行
            </button>
            <RouterLink
              v-if="preview.nodes.length"
              :to="{
                path: '/topology',
                query: { nodeId: previewRun?.run.targetIds[0] ?? deviceId },
              }"
              >打开拓扑 →</RouterLink
            >
          </div>
        </div>
        <div v-if="graphError" class="network-note">
          {{ graphError }}
          <button class="scenario-icon-button" type="button" @click="loadGraph()">重试</button>
        </div>
        <TopologyGraph
          v-if="preview.nodes.length"
          :topology="preview"
          :markers="markers"
          campus-name="演示园区"
          compact
          @select="openTopology"
        />
        <div v-else class="scenario-idle">
          <Network :size="30" />
          <p>{{ graphLoading ? '正在读取设备拓扑…' : '请选择演示设备' }}</p>
        </div>
      </section>
    </div>

    <div class="scenario-evidence">
      <section class="network-panel">
        <div class="network-toolbar"><h2>演示范围</h2></div>
        <p v-if="!preview.nodes.length" class="network-note">暂无选定设备。</p>
        <ul v-else class="scenario-list">
          <li v-for="device in preview.nodes" :key="device.id">
            <RouterLink :to="{ path: '/devices', query: { id: device.id } }">{{
              device.name
            }}</RouterLink>
            <span
              class="network-chip"
              :class="device.connectivityStatus === 'OFFLINE' ? 'red' : 'green'"
              >{{
                device.lastHeartbeatAt
                  ? device.connectivityStatus === 'OFFLINE'
                    ? '离线'
                    : '在线'
                  : '未上报'
              }}</span
            >
            <span v-if="device.healthStatus === 'ABNORMAL'" class="network-chip amber">异常</span>
          </li>
          <li v-for="id in related" :key="id">
            <RouterLink :to="{ path: '/alarms', query: { id } }">查看当前关联告警 →</RouterLink>
          </li>
        </ul>
      </section>
      <section class="network-panel">
        <div class="network-toolbar"><h2>操作日志</h2></div>
        <p v-if="!detail?.logs.length" class="network-note">执行后显示后台操作记录。</p>
        <ol v-else class="scenario-list scenario-log">
          <li v-for="log in detail.logs" :key="log.id">
            <time>{{ formatTime(log.at).slice(-8) }}</time><span>{{ log.message }}</span>
          </li>
        </ol>
      </section>
      <section class="network-panel">
        <div class="network-toolbar">
          <h2>事件证据</h2>
          <RouterLink v-if="current" :to="{ path: '/events', query: { scenarioRunId: current.id } }"
            >全部上报 →</RouterLink
          >
        </div>
        <p v-if="!detail?.evidence.length" class="network-note">
          {{ current ? '当前无非心跳事件。' : '暂无事件证据。' }}
        </p>
        <ul v-else class="scenario-list">
          <li v-for="event in detail.evidence" :key="event.eventId">
            <RouterLink
              :to="{ path: '/events', query: { id: event.eventId, scenarioRunId: current?.id } }"
              >{{ eventTypes[event.eventType as keyof typeof eventTypes] }} ·
              {{ event.deviceCode }}</RouterLink
            >
            <time>{{ formatTime(event.receivedAt).slice(-8) }}</time>
          </li>
        </ul>
      </section>
    </div>
  </section>
</template>
