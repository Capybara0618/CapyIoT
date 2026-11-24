<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import ValueBadge from '../components/ValueBadge.vue'
import { core, label, loadMe, query, type Event, type Page } from '../video'
import { formatTime } from '../domain'
const route = useRoute(),
  filters = reactive({
    deviceId: String(route.query.deviceId ?? ''),
    eventType: '',
    status: '',
    from: '',
    to: '',
  })
const rows = ref<Page<Event>>({ items: [], total: 0, page: 1, size: 20 }),
  selected = ref<Event | null>(null),
  error = ref(''),
  busy = ref(false)
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
async function load(page = rows.value.page) {
  rows.value = await core<Page<Event>>(
    '/events?' +
      query({
        ...filters,
        from: filters.from ? new Date(filters.from).toISOString() : undefined,
        to: filters.to ? new Date(filters.to).toISOString() : undefined,
        page,
      }),
  )
}
onMounted(() =>
  run(async () => {
    await loadMe()
    await load(1)
  }),
)
</script>
<template>
  <div class="video-page">
    <PageHeader><button :disabled="busy" @click="run(() => load())">刷新</button></PageHeader>
    <p v-if="error" class="video-error" role="alert">{{ error }}</p>
    <section class="video-panel">
      <form class="video-toolbar" @submit.prevent="run(() => load(1))">
        <input v-model="filters.deviceId" aria-label="设备编号" placeholder="设备编号" /><select
          v-model="filters.eventType"
          aria-label="事件类型"
        >
          <option value="">全部业务事件</option>
          <option
            v-for="t in ['STATUS_OBSERVATION', 'DEVICE_FAULT', 'DEVICE_RECOVERED']"
            :key="t"
            :value="t"
          >
            {{ label(t) }}
          </option></select
        ><select v-model="filters.status" aria-label="处理结果">
          <option value="">全部处理结果</option>
          <option value="APPLIED">已应用</option>
          <option value="IGNORED_STALE">已忽略旧序号</option></select
        ><label>接收起始<input v-model="filters.from" type="datetime-local" /></label
        ><label>接收截止<input v-model="filters.to" type="datetime-local" /></label
        ><button :disabled="busy">查询</button>
      </form>
      <p class="video-muted">
        发生时间由设备提供，接收时间由服务端记录。不同状态流分别计数；心跳用于连接判断，不作为长期业务事件保存。
      </p>
      <div class="video-table-wrap">
        <table class="video-table">
          <thead>
            <tr>
              <th>事件</th>
              <th>设备</th>
              <th>状态流 / 序号</th>
              <th>发生时间</th>
              <th>接收时间</th>
              <th>处理</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="e in rows.items" :key="e.eventId">
              <td>{{ label(e.eventType) }}</td>
              <td>{{ e.deviceId }}</td>
              <td>
                {{ e.streamKey }}
                <p class="video-muted">会话代次 {{ e.sessionEpoch }} · 序号 {{ e.sequence }}</p>
              </td>
              <td>{{ formatTime(e.occurredAt) }}</td>
              <td>{{ formatTime(e.receivedAt) }}</td>
              <td><ValueBadge :value="e.processingStatus" /></td>
              <td>
                <button
                  class="link"
                  @click="
                    run(async () => {
                      selected = await core<Event>('/events/' + e.eventId)
                    })
                  "
                >
                  详情
                </button>
              </td>
            </tr>
            <tr v-if="!rows.items.length">
              <td colspan="7" class="video-muted">暂无符合条件的业务事件。</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="video-pager">
        <span>共 {{ rows.total }} 项</span
        ><button :disabled="busy || rows.page <= 1" @click="run(() => load(rows.page - 1))">
          上一页</button
        ><button
          :disabled="busy || rows.page * rows.size >= rows.total"
          @click="run(() => load(rows.page + 1))"
        >
          下一页
        </button>
      </div>
    </section>
    <section v-if="selected" class="video-panel">
      <h2>事件详情</h2>
      <dl>
        <dt>事件 ID</dt>
        <dd>{{ selected.eventId }}</dd>
        <dt>追踪 ID</dt>
        <dd>{{ selected.traceId }}</dd>
      </dl>
      <pre>{{ JSON.stringify(selected.payload, null, 2) }}</pre>
      <button @click="selected = null">收起</button>
    </section>
  </div>
</template>
