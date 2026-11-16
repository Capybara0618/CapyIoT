<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElButton,
  ElDrawer,
  ElInput,
  ElMessage,
  ElOption,
  ElPagination,
  ElSelect,
  ElTable,
  ElTableColumn,
} from 'element-plus'
import { Pause, Play, RefreshCw, Search, Copy, RotateCcw } from 'lucide-vue-next'
import { request } from '../api'
import { eventStatuses, eventTypes, formatTime, type DeviceEvent, type EventPage } from '../domain'
import { useLiveUpdates } from '../live'
import PageHeader from '../components/PageHeader.vue'
import EmptyState from '../components/EmptyState.vue'
import StatusBadge from '../components/StatusBadge.vue'

const route = useRoute()
const router = useRouter()
const items = ref<DeviceEvent[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const paused = ref(false)
const detail = ref<DeviceEvent | null>(null)
const detailOpen = ref(false)
const cursor = ref<string | null>(null)
const filters = reactive({
  deviceId: '',
  eventType: '',
  status: '',
  from: '',
  to: '',
  page: 1,
  size: 50,
})
const historical = computed(() => filters.page > 1 || !!filters.to)
let version = 0
let timer: ReturnType<typeof setTimeout> | undefined
let syncing = false
let pending = false
let stopped = false

function parameters() {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(filters)) {
    if (value === '') continue
    query.set(
      key,
      key === 'from' || key === 'to' ? new Date(String(value)).toISOString() : String(value),
    )
  }
  return query
}

async function load() {
  const current = ++version
  loading.value = true
  error.value = ''
  cursor.value = null
  try {
    const result = await request<EventPage>(`/events?${parameters()}`)
    if (current !== version || stopped) return
    items.value = result.items
    total.value = result.total
    cursor.value = result.resumeCursor
  } catch (cause) {
    if (current === version) error.value = (cause as Error).message
  } finally {
    if (current === version) {
      loading.value = false
      if (pending) scheduleSync()
    }
  }
}

async function sync() {
  if (syncing || loading.value || paused.value || historical.value || !cursor.value || stopped) {
    pending = true
    return
  }
  syncing = true
  const current = version
  try {
    do {
      pending = false
      const query = parameters()
      query.set('cursor', cursor.value!)
      query.set('size', '100')
      const result = await request<EventPage>(`/events?${query}`)
      if (current !== version || stopped || paused.value) {
        pending = true
        return
      }
      const merged = new Map(items.value.map((event) => [event.eventId, event]))
      for (const event of result.items) merged.set(event.eventId, event)
      items.value = [...merged.values()].sort((first, second) => second.id - first.id).slice(0, 500)
      total.value = result.total
      cursor.value = result.nextCursor
      pending = pending || result.hasMore
    } while (pending && !paused.value && !stopped)
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    syncing = false
  }
}

function scheduleSync() {
  pending = true
  clearTimeout(timer)
  timer = setTimeout(() => {
    void sync()
  }, 250)
}
const live = useLiveUpdates(scheduleSync)

watch(
  () => route.query,
  (query) => {
    for (const key of ['deviceId', 'eventType', 'status', 'from', 'to'] as const)
      filters[key] = String(query[key] ?? '')
    filters.page = Number(query.page ?? 1)
    void load()
    if (query.id) {
      void request<DeviceEvent>(`/events/${encodeURIComponent(String(query.id))}`)
        .then((event) => {
          detail.value = event
          detailOpen.value = true
        })
        .catch((cause: Error) => ElMessage.error(cause.message))
    } else detailOpen.value = false
  },
  { immediate: true },
)

async function search(page = 1) {
  const query: Record<string, string> = {}
  for (const [key, value] of Object.entries({ ...filters, page }))
    if (value !== '') query[key] = String(value)
  await router.replace({ query })
  await load()
}
function reset() {
  Object.assign(filters, { deviceId: '', eventType: '', status: '', from: '', to: '', page: 1 })
  void search()
}
function togglePause() {
  paused.value = !paused.value
  if (!paused.value) scheduleSync()
}
function openDetail(event: DeviceEvent) {
  void router.replace({ query: { ...route.query, id: event.eventId } })
}
function closeDetail() {
  if (route.query.id) {
    const query = { ...route.query }
    delete query.id
    void router.replace({ query })
  }
}
async function copy(value: string) {
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success('已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择内容')
  }
}
onBeforeUnmount(() => {
  stopped = true
  version++
  clearTimeout(timer)
})
</script>

