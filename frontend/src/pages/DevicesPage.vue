<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ElButton,
  ElDialog,
  ElDrawer,
  ElForm,
  ElFormItem,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElPagination,
  ElSelect,
  ElSwitch,
  ElTable,
  ElTableColumn,
  ElTabPane,
  ElTabs,
  type FormInstance,
  type FormRules,
} from 'element-plus'
import {
  Plus,
  Search,
  RotateCcw,
  HardDrive,
  CircleCheck,
  CircleX,
  TriangleAlert,
  RefreshCw,
} from 'lucide-vue-next'
import { ApiError, request } from '../api'
import { alarmTitle, type Alarm, type ImpactMarker } from '../alarms'
import { workStream, type WorkOrder } from '../workorders'
import { session } from '../session'
import { useLiveUpdates } from '../live'
import {
  deviceTypes,
  eventTypes,
  formatTime,
  type Device,
  type DeviceEvent,
  type DeviceType,
  type EventPage,
  type Locations,
  type Page,
} from '../domain'
import PageHeader from '../components/PageHeader.vue'
import StatusBadge from '../components/StatusBadge.vue'
import EmptyState from '../components/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const admin = computed(() => session.value?.user.role === 'ADMIN')
const devices = ref<Device[]>([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const locations = ref<Locations>({
  campus: { id: '', name: '' },
  buildings: [],
  floors: [],
  areas: [],
})
const switches = ref<Device[]>([])
const summary = ref({ total: 0, online: 0, offline: 0, abnormal: 0 })
const summaryLoaded = ref(false)
const impacts = ref<Map<string, number> | null>(null)
const filters = reactive({
  keyword: '',
  type: '',
  buildingId: '',
  connectivity: '',
  health: '',
  page: 1,
  size: 8,
})
const detail = ref<Device | null>(null)
const history = ref<DeviceEvent[]>([])
const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detailTab = ref('basic')
const related = ref<{ alarm: Alarm; order: WorkOrder | null }[]>([])
const relationsLoading = ref(false)
const relationsError = ref('')
const dialogOpen = ref(false)
const saving = ref(false)
const editing = ref<Device | null>(null)
const form = ref<FormInstance>()
const credential = ref('')
const credentialOpen = ref(false)
const draft = reactive({
  deviceCode: '',
  name: '',
  type: 'CAMERA' as DeviceType,
  buildingId: '',
  floorId: '',
  areaId: '',
  upstreamNodeId: '',
  enabled: true,
})
const floors = computed(() =>
  locations.value.floors.filter((floor) => floor.buildingId === draft.buildingId),
)
const areas = computed(() => locations.value.areas.filter((area) => area.floorId === draft.floorId))
const rules: FormRules = Object.fromEntries(
  ['deviceCode', 'name', 'type', 'buildingId', 'floorId'].map((field) => [
    field,
    [{ required: true, message: '请填写此项', trigger: 'blur' }],
  ]),
)
rules.deviceCode = [
  {
    required: true,
    pattern: /^[A-Z0-9][A-Z0-9_-]{2,63}$/,
    message: '使用3–64位大写字母、数字、下划线或连字符',
    trigger: 'blur',
  },
]
let loadVersion = 0
let detailVersion = 0
let relationsVersion = 0
let refreshTimer: ReturnType<typeof setTimeout> | undefined

async function load() {
  const version = ++loadVersion
  loading.value = true
  error.value = ''
  try {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(filters))
      if (value !== '') query.set(key, String(value))
    const [page, counts] = await Promise.all([
      request<Page<Device>>(`/devices?${query}`),
      request<typeof summary.value>('/devices/summary'),
    ])
    if (version !== loadVersion) return
    devices.value = page.items
    total.value = page.total
    summary.value = counts
    summaryLoaded.value = true
    void loadImpacts(page.items, version)
  } catch (cause) {
    if (version === loadVersion) error.value = (cause as Error).message
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

async function loadImpacts(items: Device[], version: number) {
  try {
    const found = items.length
      ? await request<ImpactMarker[]>(
          `/alarms/impact?${new URLSearchParams({ deviceIds: items.map(({ id }) => id).join(',') })}`,
        )
      : []
    if (version !== loadVersion) return
    const linked = new Map<string, number>()
    for (const marker of found) {
      if (!linked.has(marker.deviceId)) linked.set(marker.deviceId, marker.alarmId)
    }
    impacts.value = linked
  } catch {
    if (version === loadVersion) impacts.value = null
  }
}

async function loadRelations(id: string, quiet = false) {
  const version = ++relationsVersion
  if (!quiet) relationsLoading.value = true
  try {
    const alarms = await request<Alarm[]>(`/alarms/by-device/${encodeURIComponent(id)}`)
    const results = await Promise.all(
      alarms.map(async (alarm) => {
        try {
          return { alarm, order: await request<WorkOrder>(`/workorders/by-alarm/${alarm.id}`) }
        } catch (failure) {
          if (failure instanceof ApiError && failure.status === 404) return { alarm, order: null }
          throw failure
        }
      }),
    )
    if (version !== relationsVersion) return
    related.value = results
    relationsError.value = ''
  } catch (failure) {
    if (version === relationsVersion) relationsError.value = (failure as Error).message
  } finally {
    if (version === relationsVersion) relationsLoading.value = false
  }
}

async function loadDetail(id: string, quiet = false) {
  const version = ++detailVersion
  detailOpen.value = true
  detailLoading.value = !quiet
  detailError.value = ''
  try {
    const [device, events] = await Promise.all([
      request<Device>(`/devices/${id}`),
      request<EventPage>(`/devices/${id}/events?size=10`),
    ])
    if (version !== detailVersion) return
    detail.value = device
    history.value = events.items
  } catch (cause) {
    if (version === detailVersion) detailError.value = (cause as Error).message
  } finally {
    if (version === detailVersion) detailLoading.value = false
  }
}

function queueRefresh() {
  if (refreshTimer !== undefined) return
  refreshTimer = setTimeout(() => {
    refreshTimer = undefined
    void load()
    const id = route.query.id
    if (detailOpen.value && typeof id === 'string') void loadDetail(id, detail.value?.id === id)
  }, 300)
}
const live = useLiveUpdates(queueRefresh)
function refreshRelations() {
  const id = route.query.id
  if (detailOpen.value && typeof id === 'string' && detailTab.value === 'relations')
    void loadRelations(id, true)
}
useLiveUpdates(() => {
  void loadImpacts(devices.value, loadVersion)
  refreshRelations()
}, '/api/v1/alarms/stream')
useLiveUpdates(refreshRelations, workStream)
watch(detailTab, (tab) => {
  if (tab === 'relations' && detail.value) void loadRelations(detail.value.id)
})

watch(
  () => route.query,
  (query) => {
    for (const key of ['keyword', 'type', 'buildingId', 'connectivity', 'health'] as const)
      filters[key] = String(query[key] ?? '')
    filters.page = Number(query.page ?? 1)
    filters.size = Number(query.size ?? 8)
    void load()
    if (query.id) {
      if (String(query.id) !== detail.value?.id) {
        detailTab.value = 'basic'
        relationsVersion++
        detail.value = null
        related.value = []
      }
      void loadDetail(String(query.id))
    } else {
      detailOpen.value = false
      detailVersion++
      relationsVersion++
    }
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
function changeBuilding() {
  draft.floorId = ''
  draft.areaId = ''
}
function reset() {
  Object.assign(filters, {
    keyword: '',
    type: '',
    buildingId: '',
    connectivity: '',
    health: '',
    page: 1,
    size: 8,
  })
  void search()
}
function selectMetric(key: string) {
  if (key === 'total') {
    reset()
    return
  }
  filters.connectivity = key === 'online' ? 'ONLINE' : key === 'offline' ? 'OFFLINE' : ''
  filters.health = key === 'abnormal' ? 'ABNORMAL' : ''
  void search()
}
function openDetail(device: Device) {
  void router.replace({ query: { ...route.query, id: device.id } })
}
function closeDetail() {
  if (route.query.id) {
    const query = { ...route.query }
    delete query.id
    void router.replace({ query })
  }
}

async function openForm(device: Device | null = null) {
  editing.value = device
  Object.assign(
    draft,
    device
      ? {
          deviceCode: device.deviceCode,
          name: device.name,
          type: device.type,
          buildingId: device.buildingId,
          floorId: device.floorId,
          areaId: device.areaId ?? '',
          upstreamNodeId: device.upstreamNodeId ?? '',
          enabled: device.enabled,
        }
      : {
          deviceCode: '',
          name: '',
          type: 'CAMERA',
          buildingId: '',
          floorId: '',
          areaId: '',
          upstreamNodeId: '',
          enabled: true,
        },
  )
  dialogOpen.value = true
  try {
    switches.value = (await request<Page<Device>>('/devices?type=SWITCH&size=100')).items
  } catch (cause) {
    ElMessage.error((cause as Error).message)
  }
  form.value?.clearValidate()
}

async function save() {
  if (!(await form.value?.validate().catch(() => false))) return
  if (editing.value && editing.value.enabled !== draft.enabled) {
    try {
      await ElMessageBox.confirm(`确认${draft.enabled ? '启用' : '停用'}此设备？`, '确认配置变更', {
        confirmButtonText: '确认',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
  }
  saving.value = true
  try {
    if (editing.value) {
      await request(`/devices/${editing.value.id}`, {
        method: 'PATCH',
        body: JSON.stringify({ ...draft, expectedVersion: editing.value.version }),
      })
      ElMessage.success('设备配置已更新')
      if (detail.value?.id === editing.value.id) void loadDetail(editing.value.id)
    } else {
      const result = await request<{ device: Device; deviceSecret: string }>('/devices', {
        method: 'POST',
        body: JSON.stringify(draft),
      })
      credential.value = result.deviceSecret
      credentialOpen.value = true
    }
    dialogOpen.value = false
    await load()
  } catch (cause) {
    ElMessage.error((cause as Error).message)
  } finally {
    saving.value = false
  }
}

async function copyCredential() {
  try {
    await navigator.clipboard.writeText(credential.value)
    ElMessage.success('设备凭据已复制')
  } catch {
    ElMessage.error('复制失败，请手动选择凭据')
  }
}

onMounted(async () => {
  try {
    locations.value = await request<Locations>('/locations')
  } catch (cause) {
    error.value = (cause as Error).message
  }
})
onBeforeUnmount(() => {
  clearTimeout(refreshTimer)
  loadVersion++
  detailVersion++
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
      ><ElButton v-if="admin" type="primary" @click="openForm()"
        ><Plus :size="16" />注册设备</ElButton
      ></PageHeader
    >
    <div class="metrics-grid">
      <button
        v-for="metric in [
          { key: 'total', label: '设备总数', icon: HardDrive, tone: 'blue' },
          { key: 'online', label: '在线设备', icon: CircleCheck, tone: 'green' },
          { key: 'offline', label: '离线设备', icon: CircleX, tone: 'red' },
          { key: 'abnormal', label: '异常设备', icon: TriangleAlert, tone: 'orange' },
        ]"
        :key="metric.key"
        class="metric-card"
        @click="selectMetric(metric.key)"
      >
        <span class="metric-symbol" :class="metric.tone"
          ><component :is="metric.icon" :size="22" /></span
        ><span
          ><span class="metric-label">{{ metric.label }}</span
          ><strong>{{
            summaryLoaded ? summary[metric.key as keyof typeof summary] : '—'
          }}</strong></span
        >
      </button>
    </div>
    <form class="filter-toolbar" @submit.prevent="search()">
      <label
        >设备名称或编号<ElInput
          v-model="filters.keyword"
          clearable
          placeholder="搜索名称或编号"
          maxlength="100"
      /></label>
      <label
        >设备类型<ElSelect v-model="filters.type" placeholder="全部类型" clearable
          ><ElOption
            v-for="(label, value) in deviceTypes"
            :key="value"
            :label="label"
            :value="value" /></ElSelect
      ></label>
      <label
        >所属楼宇<ElSelect v-model="filters.buildingId" placeholder="全部楼宇" clearable
          ><ElOption
            v-for="building in locations.buildings"
            :key="building.id"
            :label="building.name"
            :value="building.id" /></ElSelect
      ></label>
      <label
        >连接状态<ElSelect v-model="filters.connectivity" placeholder="全部状态" clearable
          ><ElOption label="在线" value="ONLINE" /><ElOption
            label="离线 / 待上报"
            value="OFFLINE" /></ElSelect
      ></label>
      <label
        >健康状态<ElSelect v-model="filters.health" placeholder="全部状态" clearable
          ><ElOption label="正常" value="NORMAL" /><ElOption
            label="异常"
            value="ABNORMAL" /></ElSelect
      ></label>
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
          设备列表 <span>{{ total }}</span>
        </h2>
        <ElButton text :loading="loading" @click="load"><RefreshCw :size="15" />刷新</ElButton>
      </div>
      <ElTable
        :data="devices"
        row-key="id"
        empty-text="尚未注册设备"
        class="asset-table"
        max-height="max(300px, calc(100vh - 530px))"
        @row-dblclick="openDetail"
      >
        <ElTableColumn label="设备编号" min-width="132"
          ><template #default="{ row }"
            ><button class="text-link mono" @click="openDetail(row as Device)">
              {{ row.deviceCode }}
            </button></template
          ></ElTableColumn
        >
        <ElTableColumn label="名称" min-width="160"
          ><template #default="{ row }"
            ><span>{{ row.name }}</span
            ><span v-if="!row.enabled" class="muted-tag">已停用</span></template
          ></ElTableColumn
        >
        <ElTableColumn label="类型" min-width="104"
          ><template #default="{ row }">{{
            deviceTypes[row.type as DeviceType]
          }}</template></ElTableColumn
        >
        <ElTableColumn label="所属位置" min-width="120"
          ><template #default="{ row }"
            >{{ row.buildingName }} / {{ row.floorName
            }}<small class="cell-secondary">{{ row.areaName ?? '未分配区域' }}</small></template
          ></ElTableColumn
        >
        <ElTableColumn prop="upstreamNodeName" label="上游节点" min-width="150"
          ><template #default="{ row }">{{
            row.upstreamNodeName ?? '未配置'
          }}</template></ElTableColumn
        >
        <ElTableColumn label="连接状态" min-width="88"
          ><template #default="{ row }"
            ><StatusBadge
              :value="row.lastHeartbeatAt ? row.connectivityStatus : 'UNKNOWN'" /></template
        ></ElTableColumn>
        <ElTableColumn label="健康状态" min-width="88"
          ><template #default="{ row }"><StatusBadge :value="row.healthStatus" /></template
        ></ElTableColumn>
        <ElTableColumn label="最后心跳" min-width="164"
          ><template #default="{ row }"
            ><span class="mono time-cell">{{ formatTime(row.lastHeartbeatAt) }}</span></template
          ></ElTableColumn
        >
        <ElTableColumn label="关联告警" min-width="118">
          <template #default="{ row }">
            <RouterLink
              v-if="impacts?.has(row.id)"
              class="text-link"
              :to="{ path: '/alarms', query: { id: impacts.get(row.id) } }"
              >查看活跃告警</RouterLink
            >
            <span v-else class="cell-secondary">{{ impacts ? '无活跃告警' : '—' }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="110" fixed="right"
          ><template #default="{ row }"
            ><ElButton link type="primary" @click="openDetail(row as Device)">详情</ElButton
            ><ElButton v-if="admin" link type="primary" @click="openForm(row as Device)"
              >编辑</ElButton
            ></template
          ></ElTableColumn
        >
        <template #empty
          ><EmptyState title="尚无匹配设备" description="调整筛选条件，或注册设备开始接入。"
        /></template>
      </ElTable>
      <div class="table-footer">
        <span>共 {{ total }} 台设备</span
        ><ElPagination
          :current-page="filters.page"
          :page-size="filters.size"
          :total="total"
          layout="prev, pager, next"
          @current-change="search"
        />
      </div>
    </div>
    <ElDrawer v-model="detailOpen" title="设备详情" size="min(540px, 94vw)" @closed="closeDetail">
      <p v-if="detailLoading" class="drawer-status" role="status">正在加载设备详情…</p>
      <p v-if="detailError" class="request-error" role="alert">{{ detailError }}</p>
      <template v-if="detail && !detailLoading && !detailError">
        <div class="detail-identity">
          <span class="metric-symbol blue"><HardDrive :size="25" /></span>
          <div>
            <h2>{{ detail.name }}</h2>
            <p class="mono">{{ detail.deviceCode }}</p>
          </div>
        </div>
        <div class="detail-badges">
          <StatusBadge
            :value="detail.lastHeartbeatAt ? detail.connectivityStatus : 'UNKNOWN'"
          /><StatusBadge :value="detail.healthStatus" /><span class="muted-tag">{{
            detail.enabled ? '已启用' : '已停用'
          }}</span>
        </div>
        <ElTabs v-model="detailTab"
          ><ElTabPane name="basic" label="基本信息"
            ><dl class="detail-grid">
              <dt>设备类型</dt>
              <dd>{{ deviceTypes[detail.type] }}</dd>
              <dt>所属位置</dt>
              <dd>
                {{ detail.buildingName }} / {{ detail.floorName }} /
                {{ detail.areaName ?? '未分配区域' }}
              </dd>
              <dt>上游节点</dt>
              <dd>{{ detail.upstreamNodeName ?? '未配置' }}</dd>
              <dt>最后心跳</dt>
              <dd>{{ formatTime(detail.lastHeartbeatAt) }}</dd>
              <dt>创建时间</dt>
              <dd>{{ formatTime(detail.createdAt) }}</dd>
              <dt>设备 ID</dt>
              <dd class="mono">{{ detail.id }}</dd>
              <dt>会话版本 / 序号</dt>
              <dd>{{ detail.sessionEpoch }} / {{ detail.seq < 0 ? '尚未上报' : detail.seq }}</dd>
            </dl>
            <div class="alarm-action-row">
              <RouterLink
                class="text-link"
                :to="{ path: '/topology', query: { nodeId: detail.id } }"
                >定位拓扑</RouterLink
              >
              <ElButton v-if="admin" @click="openForm(detail)">编辑设备配置</ElButton>
            </div></ElTabPane
          ><ElTabPane name="events" label="最近事件"
            ><div v-if="!history.length" class="drawer-status">尚无设备事件</div>
            <ol v-else class="event-timeline">
              <li v-for="event in history" :key="event.eventId">
                <RouterLink
                  class="text-link"
                  :to="{ path: '/events', query: { id: event.eventId, deviceId: detail.id } }"
                  >{{
                    eventTypes[event.eventType as keyof typeof eventTypes] ?? event.eventType
                  }}</RouterLink><span>{{ formatTime(event.receivedAt) }}</span
                ><StatusBadge :value="event.processingStatus" />
              </li></ol
          ></ElTabPane>
          <ElTabPane name="relations" label="告警与工单">
            <p v-if="relationsLoading" class="drawer-status">正在查询关联记录…</p>
            <p v-else-if="relationsError" class="drawer-status">
              {{ relationsError }}
              <ElButton link type="primary" @click="loadRelations(detail.id)">重试</ElButton>
            </p>
            <p v-else-if="!related.length" class="drawer-status">暂无关联告警与工单</p>
            <ul v-else class="drawer-alarm-links">
              <li v-for="{ alarm, order } in related" :key="alarm.id">
                <RouterLink class="text-link" :to="{ path: '/alarms', query: { id: alarm.id } }"
                  >{{ alarm.alarmNo }} · {{ alarmTitle(alarm) }}</RouterLink
                >
                <RouterLink
                  v-if="order"
                  class="text-link"
                  :to="{ path: '/workorders', query: { id: order.id } }"
                  >{{ order.orderNo }}</RouterLink
                >
              </li>
            </ul>
          </ElTabPane>
        </ElTabs>
      </template>
    </ElDrawer>
    <ElDialog
      v-model="dialogOpen"
      :title="editing ? '编辑设备' : '注册设备'"
      width="560px"
      :close-on-click-modal="!saving"
    >
      <ElForm ref="form" :model="draft" :rules="rules" label-position="top" @submit.prevent="save">
        <div class="form-columns">
          <ElFormItem label="设备编号" prop="deviceCode"
            ><ElInput
              v-model="draft.deviceCode"
              :disabled="!!editing"
              maxlength="64"
              placeholder="例如 CAM-001" /></ElFormItem
          ><ElFormItem label="设备类型" prop="type"
            ><ElSelect v-model="draft.type" :disabled="!!editing"
              ><ElOption
                v-for="(label, value) in deviceTypes"
                :key="value"
                :label="label"
                :value="value" /></ElSelect
          ></ElFormItem>
        </div>
        <ElFormItem label="设备名称" prop="name"
          ><ElInput v-model="draft.name" maxlength="100" placeholder="请输入设备名称"
        /></ElFormItem>
        <div class="form-columns">
          <ElFormItem label="所属楼宇" prop="buildingId"
            ><ElSelect v-model="draft.buildingId" placeholder="请选择楼宇" @change="changeBuilding"
              ><ElOption
                v-for="building in locations.buildings"
                :key="building.id"
                :label="building.name"
                :value="building.id" /></ElSelect></ElFormItem
          ><ElFormItem label="所属楼层" prop="floorId"
            ><ElSelect v-model="draft.floorId" placeholder="请选择楼层" @change="draft.areaId = ''"
              ><ElOption
                v-for="floor in floors"
                :key="floor.id"
                :label="floor.name"
                :value="floor.id" /></ElSelect
          ></ElFormItem>
        </div>
        <div class="form-columns">
          <ElFormItem label="区域"
            ><ElSelect v-model="draft.areaId" placeholder="可选" clearable
              ><ElOption
                v-for="area in areas"
                :key="area.id"
                :label="area.name"
                :value="area.id" /></ElSelect></ElFormItem
          ><ElFormItem label="上游交换机"
            ><ElSelect v-model="draft.upstreamNodeId" placeholder="可选" clearable filterable
              ><ElOption
                v-for="device in switches.filter((item) => item.id !== editing?.id)"
                :key="device.id"
                :label="device.name"
                :value="device.id" /></ElSelect
          ></ElFormItem>
        </div>
        <ElFormItem v-if="editing" label="资产启用状态"
          ><ElSwitch v-model="draft.enabled" active-text="启用" inactive-text="停用"
        /></ElFormItem>
      </ElForm>
      <template #footer
        ><ElButton :disabled="saving" @click="dialogOpen = false">取消</ElButton
        ><ElButton type="primary" :loading="saving" @click="save">{{
          editing ? '保存修改' : '确认注册'
        }}</ElButton></template
      >
    </ElDialog>
    <ElDialog v-model="credentialOpen" title="设备注册成功" width="520px" @closed="credential = ''"
      ><p class="credential-help">请保存设备凭据，用于模拟器建立设备会话。</p>
      <pre class="credential-value">{{ credential }}</pre>
      <template #footer
        ><ElButton @click="copyCredential">复制设备凭据</ElButton
        ><ElButton type="primary" @click="credentialOpen = false">已保存</ElButton></template
      ></ElDialog
    >
  </section>
</template>
