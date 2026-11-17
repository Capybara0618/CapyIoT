<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElDialog, ElPagination, ElTable, ElTableColumn } from 'element-plus'
import {
  CircleCheck,
  ClipboardList,
  Clock3,
  FileText,
  Plus,
  RefreshCw,
  Search,
  X,
} from 'lucide-vue-next'
import { ApiError, request } from '../api'
import { useLiveUpdates } from '../live'
import { session } from '../session'
import { formatTime, type Page } from '../domain'
import { alarmTitle, alarmTypes, type Alarm } from '../alarms'
import {
  workActions,
  workPriorities,
  workStatuses,
  workStream,
  type WorkAction,
  type WorkCreated,
  type WorkDetail,
  type WorkOrder,
  type WorkSummary,
} from '../workorders'
import PageHeader from '../components/PageHeader.vue'

const route = useRoute()
const router = useRouter()
const selected = computed(() => String(route.query.id ?? ''))
const mine = computed(() => route.query.mine === 'true')
const page = ref<Page<WorkOrder>>({ items: [], total: 0, page: 1, size: 10 })
const summary = ref<WorkSummary>()
const detail = ref<WorkDetail>()
const detailPage = ref(1)
const filters = reactive({ status: '', priority: '' })
const loading = ref(false)
const error = ref('')
const detailError = ref('')
const dialogError = ref('')
const saving = ref(false)

const createOpen = ref(false)
const candidates = ref<Alarm[]>([])
const candidateLoading = ref(false)
const createDraft = reactive({ sourceAlarmId: '', title: '', priority: '' })
let createBody = ''
let createKey = ''

const actionOpen = ref(false)
const actionDraft = reactive({
  action: 'ASSIGN' as WorkAction,
  assignee: '',
  remark: '',
  id: 0,
  version: 0,
})
const admin = computed(() => session.value?.user.role === 'ADMIN')
const assignment = computed(() => ['ASSIGN', 'REASSIGN'].includes(actionDraft.action))
const noteRequired = computed(() =>
  ['ADD_NOTE', 'COMPLETE_PROCESSING', 'VERIFY_RECOVERY', 'RETURN_TO_PROCESSING', 'REOPEN'].includes(
    actionDraft.action,
  ),
)
const metrics = computed(() => [
  { label: '全部工单', value: summary.value?.total, icon: ClipboardList, tone: 'blue' },
  { label: '待处理', value: summary.value?.pending, icon: Clock3, tone: 'orange' },
  { label: '处理中', value: summary.value?.processing, icon: FileText, tone: 'blue' },
  { label: '已关闭', value: summary.value?.closed, icon: CircleCheck, tone: 'green' },
])

let listVersion = 0
let detailVersion = 0
let timer: ReturnType<typeof setTimeout> | undefined

async function load() {
  const version = ++listVersion
  loading.value = true
  const query = new URLSearchParams()
  for (const field of ['status', 'priority', 'mine', 'page']) {
    const value = route.query[field]
    if (typeof value === 'string') query.set(field, value)
  }
  try {
    const [rows, totals] = await Promise.all([
      request<Page<WorkOrder>>(`/workorders?${query}`),
      request<WorkSummary>('/workorders/summary'),
    ])
    if (version !== listVersion) return
    page.value = rows
    summary.value = totals
    error.value = ''
  } catch (failure) {
    if (version === listVersion) error.value = (failure as Error).message
  } finally {
    if (version === listVersion) loading.value = false
  }
}

async function loadDetail() {
  const version = ++detailVersion
  if (!selected.value) return
  try {
    const row = await request<WorkDetail>(
      `/workorders/${encodeURIComponent(selected.value)}?timelinePage=${detailPage.value}`,
    )
    if (version !== detailVersion) return
    detail.value = row
    detailError.value = ''
  } catch (failure) {
    if (version === detailVersion) detailError.value = (failure as Error).message
  }
}

function refresh() {
  if (timer !== undefined) return
  timer = setTimeout(() => {
    timer = undefined
    void load()
    void loadDetail()
  }, 250)
}
const live = useLiveUpdates(refresh, workStream)
watch(
  () => [route.query.status, route.query.priority, route.query.mine, route.query.page],
  () => {
    filters.status = String(route.query.status ?? '')
    filters.priority = String(route.query.priority ?? '')
    void load()
  },
  { immediate: true },
)
watch(
  selected,
  () => {
    detail.value = undefined
    detailError.value = ''
    detailPage.value = 1
    void loadDetail()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  listVersion++
  detailVersion++
  clearTimeout(timer)
})

