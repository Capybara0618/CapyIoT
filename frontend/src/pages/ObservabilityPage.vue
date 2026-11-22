<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  Activity,
  BellRing,
  ClipboardList,
  ExternalLink,
  HardDrive,
  Radio,
  RefreshCw,
  Server,
} from 'lucide-vue-next'
import { request } from '../api'
import { formatTime } from '../domain'
import PageHeader from '../components/PageHeader.vue'
import MetricTrend from '../components/MetricTrend.vue'

const services = [
  { id: 'gateway', name: 'API 网关', icon: Server },
  { id: 'device-ingestion', name: '设备接入', icon: Radio },
  { id: 'device-service', name: '设备管理', icon: HardDrive },
  { id: 'alarm-service', name: '告警治理', icon: BellRing },
  { id: 'workflow-service', name: '工单协同', icon: ClipboardList },
] as const
const healthLabels = { UP: '就绪', DOWN: '未就绪', UNREACHABLE: '探测失败' } as const
interface Health {
  services: { service: string; status: keyof typeof healthLabels }[]
  checkedAt: string
}
interface Metrics {
  prometheusAvailable: boolean
  series: { id: string; points: { at: number; value: number | null }[] }[]
  checkedAt: string
}
interface Deployment {
  k8sEnabled: boolean
  reachable: boolean
  workloads: { service: string; desired: number; ready: number; available: number }[]
  scaling: { configured: boolean; ready: boolean; active: boolean; min: number; max: number } | null
}
const definitions = [
  { id: 'lag', label: 'Kafka 消费积压', unit: '条', color: '#97641b', hint: 'device-state-group' },
  {
    id: 'tps',
    label: '设备事件处理速率',
    unit: '条/秒',
    color: '#347454',
    hint: '完成状态投影，包括成功重投',
  },
  {
    id: 'httpP95',
    label: 'API 延迟 P95',
    unit: 'ms',
    color: '#2563eb',
    hint: '请求直方图 · 五分钟窗口',
  },
  {
    id: 'heapMiB',
    label: 'JVM 堆使用',
    unit: 'MiB',
    color: '#766b87',
    hint: '所选服务各实例的堆用量',
  },
  { id: 'gcPause', label: 'GC 暂停速率', unit: 'ms/s', color: '#507b7d', hint: '每秒平均暂停时间' },
  {
    id: 'poolActive',
    label: 'HikariCP 活跃连接',
    unit: '条',
    color: '#5d8067',
    hint: '当前借出的连接数',
  },
  {
    id: 'mysqlP95',
    label: 'MySQL 查询路径 P95',
    unit: 'ms',
    color: '#a7793b',
    hint: '设备或告警查询 · 五分钟窗口',
  },
  {
    id: 'cacheHit',
    label: 'Redis 汇总缓存命中率',
    unit: '%',
    color: '#4b7bec',
    hint: '设备汇总 · 五分钟窗口',
  },
] as const
const links = [
  { name: 'Prometheus', url: 'http://localhost:9095' },
  { name: 'Grafana', url: 'http://localhost:3000/d/capyiot' },
  { name: 'Jaeger', url: 'http://localhost:16686' },
]
const health = ref<Health>()
const metrics = ref<Metrics>()
const deployment = ref<Deployment>()
const minutes = ref(15)
const service = ref('device-service')
const loading = ref(false)
const healthError = ref('')
const metricsError = ref('')
const deploymentError = ref('')
const status = computed(
  () => new Map(health.value?.services.map(({ service, status }) => [service, status])),
)
const ready = computed(() => health.value?.services.filter((item) => item.status === 'UP').length)
const metricEnd = computed(() => (metrics.value ? Date.parse(metrics.value.checkedAt) / 1000 : 0))
const series = computed(() => new Map(metrics.value?.series.map((item) => [item.id, item.points])))
const podCounts = computed(() => {
  if (!deployment.value?.reachable) return '—'
  const totals = deployment.value.workloads.reduce(
    (total, item) => ({
      ready: total.ready + item.ready,
      desired: total.desired + item.desired,
    }),
    { ready: 0, desired: 0 },
  )
  return `${totals.ready} / ${totals.desired}`
})
const local = ['localhost', '127.0.0.1'].includes(location.hostname)
const number = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 })
let metricRequest = 0
let timer: ReturnType<typeof setInterval>

