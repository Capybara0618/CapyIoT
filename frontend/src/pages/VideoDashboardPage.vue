<script setup lang="ts">
import { onMounted, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import ValueBadge from '../components/ValueBadge.vue'
import { core, label, loadMe, me, type Incident, type Order, type Page } from '../video'
const summary = ref<{
  total: number
  online: number
  offline: number
  unknown: number
  abnormal: number
  stale: number
} | null>(null)
const incidents = ref<Incident[]>([]),
  orders = ref<Order[]>([]),
  activeAnomalies = ref<number | null>(null)
const error = ref(''),
  busy = ref(false)
async function load() {
  busy.value = true
  error.value = ''
  summary.value = null
  activeAnomalies.value = null
  incidents.value = []
  orders.value = []
  try {
    await loadMe()
    const permissions = me.value?.permissionCodes ?? []
    const tasks: Promise<void>[] = []
    if (permissions.includes('device.read'))
      tasks.push(
        core<typeof summary.value>('/devices/summary').then((v) => {
          summary.value = v
        }),
      )
    if (permissions.includes('anomaly.read'))
      tasks.push(
        core<Page<unknown>>('/anomalies?status=ACTIVE&size=1').then((v) => {
          activeAnomalies.value = v.total
        }),
      )
    if (permissions.includes('incident.read'))
      tasks.push(
        core<Page<Incident>>('/incidents?size=8').then((v) => {
          incidents.value = v.items
        }),
      )
    if (permissions.includes('workorder.read'))
      tasks.push(
        core<Page<Order>>('/workorders?size=8').then((v) => {
          orders.value = v.items
        }),
      )
    const results = await Promise.allSettled(tasks)
    const failure = results.find((r) => r.status === 'rejected')
    if (failure?.status === 'rejected')
      error.value = '部分数据未能加载：' + (failure.reason as Error).message
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    busy.value = false
  }
}
onMounted(load)
</script>
<template>
  <div class="video-page">
    <PageHeader><button :disabled="busy" @click="load">刷新概览</button></PageHeader>
    <p v-if="error" class="video-error" role="alert">{{ error }}</p>
    <p class="video-muted">{{ me?.displayName }}，这里展示你当前有权查看的设备、故障与维修任务。</p>
    <div v-if="summary" class="video-metrics">
      <section class="video-metric">
        <span>纳管设备</span><strong>{{ summary.total }}</strong
        ><span class="video-muted">IPC / NVR / 交换机</span>
      </section>
      <section class="video-metric">
        <span>在线设备</span><strong>{{ summary.online }}</strong
        ><span class="video-muted">离线 {{ summary.offline }} · 未知 {{ summary.unknown }}</span>
      </section>
      <section class="video-metric">
        <span>健康异常设备</span><strong>{{ summary.abnormal }}</strong
        ><span class="video-muted">在线不代表业务健康</span>
      </section>
      <section class="video-metric">
        <span>活动异常</span><strong>{{ activeAnomalies ?? '—' }}</strong
        ><span class="video-muted">数据陈旧设备 {{ summary.stale }}</span>
      </section>
    </div>
    <section v-if="me?.roleCodes.includes('SYS_ADMIN')" class="video-panel">
      <h2>平台管理</h2>
      <p>平台管理员管理账号和授权。查看园区业务需另行授予对应角色和数据范围。</p>
      <RouterLink to="/administration">管理用户与授权</RouterLink>
    </section>
    <div v-if="summary" class="video-grid">
      <section class="video-panel">
        <h2>近期故障</h2>
        <div v-for="i in incidents" :key="i.incidentId" class="video-timeline">
          <RouterLink :to="'/alarms?id=' + i.incidentId">{{ i.title }}</RouterLink>
          <p class="video-muted">
            {{ i.incidentNo }} · {{ i.priority }} · {{ label(i.rootCauseStatus) }}
          </p>
          <ValueBadge :value="i.status" />
        </div>
        <p v-if="!incidents.length" class="video-muted">暂无可见故障。</p>
      </section>
      <section class="video-panel">
        <h2>近期维修任务</h2>
        <div v-for="o in orders" :key="o.workOrderId" class="video-timeline">
          <RouterLink :to="'/workorders?id=' + o.workOrderId"
            >{{ o.workOrderNo }} · {{ o.title }}</RouterLink
          >
          <p class="video-muted">{{ o.deviceItems.length }} 台设备 · {{ label(o.sourceType) }}</p>
          <ValueBadge :value="o.status" />
        </div>
        <p v-if="!orders.length" class="video-muted">暂无可见工单。</p>
      </section>
    </div>
    <section class="video-panel">
      <h2>设备接入与运维流程</h2>
      <p>
        注册资产与点位 → 建立网络、供电和录像关系 → 设备上报状态 → 研判异常与故障 → 创建维修工单 →
        独立验收 → 核查并关闭故障。
      </p>
      <p class="video-muted">
        当前没有实体设备，接入数据来自虚拟设备。异常关联是研判线索，根因须结合证据人工确认。
      </p>
    </section>
  </div>
</template>
