<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import ValueBadge from '../components/ValueBadge.vue'
import {
  allowed,
  command,
  uncertainWorkOrder,
  checkWorkOperation,
  retryWorkOrderCommand,
  core,
  label,
  loadMe,
  me,
  query,
  type Device,
  type Incident,
  type Order,
  type Page,
} from '../video'
import { formatTime } from '../domain'
const route = useRoute(),
  canCreate = allowed('workorder.create'),
  canAssign = allowed('workorder.assign'),
  canCancel = allowed('workorder.cancel'),
  canReopen = allowed('workorder.reopen')
const rows = ref<Page<Order>>({ items: [], total: 0, page: 1, size: 20 }),
  selected = ref<Order | null>(null)
const devices = ref<Device[]>([]),
  people = ref<{ userId: string; displayName: string; roleCode: string }[]>([]),
  incident = ref<Incident | null>(null)
const status = ref(''),
  error = ref(''),
  busy = ref(false),
  selectedIds = ref<string[]>([]),
  mainId = ref('')
const create = reactive({
  title: '',
  description: '',
  expectedResult: '',
  workCategory: '设备维修',
  priority: 'P3',
  priorityReason: '',
  dueAt: '',
})
const assign = reactive({ assignedRepairerId: '', designatedAcceptorId: '', reason: '' }),
  repair = reactive({ recordType: 'DIAGNOSIS', content: '' })
const completion = ref(''),
  deviceResults = ref<Record<string, string>>({}),
  acceptance = reactive({ result: 'PASS', comment: '' })
