<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import ValueBadge from '../components/ValueBadge.vue'
import {
  allowed,
  command,
  core,
  label,
  loadMe,
  query,
  type Area,
  type Device,
  type Page,
} from '../video'
import { formatTime } from '../domain'

const canManage = allowed('device.manage')
const rows = ref<Page<Device>>({ items: [], total: 0, page: 1, size: 20 })
const areas = ref<Area[]>([]),
  selected = ref<Device | null>(null)
const history = ref<
  { stateDimension: string; oldValue: string; newValue: string; receivedAt: string }[]
>([])
const error = ref(''),
  busy = ref(false),
  secret = ref(''),
  secretId = ref('')
const filters = reactive({ keyword: '', deviceType: '', assetStatus: '', connectivityStatus: '' })
const create = reactive({
  assetCode: '',
  deviceType: 'IPC',
  name: '',
  areaId: '',
  poeInput: true,
  poeOutput: false,
})
const edit = reactive({ name: '', assetStatus: '' })
const area = reactive({ areaCode: '', name: '', areaType: 'AREA', parentAreaId: '' })
async function run(task: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await task()
  } catch (cause) {
    error.value = (cause as Error).message
  } finally {
    busy.value = false
  }
}
async function reload(page = rows.value.page) {
  rows.value = await core<Page<Device>>('/devices?' + query({ ...filters, page, size: 20 }))
}
async function open(id: string) {
  selected.value = await core<Device>('/devices/' + id)
  Object.assign(edit, { name: selected.value.name, assetStatus: selected.value.assetStatus })
  history.value = (
    await core<Page<(typeof history.value)[number]>>('/devices/' + id + '/status-history?size=20')
  ).items
}
async function register() {
  await run(async () => {
    const result = await command<{ device: Device; deviceSecret?: string }>('/devices', {
      assetCode: create.assetCode,
      deviceType: create.deviceType,
      name: create.name,
      areaId: create.areaId,
      capabilities: {
        poeInput: create.deviceType === 'IPC' && create.poeInput,
        poeOutput: create.deviceType === 'SWITCH' && create.poeOutput,
      },
    })
    secret.value = result.deviceSecret ?? ''
    secretId.value = result.device.deviceId
    await reload(1)
    await open(result.device.deviceId)
  })
}
async function save() {
  const device = selected.value
  if (!device) return
  await run(async () => {
    await command(
      '/devices/' + device.deviceId,
      { ...edit, expectedVersion: device.version },
      'PATCH',
    )
    await open(device.deviceId)
    await reload()
  })
}
async function rotate() {
  const device = selected.value
  if (!device) return
  const reason = window.prompt('请填写更换设备密钥的原因。旧连接将被撤销。')
  if (!reason) return
  await run(async () => {
    const result = await command<{ deviceSecret?: string }>(
      '/devices/' + device.deviceId + '/credential/rotate',
      { expectedVersion: device.version, reason },
    )
    secret.value = result.deviceSecret ?? ''
    secretId.value = device.deviceId
    await open(device.deviceId)
  })
}
async function addArea() {
  await run(async () => {
    await command('/areas', { ...area, parentAreaId: area.parentAreaId || null })
    areas.value = await core<Area[]>('/areas')
    area.areaCode = ''
    area.name = ''
  })
}
onMounted(() =>
  run(async () => {
    await loadMe()
    areas.value = await core<Area[]>('/areas')
    await reload(1)
  }),
)
</script>

