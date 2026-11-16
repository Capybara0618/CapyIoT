<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElPagination, ElTable, ElTableColumn } from 'element-plus'
import { BellRing, CircleAlert, Layers3, Network, RefreshCw, Search, X } from 'lucide-vue-next'
import { request } from '../api'
import { useLiveUpdates } from '../live'
import { formatTime, type Locations, type Page } from '../domain'
import {
  alarmStatuses,
  alarmTitle,
  alarmTypes,
  evidenceTypes,
  relations,
  severityLabels,
  type Alarm,
  type AlarmDetail,
  type AlarmRules,
  type AlarmSummary,
  type Evidence,
} from '../alarms'
import PageHeader from '../components/PageHeader.vue'
import { workStatuses, workStream, type WorkCreated, type WorkOrder } from '../workorders'

const route = useRoute()
const router = useRouter()
const page = ref<Page<Alarm>>({ items: [], total: 0, page: 1, size: 10 })
const detail = ref<AlarmDetail>()
const linkedOrder = ref<WorkOrder | null>()
const creatingOrder = ref(false)
const workError = ref('')
const summary = ref<AlarmSummary>()
const rules = ref<AlarmRules>()
const buildings = ref<Locations['buildings']>([])
const loading = ref(false)
const error = ref('')
const detailError = ref('')
const acknowledging = ref(false)
const selected = computed(() => String(route.query.id ?? ''))
const sections = {
  children: '子告警',
  devices: '受影响设备',
  evidence: '原始证据',
  rules: '关联规则',
} as const
const tab = ref<keyof typeof sections>('children')
const detailPages = reactive({ children: 1, devices: 1, evidence: 1 })
const filter = reactive({ severity: '', status: '', alarmType: '', buildingId: '' })
const expanded = ref('')
const metrics = computed(() => [
  { name: '活跃告警', value: summary.value?.active, tone: 'blue', icon: BellRing },
  { name: '严重告警', value: summary.value?.critical, tone: 'red', icon: CircleAlert },
  { name: '聚合主告警', value: summary.value?.grouped, tone: 'orange', icon: Layers3 },
  { name: '受影响设备', value: summary.value?.affected, tone: 'blue', icon: Network },
])
let listVersion = 0
let detailVersion = 0
let timer: ReturnType<typeof setTimeout> | undefined

async function load() {
  const version = ++listVersion
  loading.value = true
  const query = new URLSearchParams({ size: '10' })
  for (const key of ['severity', 'status', 'alarmType', 'buildingId', 'mainOnly', 'page']) {
    if (typeof route.query[key] === 'string') query.set(key, route.query[key])
  }
  try {
    const [rows, counts] = await Promise.all([
      request<Page<Alarm>>(`/alarms?${query}`),
      request<AlarmSummary>('/alarms/summary'),
    ])
    if (version !== listVersion) return
    page.value = rows
    summary.value = counts
    error.value = ''
  } catch (failure) {
    if (version === listVersion) error.value = (failure as Error).message
  } finally {
    if (version === listVersion) loading.value = false
  }
}

async function loadDetail(id = selected.value) {
  const version = ++detailVersion
  if (!id) return
  const params = new URLSearchParams({
    childPage: String(detailPages.children),
    evidencePage: String(detailPages.evidence),
    devicePage: String(detailPages.devices),
  })
  try {
    const row = await request<AlarmDetail>(`/alarms/${encodeURIComponent(id)}?${params}`)
    if (version !== detailVersion) return
    detail.value = row
    detailError.value = ''
    void loadLinkedOrder(id, version)
  } catch (failure) {
    if (version === detailVersion) detailError.value = (failure as Error).message
  }
}

async function loadLinkedOrder(id: string, version = detailVersion) {
  try {
    const order = await request<WorkOrder | null>(`/workorders/by-alarm/${encodeURIComponent(id)}`)
    if (version === detailVersion && selected.value === id) {
      linkedOrder.value = order
      workError.value = ''
    }
  } catch (failure) {
    if (version === detailVersion && selected.value === id)
      workError.value = (failure as Error).message
  }
}