const checks = reactive([
  { name: '逐台核查维修设备状态及业务数据', passed: false },
  { name: '确认处置结果符合工单预期', passed: false },
])
const operation = ref<Awaited<ReturnType<typeof checkWorkOperation>>>(null)
async function checkOperation() {
  await run(async () => {
    operation.value = await checkWorkOperation()
    if (operation.value?.state === 'COMMITTED') {
      await reload()
      if (operation.value.workOrderId) await open(operation.value.workOrderId)
    }
  })
}
async function retryOriginal() {
  await run(async () => {
    const result = await retryWorkOrderCommand()
    await reload()
    if (result?.workOrderId) await open(result.workOrderId)
    else if (selected.value) await open(selected.value.workOrderId)
  })
}
const isRepairer = computed(
  () => !!selected.value && selected.value.assignedRepairerId === me.value?.userId,
)
const isAcceptor = computed(
  () =>
    !!selected.value &&
    selected.value.designatedAcceptorId === me.value?.userId &&
    !isRepairer.value,
)
async function run(task: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await task()
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
async function reload() {
  rows.value = await core<Page<Order>>(
    '/workorders?' +
      query({
        page: rows.value.page,
        status: status.value,
        deviceId: route.query.deviceId ? String(route.query.deviceId) : undefined,
      }),
  )
}
async function open(id: string) {
  selected.value = await core<Order>('/workorders/' + id)
  const o = selected.value
  assign.assignedRepairerId = o.assignedRepairerId ?? ''
  assign.designatedAcceptorId = o.designatedAcceptorId ?? ''
  assign.reason = ''
  completion.value = o.completionSummary ?? ''
  deviceResults.value = Object.fromEntries(o.deviceItems.map((d) => [d.deviceId, '']))
  checks.forEach((c) => {
    c.passed = false
  })
  acceptance.comment = ''
  if (canAssign.value)
    people.value = await core<typeof people.value>(
      '/workorder-assignees?' +
        query({ deviceIds: o.deviceItems.map((d) => d.deviceId).join(',') }),
    )
}
async function createOrder() {
  if (!selectedIds.value.includes(mainId.value)) {
    error.value = '请从维修设备中指定一台主设备'
    return
  }
  await run(async () => {
    const result = await command<{ workOrderId: string }>('/workorders', {
      ...create,
      dueAt: create.dueAt ? new Date(create.dueAt).toISOString() : null,
      sourceType: incident.value ? 'INCIDENT' : 'MANUAL',
      incidentId: incident.value?.incidentId ?? null,
      deviceItems: selectedIds.value.map((id) => ({
        deviceId: id,
        deviceRole: id === mainId.value ? 'MAIN' : 'RELATED',
        issueDescription: create.description,
      })),
    })
    await reload()
    await open(result.workOrderId)
  })
}
async function act(action: string, body: object = {}) {
  const o = selected.value
  if (!o) return
  await run(async () => {
    await command('/workorders/' + o.workOrderId + '/' + action, {
      ...body,
      expectedVersion: o.version,
    })
    await open(o.workOrderId)
    await reload()
  })
}
async function reasonAction(action: string) {
  const reason = window.prompt('请说明本次操作的原因')
  if (reason) await act(action, { reason })
}
async function submit() {
  await act('submit-completion', {
    completionSummary: completion.value,
    deviceResults: Object.entries(deviceResults.value).map(([deviceId, result]) => ({
      deviceId,
      result,
    })),
    evidenceReferenceIds: [],
  })
}
onMounted(() =>
  run(async () => {
    await loadMe()
    await reload()
    if (canCreate.value)
      devices.value = (await core<Page<Device>>('/devices?size=100')).items.filter(
        (d) => d.assetStatus !== 'RETIRED',
      )
    if (route.query.incidentId) {
      incident.value = await core<Incident>('/incidents/' + String(route.query.incidentId))
      selectedIds.value = [...new Set(incident.value.anomalyLinks?.map((a) => a.deviceId) ?? [])]
      mainId.value = incident.value.candidateRootDeviceId ?? selectedIds.value[0] ?? ''
      create.title = incident.value.title
      create.description = '核查并处置关联故障：' + incident.value.incidentNo
      create.priority = incident.value.priority
    }
    if (route.query.id) await open(String(route.query.id))
  }),
)
</script>
<template>
  <div class="video-page">
    <PageHeader
      ><RouterLink to="/agent-drafts">审阅 Agent 草稿</RouterLink
      ><button :disabled="busy" @click="run(reload)">刷新</button></PageHeader
    >
    <p v-if="error" class="video-error" role="alert">{{ error }}</p>
    <section v-if="uncertainWorkOrder" class="video-panel" aria-label="待核对的工单操作">
      <p class="video-notice">
        上一笔工单操作结果尚未确认。请先核对结果，避免重复提交；重试会使用原请求和原幂等键。
      </p>
      <p v-if="uncertainWorkOrder.operationId" class="video-muted">
        操作编号：{{ uncertainWorkOrder.operationId }}
      </p>
      <button v-if="uncertainWorkOrder.operationId" :disabled="busy" @click="checkOperation">
        查询操作状态
      </button>
      <button :disabled="busy" @click="retryOriginal">核对并重试原操作</button>
    </section>
    <p v-if="operation" class="video-muted" role="status">
      操作状态：{{ label(operation.state) }} · 核对时间 {{ formatTime(operation.checkedAt) }}
    </p>
    <section class="video-panel">
      <div class="video-toolbar">
        <select
          v-model="status"
          aria-label="工单状态"
          @change="
            run(async () => {
              rows.page = 1
              await reload()
            })
          "
        >
          <option value="">全部状态</option>
          <option
            v-for="s in [
              'OPEN',
              'ASSIGNED',
              'ACCEPTED',
              'IN_PROGRESS',
              'VERIFYING',
              'RETURNED',
              'CLOSED',
              'CANCELLED',
            ]"
            :key="s"
            :value="s"
          >
            {{ label(s) }}
          </option></select
        ><span class="video-muted">维修人与验收人分别履职；新工单不会由异常自动生成。</span>
      </div>
      <div class="video-table-wrap">
        <table class="video-table">
          <thead>
            <tr>
              <th>工单</th>
              <th>来源</th>
              <th>优先级</th>
              <th>状态</th>
              <th>设备数</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="o in rows.items" :key="o.workOrderId">
              <td>
                {{ o.title }}
                <p class="video-muted">{{ o.workOrderNo }}</p>
              </td>
              <td>{{ label(o.sourceType) }}</td>
              <td>{{ o.priority }}</td>
              <td><ValueBadge :value="o.status" /></td>
              <td>{{ o.deviceItems.length }}</td>
              <td>
                <button class="link" @click="run(() => open(o.workOrderId))">查看与处置</button>
              </td>
            </tr>
            <tr v-if="!rows.items.length">
              <td colspan="6" class="video-muted">
                暂无可见工单。维修或验收人员只能查看分派给自己的任务。
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="video-pager">
        <span>共 {{ rows.total }} 项</span
        ><button
          :disabled="busy || rows.page <= 1"
          @click="
            run(async () => {
              rows.page--
              await reload()
            })
          "
        >
          上一页</button
        ><button
          :disabled="busy || rows.page * rows.size >= rows.total"
          @click="
            run(async () => {
              rows.page++
              await reload()
            })
          "
        >
          下一页
        </button>
      </div>
    </section>
    <section v-if="selected" class="video-panel">
      <div class="video-actions">
        <h2>{{ selected.workOrderNo }} · {{ selected.title }}</h2>
        <ValueBadge :value="selected.status" /><button class="link" @click="selected = null">
          收起</button
        ><RouterLink v-if="selected.incidentId" :to="'/alarms?id=' + selected.incidentId"
          >来源故障</RouterLink
        >
      </div>
      <dl>
        <dt>维修周期</dt>
        <dd>第 {{ selected.workCycleNo }} 轮</dd>
        <dt>问题说明</dt>
        <dd>{{ selected.description }}</dd>
        <dt>预期结果</dt>
        <dd>{{ selected.expectedResult }}</dd>
        <dt>维修人</dt>
        <dd>{{ selected.assignedRepairerId ?? '待分派' }}</dd>
        <dt>验收人</dt>
        <dd>{{ selected.designatedAcceptorId ?? '待分派' }}</dd>
      </dl>
      <table class="video-table">
        <thead>
          <tr>
            <th>设备</th>
            <th>职责</th>
            <th>问题</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in selected.deviceItems" :key="d.deviceId">
            <td>{{ d.deviceNameSnapshot }} · {{ d.assetCodeSnapshot }}</td>
            <td>{{ d.deviceRole === 'MAIN' ? '主设备' : '关联设备' }}</td>
            <td>{{ d.issueDescription }}</td>
          </tr>
        </tbody>
      </table>
      <form
        v-if="canAssign && ['OPEN', 'ASSIGNED', 'ACCEPTED'].includes(selected.status)"
        class="video-form"
        @submit.prevent="act(selected.status === 'OPEN' ? 'assign' : 'reassign', assign)"
      >
        <div class="video-grid">
          <label
            >维修人<select v-model="assign.assignedRepairerId" required>
              <option value="">请选择</option>
              <option
                v-for="p in people.filter((p) => p.roleCode === 'REPAIR_TECH')"
                :key="p.userId"
                :value="p.userId"
              >
                {{ p.displayName }}
              </option>
            </select></label
          ><label
            >独立验收人<select v-model="assign.designatedAcceptorId" required>
              <option value="">请选择</option>
              <option
                v-for="p in people.filter(
                  (p) =>
                    p.roleCode === 'ACCEPTANCE_STAFF' && p.userId !== assign.assignedRepairerId,
                )"
                :key="p.userId"
                :value="p.userId"
              >
                {{ p.displayName }}
              </option>
            </select></label
          >
        </div>
        <label v-if="selected.status !== 'OPEN'"
          >改派原因<textarea v-model="assign.reason" required maxlength="1000"></textarea></label
        ><button class="primary" :disabled="busy || !!uncertainWorkOrder">
          {{ selected.status === 'OPEN' ? '指派任务' : '保存改派' }}
        </button>
      </form>
      <div class="video-actions">
        <template v-if="isRepairer"
          ><button
            v-if="selected.status === 'ASSIGNED'"
            class="primary"
            :disabled="busy || !!uncertainWorkOrder"
            @click="act('accept')"
          >
            接单</button
          ><button
            v-if="selected.status === 'ASSIGNED'"
            :disabled="busy || !!uncertainWorkOrder"
            @click="reasonAction('decline')"
          >
            拒接并说明原因</button
          ><button
            v-if="['ACCEPTED', 'RETURNED'].includes(selected.status)"
            class="primary"
            :disabled="busy || !!uncertainWorkOrder"
            @click="act('start')"
          >
            开始维修
          </button></template
        ><button
          v-if="
            canCancel &&
            ['OPEN', 'ASSIGNED', 'ACCEPTED', 'IN_PROGRESS', 'RETURNED'].includes(selected.status)
          "
          class="danger"
          :disabled="busy || !!uncertainWorkOrder"
          @click="reasonAction('cancel')"
        >
          取消工单</button
        ><button
          v-if="canReopen && selected.status === 'CLOSED' && selected.sourceType === 'INCIDENT'"
          :disabled="busy || !!uncertainWorkOrder"
          @click="reasonAction('reopen')"
        >
          申请重开
        </button>
      </div>
      <div v-if="isRepairer && selected.status === 'IN_PROGRESS'" class="video-grid">
        <form
          class="video-form video-panel"
          @submit.prevent="act('repair-records', { ...repair, evidenceReferenceIds: [] })"
        >
          <h2>追加维修记录</h2>
          <select v-model="repair.recordType" aria-label="记录类型">
            <option v-for="t in ['DIAGNOSIS', 'ACTION', 'RESULT', 'NOTE']" :key="t" :value="t">
              {{ label(t) }}
            </option></select
          ><label
            >记录内容<textarea v-model="repair.content" required maxlength="4000"></textarea></label
          ><button :disabled="busy || !!uncertainWorkOrder">保存记录</button>
        </form>
        <form class="video-form video-panel" @submit.prevent="submit">
          <h2>提交独立验收</h2>
          <label>完工总结<textarea v-model="completion" required maxlength="4000"></textarea></label
          ><label v-for="d in selected.deviceItems" :key="d.deviceId"
            >{{ d.deviceNameSnapshot }} 的处置结果<textarea
              v-model="deviceResults[d.deviceId]"
              required
              maxlength="2000"
            ></textarea></label
          ><button class="primary" :disabled="busy || !!uncertainWorkOrder">提交验收</button>
        </form>
      </div>
      <form
        v-if="isAcceptor && selected.status === 'VERIFYING'"
        class="video-form video-panel"
        @submit.prevent="
          act('acceptance', { ...acceptance, checkItems: checks, evidenceReferenceIds: [] })
        "
      >
        <h2>独立验收</h2>
        <p>{{ selected.completionSummary }}</p>
        <label v-for="c in checks" :key="c.name"
          ><span><input v-model="c.passed" type="checkbox" /> {{ c.name }}</span></label
        ><label
          >验收结果<select v-model="acceptance.result">
            <option value="PASS">通过并关闭工单</option>
            <option value="RETURN">退回继续维修</option>
          </select></label
        ><label
          >验收说明<textarea
            v-model="acceptance.comment"
            required
            maxlength="1000"
          ></textarea></label
        ><button
          class="primary"
          :disabled="
            busy ||
            !!uncertainWorkOrder ||
            (acceptance.result === 'PASS' && checks.some((c) => !c.passed))
          "
        >
          提交验收结果
        </button>
      </form>
      <div class="video-grid">
        <section>
          <h2>维修记录</h2>
          <div v-for="r in selected.repairRecords" :key="r.recordId" class="video-timeline">
            <p>{{ label(r.recordType) }} · {{ r.content }}</p>
            <span class="video-muted">{{ r.operatorId }} · {{ formatTime(r.recordedAt) }}</span>
          </div>
        </section>
        <section>
          <h2>验收记录</h2>
          <div v-for="a in selected.acceptanceRecords" :key="a.acceptanceId" class="video-timeline">
            <p>第 {{ a.workCycleNo }} 轮 · {{ label(a.result) }} · {{ a.comment }}</p>
            <span class="video-muted">{{ a.acceptorId }} · {{ formatTime(a.acceptedAt) }}</span>
          </div>
        </section>
      </div>
      <details>
        <summary>操作历史</summary>
        <div v-for="(h, i) in selected.statusHistory" :key="i" class="video-timeline">
          <p>{{ h.action }} → {{ label(h.toStatus) }} · {{ h.remark }}</p>
          <span class="video-muted">{{ h.actor }} · {{ formatTime(h.createdAt) }}</span>
        </div>
      </details>
    </section>
    <details v-if="canCreate" class="video-panel" :open="!!incident">
      <summary>
        {{ incident ? '基于故障 ' + incident.incidentNo + ' 创建维修工单' : '新建独立维修工单' }}
      </summary>
      <form class="video-form" @submit.prevent="createOrder">
        <div class="video-grid">
          <label>标题<input v-model="create.title" required maxlength="160" /></label
          ><label>预期完成时间<input v-model="create.dueAt" type="datetime-local" /></label
          ><label
            >问题描述<textarea
              v-model="create.description"
              required
              maxlength="4000"
            ></textarea></label
          ><label
            >预期结果<textarea
              v-model="create.expectedResult"
              required
              maxlength="2000"
            ></textarea></label
          ><label
            >优先级<select v-model="create.priority">
              <option v-for="p in ['P1', 'P2', 'P3', 'P4']" :key="p" :value="p">{{ p }}</option>
            </select></label
          ><label v-if="incident && create.priority !== incident.priority"
            >覆盖故障优先级的原因<textarea
              v-model="create.priorityReason"
              required
              maxlength="1000"
            ></textarea></label
          ><label
            >维修设备<select v-model="selectedIds" multiple required>
              <option v-for="d in devices" :key="d.deviceId" :value="d.deviceId">
                {{ d.name }} · {{ d.assetCode }}
              </option>
            </select></label
          ><label
            >其中的主设备<select v-model="mainId" required>
              <option value="">请选择</option>
              <option
                v-for="d in devices.filter((d) => selectedIds.includes(d.deviceId))"
                :key="d.deviceId"
                :value="d.deviceId"
              >
                {{ d.name }}
              </option>
            </select></label
          >
        </div>
        <button class="primary" :disabled="busy || !!uncertainWorkOrder">确认创建正式工单</button>
      </form>
    </details>
  </div>
</template>
