<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import { command, core, loadMe } from '../video'
import { formatTime } from '../domain'
interface Grant {
  grantId: string
  roleCode: string
  scopeType: string
  parkId: string | null
  rootAreaIds: string[]
  assignmentRelation: string | null
}
interface User {
  userId: string
  loginName: string
  displayName: string
  status: string
  version: number
  grants?: Grant[]
}
const users = ref<User[]>([]),
  selected = ref<User | null>(null),
  audit = ref<
    {
      auditId: number
      actorName: string
      actionCode: string
      resourceId: string
      result: string
      createdAt: string
    }[]
  >([])
const error = ref(''),
  busy = ref(false)
const create = reactive({ loginName: '', displayName: '', password: '' }),
  grant = reactive({ roleCode: 'OPS_OPERATOR', scopeType: 'PARK', parkId: 'CAMPUS-A', roots: '' })
const roleNames: Record<string, string> = {
  SYS_ADMIN: '平台管理员',
  OPS_SUPERVISOR: '运维主管',
  OPS_OPERATOR: '运维人员',
  REPAIR_TECH: '维修人员',
  ACCEPTANCE_STAFF: '验收人员',
}
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
  users.value = await core<User[]>('/users')
  audit.value = await core<typeof audit.value>('/audit-records?size=50')
}
async function open(id: string) {
  selected.value = await core<User>('/users/' + id)
}
async function createUser() {
  await run(async () => {
    await command('/users', create)
    create.password = ''
    await load()
  })
}
async function toggleUser() {
  const u = selected.value
  if (!u) return
  await run(async () => {
    await command(
      '/users/' + u.userId,
      { status: u.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE', expectedVersion: u.version },
      'PATCH',
    )
    await open(u.userId)
    await load()
  })
}
async function addGrant() {
  const user = selected.value
  if (!user) return
  const admin = grant.roleCode === 'SYS_ADMIN',
    assigned = ['REPAIR_TECH', 'ACCEPTANCE_STAFF'].includes(grant.roleCode)
  await run(async () => {
    await command('/users/' + user.userId + '/grants', {
      roleCode: grant.roleCode,
      scopeType: admin ? 'NONE' : assigned ? 'ASSIGNED_TO_ME' : grant.scopeType,
      parkId: admin ? null : grant.parkId,
      rootAreaIds:
        !admin && !assigned && grant.scopeType === 'AREA_SUBTREE'
          ? grant.roots
              .split(',')
              .map((r) => r.trim())
              .filter(Boolean)
          : [],
      assignmentRelation: assigned
        ? grant.roleCode === 'REPAIR_TECH'
          ? 'REPAIRER'
          : 'ACCEPTOR'
        : null,
    })
    await open(user.userId)
    await load()
  })
}
async function revoke(g: Grant) {
  const reason = window.prompt('请说明撤销授权的原因。相关旧登录会话会立即失效。')
  if (!reason || !selected.value) return
  const id = selected.value.userId
  await run(async () => {
    await command('/grants/' + g.grantId + '/revoke', { reason })
    await open(id)
    await load()
  })
}
onMounted(() =>
  run(async () => {
    await loadMe()
    await load()
  }),
)
</script>
<template>
  <div class="video-page">
    <PageHeader><button :disabled="busy" @click="run(load)">刷新</button></PageHeader>
    <p v-if="error" class="video-error" role="alert">{{ error }}</p>
    <section class="video-panel">
      <h2>账号</h2>
      <table class="video-table">
        <thead>
          <tr>
            <th>登录名</th>
            <th>姓名</th>
            <th>状态</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in users" :key="u.userId">
            <td>{{ u.loginName }}</td>
            <td>{{ u.displayName }}</td>
            <td>{{ u.status === 'ACTIVE' ? '可用' : '停用或锁定' }}</td>
            <td><button class="link" @click="run(() => open(u.userId))">管理授权</button></td>
          </tr>
        </tbody>
      </table>
    </section>
    <section v-if="selected" class="video-panel">
      <h2>{{ selected.displayName }} · 角色与范围</h2>
      <div class="video-actions">
        <button :disabled="busy" @click="toggleUser">
          {{ selected.status === 'ACTIVE' ? '停用账号' : '启用账号' }}</button
        ><button class="link" @click="selected = null">收起</button>
      </div>
      <div v-for="g in selected.grants" :key="g.grantId" class="video-timeline">
        <p>{{ roleNames[g.roleCode] }} · {{ g.scopeType }} · {{ g.parkId ?? '平台' }}</p>
        <p class="video-muted">{{ g.rootAreaIds.join(', ') }}</p>
        <button class="link danger" :disabled="busy" @click="revoke(g)">撤销</button>
      </div>
      <form class="video-form" @submit.prevent="addGrant">
        <div class="video-grid">
          <label
            >角色<select v-model="grant.roleCode">
              <option v-for="(name, code) in roleNames" :key="code" :value="code">
                {{ name }}
              </option>
            </select></label
          ><template v-if="grant.roleCode !== 'SYS_ADMIN'"
            ><label>园区编号<input v-model="grant.parkId" required /></label
            ><template v-if="!['REPAIR_TECH', 'ACCEPTANCE_STAFF'].includes(grant.roleCode)"
              ><label
                >范围<select v-model="grant.scopeType">
                  <option value="PARK">整个园区</option>
                  <option value="AREA_SUBTREE">指定区域及下级</option>
                </select></label
              ><label v-if="grant.scopeType === 'AREA_SUBTREE'"
                >区域编号，以逗号分隔<input v-model="grant.roots" required /></label></template
          ></template>
        </div>
        <p class="video-muted">
          维修、验收角色按所分派任务获得必要设备范围；平台管理员不会自动获得园区业务权限。授权变化会使该用户的旧登录失效。
        </p>
        <button class="primary" :disabled="busy">新增授权</button>
      </form>
    </section>
    <details class="video-panel">
      <summary>新增账号</summary>
      <form class="video-form" @submit.prevent="createUser">
        <label
          >登录名<input
            v-model="create.loginName"
            required
            pattern="[A-Za-z0-9_.@-]{1,64}"
            autocomplete="off" /></label
        ><label>显示姓名<input v-model="create.displayName" required maxlength="100" /></label
        ><label
          >初始密码<input
            v-model="create.password"
            type="password"
            required
            minlength="6"
            maxlength="256"
            autocomplete="new-password" /></label
        ><button :disabled="busy">创建账号</button>
      </form>
    </details>
    <section class="video-panel">
      <h2>平台操作记录</h2>
      <table class="video-table">
        <thead>
          <tr>
            <th>操作</th>
            <th>对象</th>
            <th>结果</th>
            <th>时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in audit" :key="a.auditId">
            <td>{{ a.actionCode }}</td>
            <td>{{ a.resourceId }}</td>
            <td>{{ a.result }}</td>
            <td>{{ formatTime(a.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>
