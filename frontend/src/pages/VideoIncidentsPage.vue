<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import ValueBadge from '../components/ValueBadge.vue'
import {
  allowed,
  command,
  core,
  label,
  loadMe,
  query,
  type Anomaly,
  type Incident,
  type Page,
} from '../video'
import { formatTime } from '../domain'
const route = useRoute(),
  review = allowed('incident.review'),
  dismissPermission = allowed('anomaly.dismiss'),
  createOrder = allowed('workorder.create'),
  closePermission = allowed('incident.close')
const incidents = ref<Page<Incident>>({ items: [], total: 0, page: 1, size: 20 }),
  anomalies = ref<Page<Anomaly>>({ items: [], total: 0, page: 1, size: 20 })
const selected = ref<Incident | null>(null),
  anomaly = ref<Anomaly | null>(null),
  checked = ref<string[]>([])
const error = ref(''),
  busy = ref(false),
  incidentStatus = ref(''),
  anomalyStatus = ref('ACTIVE')
const cause = reactive({ text: '', evidenceIds: [] as string[], reason: '' })
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
  incidents.value = await core<Page<Incident>>(
    '/incidents?' + query({ status: incidentStatus.value, page: incidents.value.page }),
  )
  anomalies.value = await core<Page<Anomaly>>(
    '/anomalies?' + query({ status: anomalyStatus.value, page: anomalies.value.page }),
  )
}
async function open(id: string) {
  selected.value = await core<Incident>('/incidents/' + id)
  cause.text = selected.value.confirmedRootCauseText ?? ''
  cause.evidenceIds = []
  cause.reason = ''
}
async function transition(action: string, extras: object = {}, method = 'POST') {
  const item = selected.value
  if (!item) return
  const reason = window.prompt('请填写本次操作的依据和原因')
  if (!reason) return
  await run(async () => {
    await command(
      '/incidents/' + item.incidentId + '/' + action,
      { ...extras, reason, expectedVersion: item.version },
      method,
    )
    await open(item.incidentId)
    await reload()
  })
}
async function dismiss(item: Anomaly) {
  const reason = window.prompt('请填写排除异常的依据。排除不代表设备已经恢复。')
  if (!reason) return
  await run(async () => {
    await command('/anomalies/' + item.anomalyId + '/dismiss', {
      reason,
      expectedVersion: item.version,
    })
    await reload()
    if (selected.value) await open(selected.value.incidentId)
  })
}
async function group() {
  const title = window.prompt('为选中的活动异常填写故障标题')
  if (!title) return
  const reason = window.prompt('请说明将这些异常归为同一故障的依据')
  if (!reason) return
  await run(async () => {
    const result = await command<{ incidentId: string }>('/incidents', {
      title,
      anomalyIds: checked.value,
      priority: 'P3',
      reason,
    })
    checked.value = []
    await open(result.incidentId)
    await reload()
  })
}
async function confirmCause() {
  const item = selected.value
  if (!item) return
  await run(async () => {
    await command(
      '/incidents/' + item.incidentId + '/root-cause',
      {
        rootCauseStatus: 'CONFIRMED',
        confirmedRootCauseText: cause.text,
        evidenceReferenceIds: cause.evidenceIds,
        reason: cause.reason,
        expectedVersion: item.version,
      },
      'PATCH',
    )
    await open(item.incidentId)
    await reload()
  })
}
onMounted(() =>
  run(async () => {
    await loadMe()
    await reload()
    if (route.query.id) await open(String(route.query.id))
  }),
)
</script>
<template>
  <div class="video-page">
    <PageHeader><button :disabled="busy" @click="run(reload)">刷新</button></PageHeader>
    <p v-if="error" class="video-error" role="alert">{{ error }}</p>
    <section class="video-panel">
      <h2>故障研判</h2>
      <form
        class="video-toolbar"
        @submit.prevent="
          run(async () => {
            incidents.page = 1
            await reload()
          })
        "
      >
        <select v-model="incidentStatus" aria-label="故障状态">
          <option value="">全部状态</option>
          <option
            v-for="s in [
              'PENDING_REVIEW',
              'OPEN',
              'PROCESSING',
              'PENDING_CLOSE',
              'CLOSED',
              'REJECTED',
            ]"
            :key="s"
            :value="s"
          >
            {{ label(s) }}
          </option></select
        ><button :disabled="busy">查询</button>
      </form>
      <div class="video-table-wrap">
        <table class="video-table">
          <thead>
            <tr>
              <th>故障</th>
              <th>优先级</th>
              <th>状态</th>
              <th>根因结论</th>
              <th>发现时间</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="i in incidents.items" :key="i.incidentId">
              <td>
                {{ i.title }}
                <p class="video-muted">{{ i.incidentNo }}</p>
              </td>
              <td>{{ i.priority }}</td>
              <td><ValueBadge :value="i.status" /></td>
              <td>{{ label(i.rootCauseStatus) }}</td>
              <td>{{ formatTime(i.openedAt) }}</td>
              <td><button class="link" @click="run(() => open(i.incidentId))">研判详情</button></td>
            </tr>
            <tr v-if="!incidents.items.length">
              <td colspan="6" class="video-muted">
                暂无可见故障。相关性线索只形成待研判记录，不会自动确认根因或创建维修工单。
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="video-pager">
        <span>共 {{ incidents.total }} 项</span
        ><button
          :disabled="busy || incidents.page <= 1"
          @click="
            run(async () => {
              incidents.page--
              await reload()
            })
          "
        >
          上一页</button
        ><button
          :disabled="busy || incidents.page * incidents.size >= incidents.total"
          @click="
            run(async () => {
              incidents.page++
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
        <h2>{{ selected.incidentNo }} · {{ selected.title }}</h2>
        <button class="link" @click="selected = null">收起</button>
      </div>
      <dl>
        <dt>状态</dt>
        <dd>{{ label(selected.status) }}</dd>
        <dt>根因状态</dt>
        <dd>{{ label(selected.rootCauseStatus) }}</dd>
        <dt>候选上游</dt>
        <dd>
          {{ selected.candidateRootDeviceId ?? '无' }}
          <span v-if="selected.rootCauseStatus !== 'CONFIRMED'" class="video-muted"
            >（仅关联线索，尚无确认结论）</span
          >
        </dd>
        <dt>确认结论</dt>
        <dd>{{ selected.confirmedRootCauseText ?? '暂无' }}</dd>
      </dl>
      <div class="video-actions">
        <template v-if="review && selected.status === 'PENDING_REVIEW'"
          ><button
            class="primary"
            :disabled="busy"
            @click="transition('review', { decision: 'CONFIRM' })"
          >
            确认需要处置</button
          ><button :disabled="busy" @click="transition('review', { decision: 'REJECT' })">
            拒绝关联
          </button></template
        ><RouterLink
          v-if="createOrder && ['OPEN', 'PROCESSING'].includes(selected.status)"
          :to="'/workorders?incidentId=' + selected.incidentId"
          >创建维修工单</RouterLink
        ><button
          v-if="
            closePermission && ['OPEN', 'PROCESSING', 'PENDING_CLOSE'].includes(selected.status)
          "
          :disabled="busy"
          @click="transition('close', { resolvedDisposition: '人工核查完成' })"
        >
          核查并关闭故障</button
        ><button
          v-if="review && !['CLOSED', 'REJECTED'].includes(selected.status)"
          :disabled="busy"
          @click="
            transition('priority', { priority: selected.priority === 'P2' ? 'P1' : 'P2' }, 'PATCH')
          "
        >
          调整为 {{ selected.priority === 'P2' ? 'P1' : 'P2' }}
        </button>
      </div>
      <p class="video-muted">
        维修工单验收通过后，仍需主管确认没有活动异常、未结束工单或待确认的建单结果，才能关闭故障。
      </p>
      <h2>实际关联设备</h2>
      <div v-for="link in selected.anomalyLinks" :key="link.anomalyId" class="video-timeline">
        <p>{{ link.deviceId }} · {{ link.anomalyId }}</p>
        <button
          class="link"
          @click="
            run(async () => {
              anomaly = await core<Anomaly>('/anomalies/' + link.anomalyId)
            })
          "
        >
          查看异常证据
        </button>
      </div>
      <details v-if="review && !['CLOSED', 'REJECTED'].includes(selected.status)">
        <summary>确认根因</summary>
        <form class="video-form" @submit.prevent="confirmCause">
          <label>结论<textarea v-model="cause.text" required maxlength="2000"></textarea></label
          ><label
            >选择用于确认的证据<select v-model="cause.evidenceIds" multiple required>
              <option
                v-for="e in selected.evidenceReferences"
                :key="e.evidenceId"
                :value="e.evidenceId"
              >
                {{ e.evidenceId }} · {{ formatTime(e.receivedAt) }}
              </option>
            </select></label
          ><label
            >判断依据<textarea v-model="cause.reason" required maxlength="1000"></textarea></label
          ><button :disabled="busy">保存根因确认</button>
        </form>
      </details>
      <h2>关联工单</h2>
      <p v-for="o in selected.workOrders" :key="o.workOrderId">
        <RouterLink :to="'/workorders?id=' + o.workOrderId">{{ o.workOrderNo }}</RouterLink> ·
        {{ label(o.status) }}
      </p>
      <p v-if="!selected.workOrders?.length" class="video-muted">尚无关联工单。</p>
    </section>
    <section class="video-panel">
      <h2>设备异常</h2>
      <div class="video-toolbar">
        <select
          v-model="anomalyStatus"
          aria-label="异常状态"
          @change="
            run(async () => {
              anomalies.page = 1
              await reload()
            })
          "
        >
          <option value="">全部状态</option>
          <option v-for="s in ['ACTIVE', 'RECOVERED', 'DISMISSED']" :key="s" :value="s">
            {{ label(s) }}
          </option></select
        ><button v-if="review" :disabled="busy || !checked.length" @click="group">
          将选中异常归为故障
        </button>
      </div>
      <div class="video-table-wrap">
        <table class="video-table">
          <thead>
            <tr>
              <th>选择</th>
              <th>异常</th>
              <th>设备 / 资源</th>
              <th>状态</th>
              <th>最近观察</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in anomalies.items" :key="a.anomalyId">
              <td>
                <input
                  v-if="review && a.status === 'ACTIVE'"
                  v-model="checked"
                  type="checkbox"
                  :value="a.anomalyId"
                  :aria-label="'选择异常 ' + a.anomalyId"
                />
              </td>
              <td>{{ label(a.anomalyType) }}</td>
              <td>
                {{ a.deviceId }}
                <p class="video-muted">{{ a.resourceKey }}</p>
              </td>
              <td><ValueBadge :value="a.status" /></td>
              <td>{{ formatTime(a.lastObservedAt) }}</td>
              <td>
                <button
                  class="link"
                  @click="
                    run(async () => {
                      anomaly = await core<Anomaly>('/anomalies/' + a.anomalyId)
                    })
                  "
                >
                  证据</button
                ><button
                  v-if="dismissPermission && a.status === 'ACTIVE'"
                  class="link"
                  :disabled="busy"
                  @click="dismiss(a)"
                >
                  排除
                </button>
              </td>
            </tr>
            <tr v-if="!anomalies.items.length">
              <td colspan="6" class="video-muted">当前筛选下没有可见异常。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="video-pager">
        <span>共 {{ anomalies.total }} 项</span
        ><button
          :disabled="busy || anomalies.page <= 1"
          @click="
            run(async () => {
              anomalies.page--
              await reload()
            })
          "
        >
          上一页</button
        ><button
          :disabled="busy || anomalies.page * anomalies.size >= anomalies.total"
          @click="
            run(async () => {
              anomalies.page++
              await reload()
            })
          "
        >
          下一页
        </button>
      </div>
    </section>
    <section v-if="anomaly" class="video-panel">
      <h2>{{ label(anomaly.anomalyType) }} · 证据</h2>
      <button class="link" @click="anomaly = null">收起</button>
      <div v-for="e in anomaly.evidenceReferences" :key="e.evidenceId" class="video-timeline">
        <p>{{ e.evidenceId }}</p>
        <p class="video-muted">
          来源事件 {{ e.eventId }} · 接收于 {{ formatTime(e.receivedAt) }}
        </p>
        <pre>{{ JSON.stringify(e.summaryJson, null, 2) }}</pre>
      </div>
    </section>
  </div>
</template>