function select(id?: number) {
  void router.replace({ query: { ...route.query, id: id == null ? undefined : String(id) } })
}
function search() {
  void router.replace({
    query: {
      ...route.query,
      status: filters.status || undefined,
      priority: filters.priority || undefined,
      page: undefined,
    },
  })
}
function showMine(value: boolean) {
  void router.replace({
    query: { ...route.query, mine: value ? 'true' : undefined, page: undefined },
  })
}
function changePage(value: number) {
  void router.replace({ query: { ...route.query, page: String(value) } })
}
function changeTimeline(value: number) {
  detailPage.value = value
  void loadDetail()
}
async function openCreate() {
  dialogError.value = ''
  createKey = ''
  createBody = ''
  Object.assign(createDraft, { sourceAlarmId: '', title: '', priority: '' })
  createOpen.value = true
  candidateLoading.value = true
  try {
    candidates.value = (await request<Page<Alarm>>('/alarms?size=100')).items
  } catch (failure) {
    dialogError.value = (failure as Error).message
  } finally {
    candidateLoading.value = false
  }
}
async function submitCreate() {
  if (!createDraft.sourceAlarmId || saving.value) return
  const body = JSON.stringify({
    sourceAlarmId: Number(createDraft.sourceAlarmId),
    title: createDraft.title.trim() || undefined,
    priority: createDraft.priority || undefined,
  })
  if (createBody !== body) {
    createBody = body
    createKey = crypto.randomUUID()
  }
  saving.value = true
  dialogError.value = ''
  try {
    const created = await request<WorkCreated>('/workorders', {
      method: 'POST',
      headers: { 'Idempotency-Key': createKey },
      body,
    })
    createOpen.value = false
    select(created.order.id)
    void load()
  } catch (failure) {
    dialogError.value = (failure as Error).message
  } finally {
    saving.value = false
  }
}

