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

const services = [
  { id: 'gateway', name: 'API 网关', role: '认证与业务路由', icon: Server },
  { id: 'device-ingestion', name: '设备接入', role: 'MQTT 消息接收', icon: Radio },
  { id: 'device-service', name: '设备管理', role: '设备状态与离线巡检', icon: HardDrive },
  { id: 'alarm-service', name: '告警治理', role: '告警关联与发布', icon: BellRing },
  { id: 'workflow-service', name: '工单协同', role: '工单流转与恢复验证', icon: ClipboardList },
] as const
const labels = { UP: '就绪', DOWN: '未就绪', UNREACHABLE: '探测失败' } as const
interface Snapshot {
  services: { service: string; status: keyof typeof labels }[]
  checkedAt: string
}
const snapshot = ref<Snapshot>()
const loading = ref(false)
const error = ref('')
const statusByService = computed(
  () => new Map(snapshot.value?.services.map(({ service, status }) => [service, status])),
)
const ready = computed(() => snapshot.value?.services.filter((item) => item.status === 'UP').length)
const local = ['localhost', '127.0.0.1'].includes(location.hostname)
let timer: ReturnType<typeof setInterval>

async function refresh() {
  if (loading.value) return
  loading.value = true
  try {
    snapshot.value = await request<Snapshot>('/observability/services')
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  } finally {
    loading.value = false
  }
}
onMounted(() => {
  void refresh()
  timer = setInterval(() => {
    void refresh()
  }, 15000)
})
onBeforeUnmount(() => clearInterval(timer))
</script>

<template>
  <section class="network-page observability-page">
    <PageHeader>
      <button class="scenario-secondary" type="button" :disabled="loading" @click="refresh">
        <RefreshCw :size="15" /> {{ loading ? '正在探测…' : '刷新探测' }}
      </button>
    </PageHeader>
    <div v-if="error" class="network-error" role="alert">{{ error }}</div>
    <div class="network-toolbar observability-status">
      <h2><Activity :size="18" /> 服务就绪状态</h2>
      <span v-if="snapshot"
        >{{ ready }} / {{ snapshot.services.length }} 就绪 ·
        {{ formatTime(snapshot.checkedAt) }}</span
      >
      <span v-else role="status">{{ error ? '暂无探测结果' : '正在读取服务状态…' }}</span>
    </div>
    <div class="observability-services">
      <section
        v-for="service in services"
        :key="service.id"
        class="network-metric"
        :class="{
          green: statusByService.get(service.id) === 'UP',
          red: statusByService.has(service.id) && statusByService.get(service.id) !== 'UP',
        }"
      >
        <component :is="service.icon" :size="22" />
        <div>
          <small>{{ service.name }}</small><strong>{{
            labels[statusByService.get(service.id)!] ?? '—'
          }}</strong>
        </div>
      </section>
    </div>
    <div class="observability-columns">
      <section class="network-panel">
        <div class="network-toolbar">
          <h2>运行服务</h2>
          <small>Spring Boot 就绪探针</small>
        </div>
        <ul class="observability-list">
          <li v-for="service in services" :key="service.id">
            <component :is="service.icon" :size="18" />
            <div>
              <strong>{{ service.name }}</strong><small>{{ service.role }}</small>
            </div>
            <span
              v-if="statusByService.has(service.id)"
              class="network-chip"
              :class="statusByService.get(service.id) === 'UP' ? 'green' : 'red'"
              >{{ labels[statusByService.get(service.id)!] }}</span
            >
            <span v-else class="network-chip">待探测</span>
          </li>
        </ul>
      </section>
      <aside class="network-side">
        <section class="network-panel">
          <div class="network-toolbar"><h2>监控面板</h2></div>
          <div class="observability-empty">
            <Activity :size="26" />
            <p>
              本页每 15 秒读取服务就绪状态。启用 Compose 的 monitoring profile 后，可在 Prometheus
              和 Grafana 查看基础 JVM 指标。
            </p>
            <code>docker compose --profile monitoring up -d</code>
            <div v-if="local" class="scenario-buttons">
              <a
                class="scenario-secondary"
                href="http://localhost:9095"
                target="_blank"
                rel="noopener noreferrer"
                >Prometheus <ExternalLink :size="14"
              /></a>
              <a
                class="scenario-secondary"
                href="http://localhost:3000"
                target="_blank"
                rel="noopener noreferrer"
                >Grafana <ExternalLink :size="14"
              /></a>
            </div>
          </div>
        </section>
        <section class="network-panel">
          <div class="network-toolbar"><h2>部署与扩缩容</h2></div>
          <p class="network-note">
            当前为 Docker Compose 演示环境，Pod、KEDA 和 Kafka 积压采集待接入。
          </p>
        </section>
      </aside>
    </div>
  </section>
</template>