function latest(id: string) {
  if (metricsError.value) return '—'
  const point = series.value.get(id)?.at(-1)
  if (!point || point.at < metricEnd.value - Math.max(30, minutes.value)) return '—'
  return point.value === null ? '—' : number.format(point.value)
}
function serviceName(id: string) {
  return services.find((item) => item.id === id)?.name ?? '前端'
}
async function loadMetrics() {
  const id = ++metricRequest
  try {
    const result = await request<Metrics>(
      `/observability/metrics?minutes=${minutes.value}&service=${service.value}`,
    )
    if (id !== metricRequest) return
    metrics.value = result
    metricsError.value = result.prometheusAvailable ? '' : 'Prometheus 暂不可用'
  } catch (failure) {
    if (id === metricRequest) metricsError.value = (failure as Error).message
  }
}
function updateFilters() {
  metrics.value = undefined
  metricsError.value = ''
  void loadMetrics()
}
async function refresh() {
  if (loading.value) return
  loading.value = true
  await Promise.all([
    request<Health>('/observability/services')
      .then((result) => {
        health.value = result
        healthError.value = ''
      })
      .catch((failure: Error) => {
        healthError.value = failure.message
      }),
    request<Deployment>('/observability/deployment')
      .then((result) => {
        deployment.value = result
        deploymentError.value = ''
      })
      .catch((failure: Error) => {
        deployment.value = undefined
        deploymentError.value = failure.message
      }),
    loadMetrics(),
  ])
  loading.value = false
}
onMounted(() => {
  void refresh()
  timer = setInterval(() => {
    if (!document.hidden) void refresh()
  }, 15000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <section class="network-page observability-page">
    <PageHeader>
      <div class="network-controls">
        <select v-model="service" aria-label="监控服务" @change="updateFilters">
          <option v-for="item in services" :key="item.id" :value="item.id">{{ item.name }}</option>
        </select>
        <select v-model.number="minutes" aria-label="监控时间范围" @change="updateFilters">
          <option :value="15">近 15 分钟</option>
          <option :value="60">近 1 小时</option>
          <option :value="360">近 6 小时</option>
        </select>
      </div>
      <button class="scenario-secondary" type="button" :disabled="loading" @click="refresh">
        <RefreshCw :size="15" /> {{ loading ? '正在探测…' : '刷新探测' }}
      </button>
    </PageHeader>
    <div v-if="healthError" class="network-error" role="alert">{{ healthError }}</div>
    <div class="network-metrics">
      <article class="network-metric">
        <Server :size="22" />
        <div>
          <small>Kubernetes 就绪副本</small><strong>{{ podCounts }}</strong>
        </div>
      </article>
      <article class="network-metric green">
        <Activity :size="22" />
        <div>
          <small>就绪服务</small
          ><strong>{{ health ? `${ready} / ${health.services.length}` : '—' }}</strong>
        </div>
      </article>
      <article class="network-metric amber">
        <Radio :size="22" />
        <div>
          <small>Kafka 消费积压</small><strong>{{ latest('lag') }}</strong>
        </div>
      </article>
      <article class="network-metric">
        <HardDrive :size="22" />
        <div>
          <small>设备事件处理速率</small><strong>{{ latest('tps') }}<small> 条/秒</small></strong>
        </div>
      </article>
    </div>
    <div class="monitor-summary">
      <section class="network-panel">
        <div class="network-toolbar">
          <h2><Activity :size="17" /> 服务就绪状态</h2>
          <small v-if="health"
            >{{ ready }} / {{ health.services.length }} 就绪 ·
            {{ formatTime(health.checkedAt) }}</small
          >
        </div>
        <ul class="observability-list monitor-health-list">
          <li v-for="item in services" :key="item.id">
            <component :is="item.icon" :size="18" />
            <strong>{{ item.name }}</strong>
            <span
              class="network-chip"
              :class="status.get(item.id) === 'UP' ? 'green' : status.has(item.id) ? 'red' : ''"
            >
              {{ healthLabels[status.get(item.id)!] ?? '待探测' }}
            </span>
          </li>
        </ul>
      </section>
      <section class="network-panel">
        <div class="network-toolbar"><h2>部署与扩缩容</h2></div>
        <p v-if="deploymentError" class="network-note">{{ deploymentError }}</p>
        <p v-else-if="!deployment" class="network-note">正在读取部署状态…</p>
        <p v-else-if="!deployment.k8sEnabled" class="network-note">
          当前为 Compose 模式，Kubernetes 副本与 KEDA 状态不可用。
        </p>
        <p v-else-if="!deployment.reachable" class="network-note">
          暂时无法读取 Kubernetes 部署状态。
        </p>
        <template v-else>
          <p class="network-note">
            {{
              deployment.scaling?.configured
                ? `KEDA ${deployment.scaling.ready ? (deployment.scaling.active ? '积压触发中' : '已就绪') : '未就绪'} · ${deployment.scaling.min}–${deployment.scaling.max} 个设备管理副本`
                : '当前部署未启用 KEDA'
            }}
          </p>
          <ul class="scenario-list monitor-workloads">
            <li v-for="workload in deployment.workloads" :key="workload.service">
              <span>{{ serviceName(workload.service) }}</span>
              <span
                class="network-chip"
                :class="workload.ready >= workload.desired ? 'green' : 'amber'"
                >{{ workload.ready }} / {{ workload.desired }} 就绪</span
              >
            </li>
          </ul>
        </template>
        <div v-if="local" class="scenario-buttons monitor-links">
          <a
            v-for="link in links"
            :key="link.name"
            class="scenario-secondary"
            :href="link.url"
            target="_blank"
            rel="noopener noreferrer"
            >{{ link.name }} <ExternalLink :size="13"
          /></a>
        </div>
      </section>
    </div>
    <div v-if="metricsError" class="network-panel monitor-unavailable" role="status">
      <Activity :size="23" />
      <div>
        <strong>{{ metricsError }}</strong>
        <p v-if="deployment && !deployment.k8sEnabled">
          运行 <code>docker compose --profile monitoring up -d</code> 启动采集。
        </p>
        <p v-else>检查 Prometheus 服务和采集状态后重试。</p>
      </div>
    </div>
    <div v-else-if="metrics?.prometheusAvailable" class="monitor-grid" aria-label="Prometheus 指标">
      <section v-for="metric in definitions" :key="metric.id" class="network-panel">
        <div class="network-toolbar">
          <h2>{{ metric.label }}</h2>
        </div>
        <div class="monitor-reading">
          <strong>{{ latest(metric.id) }}</strong> {{ metric.unit }}<small>{{ metric.hint }}</small>
        </div>
        <MetricTrend
          v-if="series.get(metric.id)?.some((point) => point.value !== null)"
          :points="series.get(metric.id) ?? []"
          :from="metricEnd - minutes * 60"
          :to="metricEnd"
          :label="metric.label"
          :color="metric.color"
        />
        <p v-else class="monitor-no-data">所选时间内暂无有效样本</p>
      </section>
    </div>
    <p v-else class="network-note" role="status">正在读取 Prometheus 指标…</p>
  </section>
</template>