function openAction(action: WorkAction) {
  const order = detail.value?.order
  if (!order) return
  Object.assign(actionDraft, {
    action,
    id: order.id,
    version: order.version,
    assignee: session.value?.user.username ?? '',
    remark: '',
  })
  dialogError.value = ''
  actionOpen.value = true
}
async function submitAction() {
  if (saving.value || (noteRequired.value && !actionDraft.remark.trim())) return
  saving.value = true
  dialogError.value = ''
  try {
    await request<WorkOrder>(`/workorders/${actionDraft.id}/transitions`, {
      method: 'POST',
      body: JSON.stringify({
        action: actionDraft.action,
        expectedVersion: actionDraft.version,
        assignee: assignment.value ? actionDraft.assignee : undefined,
        remark: actionDraft.remark.trim(),
      }),
    })
    actionOpen.value = false
    await Promise.all([load(), loadDetail()])
  } catch (failure) {
    dialogError.value = (failure as Error).message
    if (failure instanceof ApiError && failure.status === 409) {
      await loadDetail()
      if (
        detail.value?.order.id === actionDraft.id &&
        detail.value.actions.includes(actionDraft.action)
      ) {
        actionDraft.version = detail.value.order.version
      }
    }
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="business-page workorder-page">
    <PageHeader>
      <span class="live-status" :class="live"
        ><span></span>{{ live === 'connected' ? '实时连接已建立' : '正在连接实时通知' }}</span
      >
      <ElButton type="primary" @click="openCreate"><Plus :size="16" />创建工单</ElButton>
    </PageHeader>
    <div class="metrics-grid">
      <article v-for="item in metrics" :key="item.label" class="metric-card">
        <span class="metric-symbol" :class="item.tone"
          ><component :is="item.icon" :size="22"
        /></span>
        <span
          ><span class="metric-label">{{ item.label }}</span
          ><strong>{{ item.value ?? '—' }}</strong></span
        >
      </article>
    </div>
    <form class="filter-toolbar work-filters" @submit.prevent="search">
      <label
        >处理状态<select v-model="filters.status">
          <option value="">全部状态</option>
          <option v-for="(label, id) in workStatuses" :key="id" :value="id">{{ label }}</option>
        </select></label
      >
      <label
        >优先级<select v-model="filters.priority">
          <option value="">全部优先级</option>
          <option v-for="(label, id) in workPriorities" :key="id" :value="id">{{ label }}</option>
        </select></label
      >
      <div class="filter-actions">
        <ElButton type="primary" native-type="submit"><Search :size="15" />查询</ElButton>
      </div>
    </form>
    <p v-if="error" class="request-error" role="alert">
      {{ error }} <ElButton link type="primary" @click="load">重试</ElButton>
    </p>
    <div class="alarm-columns work-columns" :class="{ 'with-detail': selected }">
      <div class="table-panel" :aria-busy="loading">
        <div class="panel-heading">
          <h2>
            工单列表 <span>{{ page.total }}</span>
          </h2>
          <div class="alarm-view-options">
            <button :aria-pressed="!mine" @click="showMine(false)">全部工单</button>
            <button :aria-pressed="mine" @click="showMine(true)">我的工单</button>
            <ElButton text aria-label="刷新工单" @click="refresh"
              ><RefreshCw :size="15"
            /></ElButton>
          </div>
        </div>
        <ElTable :data="page.items" row-key="id" empty-text="暂无匹配的工单" max-height="540px">
          <ElTableColumn label="工单编号" min-width="100"
            ><template #default="{ row }"
              ><button class="text-link mono" @click="select(row.id)">
                {{ row.orderNo }}
              </button></template
            ></ElTableColumn
          >
          <ElTableColumn label="标题 / 关联告警" min-width="170"
            ><template #default="{ row }"
              ><button class="text-link alarm-row-title" @click="select(row.id)">
                {{ row.title }}</button
              ><small class="cell-secondary">{{ row.sourceAlarmNo }}</small></template
            ></ElTableColumn
          >
          <ElTableColumn label="优先级" width="66"
            ><template #default="{ row }"
              ><span
                class="alarm-chip"
                :class="
                  row.priority === 'HIGH'
                    ? 'critical'
                    : row.priority === 'MEDIUM'
                      ? 'warning'
                      : 'network'
                "
                >{{ workPriorities[(row as WorkOrder).priority] }}</span
              ></template
            ></ElTableColumn
          >
          <ElTableColumn label="状态" width="80"
            ><template #default="{ row }"
              ><span
                class="alarm-chip"
                :class="
                  row.status === 'CLOSED' ? 'recovered' : row.status === 'OPEN' ? 'open' : 'network'
                "
                >{{ workStatuses[(row as WorkOrder).status] }}</span
              ></template
            ></ElTableColumn
          >
          <ElTableColumn label="处理人" min-width="81"
            ><template #default="{ row }">{{ row.assignee ?? '未指派' }}</template></ElTableColumn
          >
          <ElTableColumn v-if="!selected" label="创建时间" width="165"
            ><template #default="{ row }"
              ><span class="mono time-cell">{{ formatTime(row.createdAt) }}</span></template
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

      <aside v-if="selected" class="alarm-detail work-detail" aria-label="工单详情">
        <header class="panel-heading">
          <h2><ClipboardList :size="18" /> 工单详情</h2>
          <ElButton text aria-label="关闭工单详情" @click="select()"><X :size="18" /></ElButton>
        </header>
        <p v-if="detailError" class="request-error" role="alert">{{ detailError }}</p>
        <p v-else-if="!detail" class="alarm-pad">正在读取工单</p>
        <template v-if="detail">
          <div class="alarm-detail-head">
            <span class="mono">{{ detail.order.orderNo }}</span>
            <h3>{{ detail.order.title }}</h3>
            <div class="alarm-labels">
              <span
                class="alarm-chip"
                :class="detail.order.status === 'CLOSED' ? 'recovered' : 'network'"
                >{{ workStatuses[detail.order.status] }}</span
              >
              <span
                class="alarm-chip"
                :class="detail.order.priority === 'HIGH' ? 'critical' : 'warning'"
                >{{ workPriorities[detail.order.priority] }}优先级</span
              >
            </div>
          </div>
          <dl class="work-fields">
            <div>
              <dt>来源告警</dt>
              <dd>
                <RouterLink
                  class="text-link"
                  :to="{ path: '/alarms', query: { id: detail.order.sourceAlarmId } }"
                  >{{ detail.order.sourceAlarmNo }}</RouterLink
                >
                <small>{{ alarmTypes[detail.order.source.type] }}</small>
              </dd>
            </div>
            <div>
              <dt>处理人</dt>
              <dd>{{ detail.order.assignee ?? '未指派' }}</dd>
            </div>
            <div>
              <dt>创建时间</dt>
              <dd>{{ formatTime(detail.order.createdAt) }}</dd>
            </div>
            <div>
              <dt>来源状态</dt>
              <dd>{{ detail.order.source.status === 'RECOVERED' ? '已恢复' : '仍活跃' }}</dd>
            </div>
            <div v-if="detail.order.verifiedAt">
              <dt>验证时间</dt>
              <dd>{{ formatTime(detail.order.verifiedAt) }}</dd>
            </div>
            <div v-if="detail.order.closedAt">
              <dt>关闭时间</dt>
              <dd>{{ formatTime(detail.order.closedAt) }}</dd>
            </div>
          </dl>
          <div
            v-if="detail.order.status !== 'CLOSED' || detail.actions.length"
            class="work-actions"
          >
            <ElButton
              v-for="action in detail.actions"
              :key="action"
              :type="
                action === 'CLOSE' || action === 'START_PROCESSING' || action === 'ASSIGN'
                  ? 'primary'
                  : 'default'
              "
              @click="openAction(action)"
              >{{ action === 'ASSIGN' && !admin ? '领取工单' : workActions[action] }}</ElButton
            >
            <p
              v-if="detail.order.status === 'VERIFYING' && !detail.recoveryVerified"
              class="work-note"
            >
              {{
                detail.order.source.status === 'RECOVERED'
                  ? '来源告警已恢复，完成恢复验证后可关闭工单。'
                  : '来源告警尚未恢复，恢复后可验证并关闭工单。'
              }}
            </p>
            <p
              v-else-if="!detail.actions.length && detail.order.status !== 'CLOSED'"
              class="work-note"
            >
              由当前处理人或管理员继续操作。
            </p>
          </div>
          <div class="work-timeline">
            <h4>状态时间线与处置记录</h4>
            <ol>
              <li v-for="item in detail.timeline.items" :key="item.version">
                <strong>{{
                  item.action === 'CREATE' ? '创建工单' : workActions[item.action]
                }}</strong>
                <time>{{ formatTime(item.createdAt) }}</time>
                <div class="work-note">
                  {{ item.actor === 'system' ? '系统自动开单' : item.actor
                  }}<span v-if="item.assignee && ['ASSIGN', 'REASSIGN'].includes(item.action)">
                    → {{ item.assignee }}</span
                  ><span
                    v-else-if="
                      item.fromStatus && item.toStatus && item.fromStatus !== item.toStatus
                    "
                  >
                    · {{ workStatuses[item.fromStatus] }} → {{ workStatuses[item.toStatus] }}</span
                  >
                </div>
                <p v-if="item.remark" class="work-remark">{{ item.remark }}</p>
              </li>
            </ol>
            <ElPagination
              v-if="detail.timeline.total > detail.timeline.size"
              small
              :current-page="detailPage"
              :page-size="detail.timeline.size"
              :total="detail.timeline.total"
              layout="prev, pager, next"
              @current-change="changeTimeline"
            />
          </div>
        </template>
      </aside>
    </div>

    <ElDialog v-model="createOpen" title="创建工单" width="520px" :close-on-click-modal="false">
      <form class="work-form" @submit.prevent="submitCreate">
        <label
          >来源主告警<select v-model="createDraft.sourceAlarmId" :disabled="candidateLoading">
            <option value="">{{ candidateLoading ? '正在读取主告警' : '选择主告警' }}</option>
            <option v-for="alarm in candidates" :key="alarm.id" :value="String(alarm.id)">
              {{ alarm.alarmNo }} · {{ alarmTitle(alarm) }}
            </option>
          </select></label
        >
        <label
          >工单标题<input
            v-model="createDraft.title"
            maxlength="160"
            placeholder="留空时使用来源告警生成标题"
        /></label>
        <label
          >优先级<select v-model="createDraft.priority">
            <option value="">按告警级别</option>
            <option v-for="(label, key) in workPriorities" :key="key" :value="key">
              {{ label }}
            </option>
          </select></label
        >
        <p v-if="dialogError" class="request-error" role="alert">{{ dialogError }}</p>
        <div class="work-dialog-buttons">
          <ElButton @click="createOpen = false">取消</ElButton
          ><ElButton
            type="primary"
            native-type="submit"
            :loading="saving"
            :disabled="!createDraft.sourceAlarmId"
            >确认创建</ElButton
          >
        </div>
      </form>
    </ElDialog>
    <ElDialog
      v-model="actionOpen"
      :title="workActions[actionDraft.action]"
      width="520px"
      :close-on-click-modal="false"
    >
      <form class="work-form" @submit.prevent="submitAction">
        <label v-if="assignment"
          >处理人<select v-model="actionDraft.assignee" :disabled="!admin">
            <option v-if="admin" value="admin">平台管理员</option>
            <option value="operator">运维人员</option>
          </select></label
        >
        <label
          >处置说明<textarea
            v-model="actionDraft.remark"
            rows="4"
            maxlength="1000"
            :placeholder="noteRequired ? '请输入本次处理或验证的依据' : '记录本次操作（可选）'"
          />
        </label>
        <p v-if="dialogError" class="request-error" role="alert">{{ dialogError }}</p>
        <div class="work-dialog-buttons">
          <ElButton @click="actionOpen = false">取消</ElButton
          ><ElButton
            type="primary"
            native-type="submit"
            :loading="saving"
            :disabled="noteRequired && !actionDraft.remark.trim()"
            >确认操作</ElButton
          >
        </div>
      </form>
    </ElDialog>
  </section>
</template>