async function createWorkOrder() {
  const alarm = detail.value?.alarm
  if (!alarm || creatingOrder.value) return
  creatingOrder.value = true
  workError.value = ''
  try {
    const result = await request<WorkCreated>('/workorders', {
      method: 'POST',
      headers: { 'Idempotency-Key': `alarm:${alarm.id}` },
      body: JSON.stringify({ sourceAlarmId: alarm.id }),
    })
    if (selected.value === String(alarm.id)) {
      linkedOrder.value = result.order
      await router.push({ path: '/workorders', query: { id: result.order.id } })
    }
  } catch (failure) {
    workError.value = (failure as Error).message
  } finally {
    creatingOrder.value = false
  }
}

function refresh() {
  if (timer !== undefined) return
  timer = setTimeout(() => {
    timer = undefined
    void load()
    void loadDetail()
  }, 300)
}
const live = useLiveUpdates(refresh, '/api/v1/alarms/stream')
useLiveUpdates(() => {
  if (selected.value && detail.value) void loadLinkedOrder(selected.value)
}, workStream)
watch(
  () => [
    route.query.severity,
    route.query.status,
    route.query.alarmType,
    route.query.buildingId,
    route.query.mainOnly,
    route.query.page,
  ],
  () => {
    for (const key of ['severity', 'status', 'alarmType', 'buildingId'] as const)
      filter[key] = String(route.query[key] ?? '')
    void load()
  },
  { immediate: true },
)
watch(
  selected,
  () => {
    detail.value = undefined
    linkedOrder.value = undefined
    workError.value = ''
    detailError.value = ''
    tab.value = 'children'
    expanded.value = ''
    Object.assign(detailPages, { children: 1, devices: 1, evidence: 1 })
    void loadDetail()
  },
  { immediate: true },
)
onMounted(async () => {
  try {
    const [locations, settings] = await Promise.all([
      request<Locations>('/locations'),
      request<AlarmRules>('/alarm-rules'),
    ])
    buildings.value = locations.buildings
    rules.value = settings
  } catch (failure) {
    error.value = (failure as Error).message
  }
})
onBeforeUnmount(() => {
  listVersion++
  detailVersion++
  clearTimeout(timer)
})

function select(id?: number) {
  void router.replace({ query: { ...route.query, id: id ? String(id) : undefined } })
}
function search() {
  void router.replace({
    query: {
      ...route.query,
      ...Object.fromEntries(
        Object.entries(filter).map(([key, value]) => [key, value || undefined]),
      ),
      page: undefined,
    },
  })
}
function reset() {
  Object.assign(filter, { severity: '', status: '', alarmType: '', buildingId: '' })
  search()
}
function changePage(next: number) {
  void router.replace({ query: { ...route.query, page: String(next) } })
}
function toggleMain(all: boolean) {
  void router.replace({
    query: { ...route.query, mainOnly: all ? 'false' : undefined, page: undefined },
  })
}
function detailPage(section: 'children' | 'devices' | 'evidence', next: number) {
  detailPages[section] = next
  void loadDetail()
}
async function acknowledge() {
  if (!detail.value || acknowledging.value) return
  const id = detail.value.alarm.id
  acknowledging.value = true
  try {
    await request(`/alarms/${id}/acknowledge`, { method: 'POST' })
    await Promise.all([load(), loadDetail()])
  } catch (failure) {
    detailError.value = (failure as Error).message
  } finally {
    acknowledging.value = false
  }
}
function evidenceLabel(item: Evidence) {
  if (item.reason === 'HEARTBEAT_TIMEOUT') return '心跳超时'
  return (
    (
      {
        DEVICE_FAULT: '设备故障',
        DEVICE_RECOVERED: '显式恢复',
        HEARTBEAT: '恢复心跳',
        SENSOR_READING: '传感器上报',
      } as Record<string, string>
    )[item.reason] ?? item.reason
  )
}
</script>

