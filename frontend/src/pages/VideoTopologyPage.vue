<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import VideoTopologyGraph from '../components/VideoTopologyGraph.vue'
import {
  allowed,
  command,
  core,
  label,
  loadMe,
  query,
  type Area,
  type Binding,
  type Device,
  type Page,
  type Point,
  type Relation,
  type Topology,
} from '../video'
import { formatTime } from '../domain'
const route = useRoute(),
  manage = allowed('topology.manage')
const devices = ref<Device[]>([]),
  areas = ref<Area[]>([]),
  points = ref<Point[]>([]),
  bindings = ref<Binding[]>([])
const graph = ref<Topology | null>(null),
  target = ref(String(route.query.deviceId ?? '')),
  asOf = ref(''),
  depth = ref(2)
const error = ref(''),
  busy = ref(false)
const relation = reactive({
  kind: 'NETWORK',
  source: '',
  target: '',
  port: '',
  channel: 1,
  reason: '',
})
const point = reactive({ pointCode: '', name: '', areaId: '' }),
  bind = reactive({ pointId: '', deviceId: '', reason: '' })
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
async function load() {
  devices.value = (await core<Page<Device>>('/devices?size=100')).items
  areas.value = await core<Area[]>('/areas')
  points.value = await core<Point[]>('/monitoring-points')
}
async function inspect() {
  if (!target.value) return
  graph.value = await core<Topology>(
    '/topology/context?' +
      query({
        deviceId: target.value,
        depth: depth.value,
        asOf: asOf.value ? new Date(asOf.value).toISOString() : undefined,
      }),
  )
}
async function link() {
  await run(async () => {
    const body =
      relation.kind === 'NETWORK'
        ? {
            upstreamSwitchId: relation.source,
            downstreamDeviceId: relation.target,
            upstreamPortRef: relation.port || null,
            reason: relation.reason,
          }
        : relation.kind === 'POWER'
          ? {
              poeSwitchId: relation.source,
              ipcDeviceId: relation.target,
              poePort: relation.port,
              reason: relation.reason,
            }
          : {
              nvrDeviceId: relation.source,
              ipcDeviceId: relation.target,
              channelNo: relation.channel,
              reason: relation.reason,
            }
    await command(
      relation.kind === 'NETWORK'
        ? '/network-dependencies'
        : relation.kind === 'POWER'
          ? '/power-dependencies'
          : '/video-channel-associations',
      body,
    )
    target.value = relation.source
    asOf.value = ''
    await inspect()
  })
}
async function end(edge: Relation) {
  const reason = window.prompt('请说明结束这条关系的原因。历史记录会保留。')
  if (!reason) return
  await run(async () => {
    const kind =
      edge.relationType === 'NETWORK'
        ? 'network-dependencies'
        : edge.relationType === 'POWER'
          ? 'power-dependencies'
          : 'video-channel-associations'
    await command('/' + kind + '/' + edge.relationId + '/end', {
      expectedVersion: edge.version,
      reason,
    })
    await inspect()
  })
}
async function createPoint() {
  await run(async () => {
    await command('/monitoring-points', point)
    await load()
  })
}
async function bindPoint() {
  const p = points.value.find((p) => p.monitoringPointId === bind.pointId)
  if (!p) return
  await run(async () => {
    const device = await core<Device>('/devices/' + bind.deviceId)
    await command('/monitoring-points/' + bind.pointId + '/bindings', {
      ipcDeviceId: bind.deviceId,
      reason: bind.reason,
      expectedPointVersion: p.version,
      expectedDeviceVersion: device.version,
    })
    await load()
    await readBindings()
  })
}
async function readBindings() {
  if (bind.pointId)
    bindings.value = await core<Binding[]>('/monitoring-points/' + bind.pointId + '/bindings')
}
async function unbind(b: Binding) {
  const reason = window.prompt('请说明解除点位绑定的原因')
  if (!reason) return
  await run(async () => {
    await command('/monitoring-point-bindings/' + b.bindingId + '/end', {
      reason,
      expectedVersion: b.version,
    })
    await load()
    await readBindings()
  })
}
const deviceName = (id: string) => devices.value.find((d) => d.deviceId === id)?.name ?? id
onMounted(() =>
  run(async () => {
    await loadMe()
    await load()
    if (!target.value) target.value = devices.value[0]?.deviceId ?? ''
    await inspect()
  }),
)
</script>
<template>
  <div class="video-page">
    <PageHeader />
    <p v-if="error" class="video-error" role="alert">{{ error }}</p>
    <section class="video-panel">
      <form class="video-toolbar" @submit.prevent="run(inspect)">
        <select v-model="target" required aria-label="中心设备">
          <option value="">请选择设备</option>
          <option v-for="d in devices" :key="d.deviceId" :value="d.deviceId">
            {{ d.name }} · {{ d.assetCode }}
          </option></select
        ><label>历史时刻<input v-model="asOf" type="datetime-local" /></label
        ><label
          >关系深度<select v-model="depth">
            <option v-for="n in 3" :key="n" :value="n">{{ n }} 层</option>
          </select></label
        ><button :disabled="busy">查看</button>
      </form>
      <p v-if="graph?.completeness === 'INCOMPLETE'" class="video-notice">
        当前只展示授权范围内的关系，不能据此判断全部影响范围。
      </p>
      <p v-if="graph" class="video-muted">
        关系时刻：{{ formatTime(graph.asOf) }}；节点状态为当前状态。最多展示 100 个可见节点。
      </p>
      <VideoTopologyGraph
        v-if="graph?.nodes.length"
        :topology="graph"
        @select="
          (id) => {
            target = id
            run(inspect)
          }
        "
      />
      <div v-if="graph" class="video-table-wrap">
        <table class="video-table">
          <thead>
            <tr>
              <th>关系</th>
              <th>上游 / 来源</th>
              <th>下游 / 目标</th>
              <th>端口或通道</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in graph.relations" :key="r.relationId">
              <td>{{ label(r.relationType) }}</td>
              <td>{{ deviceName(r.sourceDeviceId) }}</td>
              <td>{{ deviceName(r.targetDeviceId) }}</td>
              <td>{{ r.upstreamPortRef ?? r.poePort ?? r.channelNo ?? '—' }}</td>
              <td>
                <button v-if="manage && !asOf" class="link danger" :disabled="busy" @click="end(r)">
                  结束关系
                </button>
              </td>
            </tr>
            <tr v-if="!graph.relations.length">
              <td colspan="5" class="video-muted">
                没有可见的有效关系。网络、PoE 供电和录像关系分别维护。
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
    <div v-if="manage" class="video-grid">
      <section class="video-panel">
        <h2>新增关系</h2>
        <form class="video-form" @submit.prevent="link">
          <label
            >关系类型<select v-model="relation.kind">
              <option v-for="k in ['NETWORK', 'POWER', 'VIDEO']" :key="k" :value="k">
                {{ label(k) }}
              </option>
            </select></label
          ><label
            >上游设备<select v-model="relation.source" required>
              <option value="">请选择</option>
              <option
                v-for="d in devices.filter(
                  (d) => d.deviceType === (relation.kind === 'VIDEO' ? 'NVR' : 'SWITCH'),
                )"
                :key="d.deviceId"
                :value="d.deviceId"
              >
                {{ d.name }}
              </option>
            </select></label
          ><label
            >目标设备<select v-model="relation.target" required>
              <option value="">请选择</option>
              <option
                v-for="d in devices.filter(
                  (d) => relation.kind === 'NETWORK' || d.deviceType === 'IPC',
                )"
                :key="d.deviceId"
                :value="d.deviceId"
              >
                {{ d.name }}
              </option>
            </select></label
          ><label v-if="relation.kind !== 'VIDEO'"
            >上游端口<input
              v-model="relation.port"
              :required="relation.kind === 'POWER'"
              maxlength="64" /></label
          ><label v-else
            >录像通道号<input
              v-model.number="relation.channel"
              type="number"
              min="1"
              required /></label
          ><label
            >变更原因<textarea
              v-model="relation.reason"
              required
              maxlength="1000"
            ></textarea></label
          ><button class="primary" :disabled="busy">新增关系</button>
        </form>
      </section>
      <section class="video-panel">
        <h2>监控点位</h2>
        <details>
          <summary>新增点位</summary>
          <form class="video-form" @submit.prevent="createPoint">
            <label
              >点位编号<input
                v-model="point.pointCode"
                required
                pattern="[A-Za-z0-9_-]{1,64}" /></label
            ><label>名称<input v-model="point.name" required maxlength="100" /></label
            ><label
              >区域<select v-model="point.areaId" required>
                <option value="">请选择</option>
                <option v-for="a in areas" :key="a.areaId" :value="a.areaId">{{ a.name }}</option>
              </select></label
            ><button :disabled="busy">保存点位</button>
          </form>
        </details>
        <form class="video-form" @submit.prevent="bindPoint">
          <label
            >选择点位<select v-model="bind.pointId" required @change="run(readBindings)">
              <option value="">请选择</option>
              <option v-for="p in points" :key="p.monitoringPointId" :value="p.monitoringPointId">
                {{ p.name }} · {{ p.pointCode }}
              </option>
            </select></label
          ><label
            >绑定摄像机<select v-model="bind.deviceId" required>
              <option value="">请选择 IPC</option>
              <option
                v-for="d in devices.filter((d) => d.deviceType === 'IPC')"
                :key="d.deviceId"
                :value="d.deviceId"
              >
                {{ d.name }}
              </option>
            </select></label
          ><label
            >绑定或更换原因<textarea
              v-model="bind.reason"
              required
              maxlength="1000"
            ></textarea></label
          ><button :disabled="busy">保存绑定</button>
        </form>
        <div v-for="b in bindings" :key="b.bindingId" class="video-timeline">
          <p>{{ deviceName(b.ipcDeviceId) }} · {{ b.validTo ? '历史绑定' : '当前绑定' }}</p>
          <span class="video-muted"
            >{{ formatTime(b.validFrom) }} — {{ b.validTo ? formatTime(b.validTo) : '至今' }}</span
          ><button v-if="!b.validTo" class="link danger" :disabled="busy" @click="unbind(b)">
            解除
          </button>
        </div>
      </section>
    </div>
  </div>
</template>