<template>
  <section class="business-page">
    <PageHeader
      ><span class="live-status" :class="live"
        ><span></span
        >{{
          live === 'connected'
            ? '实时连接已建立'
            : live === 'connecting'
              ? '正在连接实时通知'
              : '实时通知断开，正在重连'
        }}</span
      ></PageHeader
    >
    <form class="filter-toolbar events-filters" @submit.prevent="search()">
      <label
        >设备 ID<ElInput v-model="filters.deviceId" placeholder="按设备 ID 筛选" clearable
      /></label>
      <label
        >事件类型<ElSelect v-model="filters.eventType" clearable placeholder="全部事件"
          ><ElOption
            v-for="(label, value) in eventTypes"
            :key="value"
            :label="label"
            :value="value" /></ElSelect
      ></label>
      <label
        >处理状态<ElSelect v-model="filters.status" clearable placeholder="全部状态"
          ><ElOption
            v-for="(label, value) in eventStatuses"
            :key="value"
            :label="label"
            :value="value" /></ElSelect
      ></label>
      <label
        >开始时间<input
          v-model="filters.from"
          type="datetime-local"
          class="time-input"
          aria-label="开始时间"
      /></label>
      <label
        >结束时间<input
          v-model="filters.to"
          type="datetime-local"
          class="time-input"
          aria-label="结束时间"
      /></label>
      <div class="filter-actions">
        <ElButton type="primary" native-type="submit"><Search :size="15" />查询</ElButton
        ><ElButton @click="reset"><RotateCcw :size="15" />重置</ElButton>
      </div>
    </form>
    <div v-if="error" class="request-error" role="alert">
      {{ error }}<ElButton link type="primary" @click="load">重新加载</ElButton>
    </div>
    <div class="table-panel" :aria-busy="loading">
      <div class="panel-heading">
        <h2>
          设备事件 <span>{{ total }}</span>
        </h2>
        <div class="panel-actions">
          <span class="table-hint">{{
            historical ? '历史查询' : paused ? '已暂停前端追加' : '实时缓冲，最多500条'
          }}</span><ElButton v-if="!historical" @click="togglePause"
            ><component :is="paused ? Play : Pause" :size="15" />{{
              paused ? '恢复追加' : '暂停追加'
            }}</ElButton
          ><ElButton text :loading="loading" @click="load"><RefreshCw :size="15" />刷新</ElButton>
        </div>
      </div>
      <ElTable
        :data="items"
        row-key="eventId"
        max-height="max(300px, calc(100vh - 460px))"
        @row-dblclick="openDetail"
      >
        <ElTableColumn label="接收时间" min-width="172"
          ><template #default="{ row }"
            ><span class="mono time-cell">{{ formatTime(row.receivedAt) }}</span></template
          ></ElTableColumn
        >
        <ElTableColumn label="事件 ID" min-width="155"
          ><template #default="{ row }"
            ><button
              class="text-link mono"
              :title="row.eventId"
              @click="openDetail(row as DeviceEvent)"
            >
              {{ row.eventId.slice(0, 14) }}…
            </button></template
          ></ElTableColumn
        >
        <ElTableColumn label="设备" min-width="168"
          ><template #default="{ row }"
            ><span>{{ row.deviceName }}</span
            ><small class="cell-secondary mono">{{ row.deviceCode }}</small></template
          ></ElTableColumn
        >
        <ElTableColumn label="事件类型" min-width="115"
          ><template #default="{ row }">{{
            eventTypes[row.eventType as keyof typeof eventTypes] ?? row.eventType
          }}</template></ElTableColumn
        >
        <ElTableColumn prop="source" label="来源" width="90" />
        <ElTableColumn label="处理状态" min-width="122"
          ><template #default="{ row }"><StatusBadge :value="row.processingStatus" /></template
        ></ElTableColumn>
        <ElTableColumn label="Trace ID" min-width="148"
          ><template #default="{ row }"
            ><button
              class="text-link mono muted-link"
              :title="row.traceId"
              @click="copy(row.traceId)"
            >
              {{ row.traceId.slice(0, 12) }}…
            </button></template
          ></ElTableColumn
        >
        <ElTableColumn label="操作" width="70" fixed="right"
          ><template #default="{ row }"
            ><ElButton link type="primary" @click="openDetail(row as DeviceEvent)"
              >详情</ElButton
            ></template
          ></ElTableColumn
        >
        <template #empty
          ><EmptyState
            title="尚无匹配事件"
            description="启动设备模拟器后，接收与处理结果会在这里显示。"
        /></template>
      </ElTable>
      <div class="table-footer">
        <span>{{ historical ? '历史事件查询' : `当前缓冲 ${items.length} 条` }}</span><ElPagination
          :current-page="filters.page"
          :page-size="filters.size"
          :total="total"
          layout="prev, pager, next"
          @current-change="search"
        />
      </div>
    </div>
    <ElDrawer v-model="detailOpen" title="事件详情" size="min(560px, 94vw)" @closed="closeDetail">
      <template v-if="detail"
        ><div class="detail-identity">
          <div>
            <h2>
              {{ eventTypes[detail.eventType as keyof typeof eventTypes] ?? detail.eventType }}
            </h2>
            <p>{{ detail.deviceName }} · {{ detail.deviceCode }}</p>
          </div>
          <StatusBadge :value="detail.processingStatus" />
        </div>
        <dl class="detail-grid">
          <dt>事件 ID</dt>
          <dd class="copy-value">
            <span class="mono">{{ detail.eventId }}</span><ElButton
              text
              aria-label="复制事件 ID"
              @click="copy(detail.eventId)"
              ><Copy :size="15"
            /></ElButton>
          </dd>
          <dt>发生时间</dt>
          <dd>{{ formatTime(detail.occurredAt) }}</dd>
          <dt>接收时间</dt>
          <dd>{{ formatTime(detail.receivedAt) }}</dd>
          <dt>Trace ID</dt>
          <dd class="copy-value">
            <span class="mono">{{ detail.traceId }}</span><ElButton
              text
              aria-label="复制 Trace ID"
              @click="copy(detail.traceId)"
              ><Copy :size="15"
            /></ElButton>
          </dd>
          <dt>设备 ID</dt>
          <dd class="mono">{{ detail.deviceId }}</dd>
          <dt>会话版本 / 序号</dt>
          <dd>{{ detail.sessionEpoch }} / {{ detail.seq }}</dd>
        </dl>
        <h3 class="detail-section-title">事件数据</h3>
        <pre class="json-view">{{ JSON.stringify(detail.payload, null, 2) }}</pre>
      </template>
    </ElDrawer>
  </section>
</template>