<template>
  <section class="business-page alarm-page">
    <PageHeader
      ><span class="live-status" :class="live"
        ><span></span>{{ live === 'connected' ? '实时连接已建立' : '正在连接实时通知' }}</span
      ></PageHeader
    >
    <div class="metrics-grid">
      <article v-for="item in metrics" :key="item.name" class="metric-card">
        <span class="metric-symbol" :class="item.tone"
          ><component :is="item.icon" :size="22"
        /></span>
        <span
          ><span class="metric-label">{{ item.name }}</span
          ><strong>{{ item.value ?? '—' }}</strong></span
        >
      </article>
    </div>
    <form class="filter-toolbar alarm-filters" @submit.prevent="search">
      <label
        >严重级别<select v-model="filter.severity">
          <option value="">全部级别</option>
          <option v-for="(label, key) in severityLabels" :key="key" :value="key">
            {{ label }}
          </option>
        </select></label
      >
      <label
        >所属楼宇<select v-model="filter.buildingId">
          <option value="">全部楼宇</option>
          <option v-for="item in buildings" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select></label
      >
      <label
        >告警类型<select v-model="filter.alarmType">
          <option value="">全部类型</option>
          <option v-for="(label, key) in alarmTypes" :key="key" :value="key">{{ label }}</option>
        </select></label
      >
      <label
        >处理状态<select v-model="filter.status">
          <option value="">全部状态</option>
          <option v-for="(label, key) in alarmStatuses" :key="key" :value="key">{{ label }}</option>
        </select></label
      >
      <div class="filter-actions">
        <ElButton native-type="submit" type="primary"><Search :size="15" />查询</ElButton
        ><ElButton @click="reset">重置</ElButton>
      </div>
    </form>
    <div v-if="error" class="request-error" role="alert">
      {{ error }} <ElButton link type="primary" @click="load">重试</ElButton>
    </div>
    <div class="alarm-columns" :class="{ 'with-detail': selected }">
      <div class="table-panel" :aria-busy="loading">
        <div class="panel-heading">
          <h2>
            告警列表 <span>{{ page.total }}</span>
          </h2>
          <div class="alarm-view-options">
            <button :aria-pressed="route.query.mainOnly !== 'false'" @click="toggleMain(false)">
              主告警
            </button>
            <button :aria-pressed="route.query.mainOnly === 'false'" @click="toggleMain(true)">
              全部
            </button>
            <ElButton text aria-label="刷新告警" @click="load"><RefreshCw :size="15" /></ElButton>
          </div>
        </div>
        <ElTable :data="page.items" row-key="id" empty-text="暂无匹配的告警" max-height="540px">
          <ElTableColumn label="告警编号" width="109">
            <template #default="{ row }"
              ><button class="text-link mono" @click="select(row.id)">
                {{ row.alarmNo }}
              </button></template
            >
          </ElTableColumn>
          <ElTableColumn label="告警标题" min-width="168">
            <template #default="{ row }"
              ><button class="text-link alarm-row-title" @click="select(row.id)">
                {{ alarmTitle(row as Alarm) }}</button
              ><small class="cell-secondary"
                >{{ relations[(row as Alarm).relationKind]
                }}{{ row.childCount ? ` · ${row.childCount} 条子告警` : '' }}</small
              ></template
            >
          </ElTableColumn>
          <ElTableColumn label="级别" width="67"
            ><template #default="{ row }"
              ><span class="alarm-chip" :class="row.severity.toLowerCase()">{{
                severityLabels[(row as Alarm).severity]
              }}</span></template
            ></ElTableColumn
          >
          <ElTableColumn label="状态" width="86"
            ><template #default="{ row }"
              ><span class="alarm-chip" :class="row.status.toLowerCase()">{{
                alarmStatuses[(row as Alarm).status]
              }}</span></template
            ></ElTableColumn
          >
          <ElTableColumn v-if="!selected" label="最后更新" width="167"
            ><template #default="{ row }"
              ><span class="mono time-cell">{{ formatTime(row.lastSeenAt) }}</span></template
            ></ElTableColumn
          >
        </ElTable>
        <div class="table-footer">
          <span>共 {{ page.total }} 条</span
          ><ElPagination
            :current-page="page.page"
            :page-size="page.size"
            :total="page.total"
            layout="prev, pager, next"
            @current-change="changePage"
          />
        </div>
      </div>
      <aside v-if="selected" class="alarm-detail" aria-label="告警详情">
        <header class="panel-heading">
          <h2><Layers3 :size="18" /> 告警详情</h2>
          <ElButton text aria-label="关闭告警详情" @click="select()"><X :size="18" /></ElButton>
        </header>
        <p v-if="detailError" class="request-error" role="alert">{{ detailError }}</p>
        <p v-else-if="!detail" class="alarm-pad">正在读取告警</p>
        <template v-if="detail">
          <div class="alarm-detail-head">
            <span class="mono">{{ detail.alarm.alarmNo }}</span>
            <h3>{{ alarmTitle(detail.alarm) }}</h3>
            <div class="alarm-labels">
              <span class="alarm-chip" :class="detail.alarm.severity.toLowerCase()">{{
                severityLabels[detail.alarm.severity]
              }}</span>
              <span class="alarm-chip" :class="detail.alarm.status.toLowerCase()">{{
                alarmStatuses[detail.alarm.status]
              }}</span>
              <span
                class="alarm-chip"
                :class="detail.alarm.relationKind === 'SUSPECTED_UPSTREAM' ? 'warning' : 'network'"
                >{{ relations[detail.alarm.relationKind] }}</span
              >
            </div>
            <div class="alarm-action-row">
              <RouterLink
                v-if="linkedOrder"
                class="text-link"
                :to="{ path: '/workorders', query: { id: linkedOrder.id } }"
                >查看工单 {{ linkedOrder.orderNo }} ·
                {{ workStatuses[linkedOrder.status] }}</RouterLink
              >
              <ElButton
                v-else-if="linkedOrder === null && !detail.alarm.parentAlarmId"
                :loading="creatingOrder"
                @click="createWorkOrder"
                >创建工单</ElButton
              >
              <ElButton
                v-if="detail.alarm.status === 'OPEN' && !detail.alarm.parentAlarmId"
                type="primary"
                :loading="acknowledging"
                @click="acknowledge"
                >确认告警</ElButton
              >
              <RouterLink
                v-if="detail.alarm.rootNetworkNodeId"
                class="text-link"
                :to="{ path: '/topology', query: { nodeId: detail.alarm.rootNetworkNodeId } }"
                >定位拓扑</RouterLink
              >
              <button
                v-if="detail.alarm.parentAlarmId"
                class="text-link"
                @click="select(detail.alarm.parentAlarmId)"
              >
                查看主告警
              </button>
            </div>
            <p v-if="workError" class="work-note" role="alert">
              {{ workError }}
              <button class="text-link" @click="loadLinkedOrder(selected)">重试</button>
            </p>
          </div>
          <div class="alarm-root" :class="detail.alarm.relationKind.toLowerCase()">
            <Network :size="20" />
            <div>
              <strong>{{ relations[detail.alarm.relationKind] }}</strong>
              <p v-if="detail.alarm.relationKind === 'CONFIRMED_UPSTREAM'">
                交换机主动上报故障，且直属下游在关联窗口内超时离线。
              </p>
              <p v-else-if="detail.alarm.relationKind === 'SUSPECTED_UPSTREAM'">
                多个直属下游集中离线，仍待核实上游交换机故障证据。
              </p>
              <p v-else>由本设备的上报或心跳状态触发。</p>
            </div>
          </div>
          <dl class="alarm-times">
            <div>
              <dt>首次触发</dt>
              <dd>{{ formatTime(detail.alarm.firstSeenAt) }}</dd>
            </div>
            <div>
              <dt>最后更新</dt>
              <dd>{{ formatTime(detail.alarm.lastSeenAt) }}</dd>
            </div>
            <div v-if="detail.alarm.acknowledgedAt">
              <dt>确认人</dt>
              <dd>
                {{ detail.alarm.acknowledgedBy }} · {{ formatTime(detail.alarm.acknowledgedAt) }}
              </dd>
            </div>
            <div v-if="detail.alarm.recoveredAt">
              <dt>恢复时间</dt>
              <dd>{{ formatTime(detail.alarm.recoveredAt) }}</dd>
            </div>
          </dl>
          <div class="alarm-tabs" aria-label="详情分区">
            <button
              v-for="(label, key) in sections"
              :key="key"
              :aria-pressed="tab === key"
              @click="tab = key"
            >
              {{ label }}
            </button>
          </div>
          <div v-if="tab === 'children'" class="alarm-pad">
            <ul class="alarm-items">
              <li v-for="item in detail.children.items" :key="item.id">
                <button class="text-link" @click="select(item.id)">
                  {{ alarmTitle(item) }}</button><span
                  class="alarm-chip"
                  :class="item.status.toLowerCase()"
                  >{{ alarmStatuses[item.status] }}</span
                >
              </li>
            </ul>
            <p v-if="!detail.children.total">暂无子告警</p>
            <ElPagination
              v-if="detail.children.total > detail.children.size"
              small
              :current-page="detailPages.children"
              :page-size="detail.children.size"
              :total="detail.children.total"
              layout="prev, pager, next"
              @current-change="detailPage('children', $event)"
            />
          </div>
          <div v-else-if="tab === 'devices'" class="alarm-pad">
            <ul class="alarm-items">
              <li v-for="item in detail.devices.items" :key="item.deviceId">
                <RouterLink
                  class="text-link"
                  :to="{ path: '/topology', query: { nodeId: item.deviceId } }"
                  >{{ item.deviceName }}</RouterLink><span>{{
                  item.impactKind === 'ROOT' ? '上游节点' : '受影响设备'
                }}</span>
              </li>
            </ul>
            <ElPagination
              v-if="detail.devices.total > detail.devices.size"
              small
              :current-page="detailPages.devices"
              :page-size="detail.devices.size"
              :total="detail.devices.total"
              layout="prev, pager, next"
              @current-change="detailPage('devices', $event)"
            />
          </div>
          <div v-else-if="tab === 'evidence'" class="alarm-pad">
            <ul class="alarm-evidence">
              <li
                v-for="(item, index) in detail.evidence.items"
                :key="`${item.eventId}:${item.evidenceType}:${index}`"
              >
                <button
                  class="text-link"
                  @click="
                    expanded =
                      expanded === `${item.eventId}:${index}` ? '' : `${item.eventId}:${index}`
                  "
                >
                  {{ evidenceTypes[item.evidenceType] }} · {{ evidenceLabel(item) }}
                </button>
                <small>{{ formatTime(item.occurredAt) }} · 拓扑 v{{ item.topologyVersion }}</small>
                <div v-if="expanded === `${item.eventId}:${index}`">
                  <p class="mono">Trace ID {{ item.traceId }}</p>
                  <RouterLink
                    v-if="item.reason !== 'HEARTBEAT_TIMEOUT'"
                    class="text-link"
                    :to="{ path: '/events', query: { id: item.causeEventId } }"
                    >打开原始 MQTT 事件</RouterLink
                  >
                  <pre class="json-view">{{ JSON.stringify(item.payload, null, 2) }}</pre>
                </div>
              </li>
            </ul>
            <ElPagination
              v-if="detail.evidence.total > detail.evidence.size"
              small
              :current-page="detailPages.evidence"
              :page-size="detail.evidence.size"
              :total="detail.evidence.total"
              layout="prev, pager, next"
              @current-change="detailPage('evidence', $event)"
            />
          </div>
          <div v-else class="alarm-pad">
            <p v-if="rules">
              同一直属上游在 {{ rules.topologyWindowSeconds }} 秒内有至少
              {{ rules.minimumDownstream }} 台设备集中离线时，可形成疑似告警。温度告警在
              {{ rules.temperatureLimit }}℃ 触发，降至 {{ rules.temperatureRecovery }}℃ 时恢复。
            </p>
            <p v-if="rules">设备抖动冷却窗口 {{ rules.flapCooldownSeconds }} 秒。</p>
            <p>该告警已抑制 {{ detail.alarm.suppressedCount }} 次重复触发，证据仍可查看。</p>
          </div>
        </template>
      </aside>
    </div>
  </section>
</template>