<template>
  <div class="video-page">
    <PageHeader><button :disabled="busy" @click="run(() => reload())">刷新</button></PageHeader>
    <p v-if="error" class="video-error" role="alert">{{ error }}</p>
    <section v-if="secret" class="video-panel" role="status">
      <h2>请立即保存设备密钥</h2>
      <p class="video-muted">
        设备 {{ secretId }}。密钥仅本次显示，不会自动写入浏览器存储；关闭后无法再次查询。
      </p>
      <p class="secret">{{ secret }}</p>
      <button @click="secret = ''">已保存，隐藏密钥</button>
    </section>
    <section class="video-panel">
      <form class="video-toolbar" @submit.prevent="run(() => reload(1))">
        <input v-model="filters.keyword" aria-label="搜索设备" placeholder="名称或资产编号" />
        <select v-model="filters.deviceType" aria-label="设备类型">
          <option value="">全部类型</option>
          <option v-for="kind in ['IPC', 'NVR', 'SWITCH']" :key="kind" :value="kind">
            {{ label(kind) }}
          </option>
        </select>
        <select v-model="filters.connectivityStatus" aria-label="连接状态">
          <option value="">全部连接状态</option>
          <option v-for="s in ['ONLINE', 'OFFLINE', 'UNKNOWN']" :key="s" :value="s">
            {{ label(s) }}
          </option>
        </select>
        <button type="submit" :disabled="busy">查询</button>
      </form>
      <div class="video-table-wrap">
        <table class="video-table">
          <thead>
            <tr>
              <th>设备 / 资产编号</th>
              <th>类型 / 位置</th>
              <th>资产状态</th>
              <th>连接</th>
              <th>健康</th>
              <th>数据</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in rows.items" :key="d.deviceId">
              <td>
                {{ d.name }}
                <p class="video-muted">{{ d.assetCode }}</p>
              </td>
              <td>
                {{ label(d.deviceType) }}
                <p class="video-muted">{{ d.areaName }}</p>
              </td>
              <td><ValueBadge :value="d.assetStatus" /></td>
              <td><ValueBadge :value="d.connectivityStatus" /></td>
              <td><ValueBadge :value="d.healthStatus" /></td>
              <td><ValueBadge :value="d.dataFreshness" /></td>
              <td><button class="link" @click="run(() => open(d.deviceId))">详情</button></td>
            </tr>
            <tr v-if="!rows.items.length">
              <td colspan="7" class="video-muted">
                暂无可见设备。可注册设备，或由主管分派维修任务后查看。
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="video-pager">
        <span>共 {{ rows.total }} 台</span
        ><button :disabled="busy || rows.page <= 1" @click="run(() => reload(rows.page - 1))">
          上一页</button
        ><span>{{ rows.page }}</span
        ><button
          :disabled="busy || rows.page * rows.size >= rows.total"
          @click="run(() => reload(rows.page + 1))"
        >
          下一页
        </button>
      </div>
    </section>
    <section v-if="selected" class="video-panel">
      <div class="video-actions">
        <h2>{{ selected.name }}</h2>
        <RouterLink :to="'/topology?deviceId=' + selected.deviceId">查看关系</RouterLink
        ><RouterLink :to="'/events?deviceId=' + selected.deviceId">查看事件</RouterLink
        ><RouterLink :to="'/workorders?deviceId=' + selected.deviceId">维修历史</RouterLink
        ><button class="link" @click="selected = null">收起</button>
      </div>
      <dl>
        <dt>设备编号</dt>
        <dd>{{ selected.deviceId }}</dd>
        <dt>运行模式</dt>
        <dd>{{ label(selected.operatingMode) }}</dd>
        <dt>最近心跳</dt>
        <dd>{{ formatTime(selected.lastHeartbeatReceivedAt) }}</dd>
        <dt>最近业务数据</dt>
        <dd>{{ formatTime(selected.lastBusinessDataReceivedAt) }}</dd>
      </dl>
      <form
        v-if="canManage && selected.assetStatus !== 'RETIRED'"
        class="video-toolbar"
        @submit.prevent="save"
      >
        <input v-model="edit.name" required maxlength="100" aria-label="设备名称" /><select
          v-model="edit.assetStatus"
          aria-label="资产状态"
        >
          <option
            v-for="s in ['REGISTERED', 'IN_SERVICE', 'OUT_OF_SERVICE', 'RETIRED']"
            :key="s"
            :value="s"
          >
            {{ label(s) }}
          </option></select
        ><button class="primary" :disabled="busy">保存</button
        ><button type="button" :disabled="busy" @click="rotate">更换设备密钥</button>
      </form>
      <p class="video-muted">
        停用或退役会撤销设备连接。退役还会结束当前关系，保留历史且不可重新启用。
      </p>
      <h2>状态变化</h2>
      <div v-for="(h, i) in history" :key="i" class="video-timeline">
        <p>{{ h.stateDimension }}：{{ label(h.oldValue) }} → {{ label(h.newValue) }}</p>
        <span class="video-muted">接收于 {{ formatTime(h.receivedAt) }}</span>
      </div>
      <p v-if="!history.length" class="video-muted">尚无状态变化记录。</p>
    </section>
    <div v-if="canManage" class="video-grid">
      <details class="video-panel">
        <summary>注册设备</summary>
        <form class="video-form" @submit.prevent="register">
          <label
            >资产编号<input
              v-model="create.assetCode"
              required
              pattern="[A-Za-z0-9_-]{1,64}" /></label
          ><label>设备名称<input v-model="create.name" required maxlength="100" /></label>
          <label
            >类型<select v-model="create.deviceType">
              <option v-for="kind in ['IPC', 'NVR', 'SWITCH']" :key="kind" :value="kind">
                {{ label(kind) }}
              </option>
            </select></label
          >
          <label
            >安装区域<select v-model="create.areaId" aria-label="安装区域" required>
              <option value="">请选择</option>
              <option v-for="a in areas" :key="a.areaId" :value="a.areaId">
                {{ a.name }} · {{ a.areaCode }}
              </option>
            </select></label
          >
          <label v-if="create.deviceType === 'IPC'"
            >PoE 受电能力<select v-model="create.poeInput">
              <option :value="true">支持</option>
              <option :value="false">不支持</option>
            </select></label
          ><label v-if="create.deviceType === 'SWITCH'"
            >PoE 供电能力<select v-model="create.poeOutput">
              <option :value="true">支持</option>
              <option :value="false">不支持</option>
            </select></label
          >
          <button class="primary" :disabled="busy">注册并获取密钥</button>
          <p class="video-muted">注册后需在详情中投入使用，才能建立设备连接。</p>
        </form>
      </details>
      <details class="video-panel">
        <summary>新增区域</summary>
        <form class="video-form" @submit.prevent="addArea">
          <label
            >区域编号<input v-model="area.areaCode" required pattern="[A-Za-z0-9_-]{1,64}" /></label
          ><label>名称<input v-model="area.name" required maxlength="100" /></label
          ><label
            >层级<select v-model="area.areaType">
              <option value="BUILDING">楼栋</option>
              <option value="FLOOR">楼层</option>
              <option value="AREA">区域</option>
            </select></label
          ><label
            >上级区域<select v-model="area.parentAreaId">
              <option value="">园区直属</option>
              <option v-for="a in areas" :key="a.areaId" :value="a.areaId">{{ a.name }}</option>
            </select></label
          ><button :disabled="busy">新增</button>
        </form>
      </details>
    </div>
  </div>
</template>
