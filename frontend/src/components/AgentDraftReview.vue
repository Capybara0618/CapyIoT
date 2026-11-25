<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { type AgentDraft, draftStatus } from '../agentDrafts'
import { formatTime } from '../domain'
import { label } from '../video'

const props = defineProps<{ draft: AgentDraft; busy: boolean; canConfirm: boolean; now: number }>()
const emit = defineEmits<{ decide: [action: 'confirm' | 'reject', reason?: string] }>()
const reviewed = ref(false), reason = ref('')
watch(() => [props.draft.draftId, props.draft.revision, props.draft.contentHash], () => { reviewed.value = false; reason.value = '' })
const pending = computed(() => props.draft.status === 'PENDING_CONFIRMATION' && Date.parse(props.draft.expiresAt) > props.now)
const deviceName = (id: string) => props.draft.devices.find(d => d.deviceId === id)?.name ?? id
</script>

<template>
  <section class="video-panel draft-review" aria-label="工单草稿审阅">
    <div class="video-actions"><h2>{{ draft.payload.title }}</h2><span class="draft-state">{{ draftStatus(draft.status) }}</span><span class="video-muted">版本 {{ draft.revision }}</span></div>
    <p class="video-notice" v-if="pending">这是一份待确认的草稿。确认后才会创建正式维修工单，负责人和验收人由后续分配流程指定。</p>
    <dl class="draft-details">
      <div><dt>工单来源</dt><dd>{{ label(draft.payload.sourceType) }} <RouterLink v-if="draft.payload.incidentId" :to="{ path: '/alarms', query: { id: draft.payload.incidentId } }">查看来源故障</RouterLink></dd></div>
      <div><dt>优先级</dt><dd>{{ draft.payload.priority }}<span v-if="draft.payload.priorityReason"> · {{ draft.payload.priorityReason }}</span></dd></div>
      <div><dt>维修类别</dt><dd>{{ draft.payload.workCategory }}</dd></div>
      <div><dt>有效期至</dt><dd>{{ formatTime(draft.expiresAt) }}</dd></div>
    </dl>
    <h3>设备范围</h3>
    <div class="table-scroll"><table><thead><tr><th>设备</th><th>维修角色</th><th>问题描述</th></tr></thead><tbody><tr v-for="item in draft.payload.deviceItems" :key="item.deviceId"><td><RouterLink :to="{path:'/devices',query:{id:item.deviceId}}">{{ deviceName(item.deviceId) }}</RouterLink><small class="draft-device-id">{{ item.deviceId }}</small></td><td>{{ item.deviceRole === 'MAIN' ? '主设备' : '关联设备' }}</td><td>{{ item.issueDescription }}</td></tr></tbody></table></div>
    <h3>维修描述</h3><p class="draft-text">{{ draft.payload.description }}</p>
    <h3>预期结果</h3><p class="draft-text">{{ draft.payload.expectedResult }}</p>
    <h3>来源证据</h3>
    <p v-if="!draft.relatedEvidenceReferences.length" class="video-muted">本草稿未引用分析证据，请结合设备与来源故障核查内容。</p>
    <ul v-else class="draft-evidence"><li v-for="evidence in draft.relatedEvidenceReferences" :key="evidence.evidenceId"><p>{{ evidence.summary }}</p><small>{{ formatTime(evidence.retrievedAt) }} · {{ label(evidence.freshnessStatus) }}</small></li></ul>
    <template v-if="draft.execution">
      <div class="video-notice" role="status"><strong>{{ draftStatus(draft.execution.status) }}</strong><p v-if="draft.execution.status === 'RECONCILIATION_REQUIRED'">命令可能已执行，系统将按原请求核对结果。请勿另建相同工单。</p><p v-if="draft.execution.errorCode">结果代码：{{ draft.execution.errorCode }}</p><RouterLink v-if="draft.execution.workOrderId" :to="{path:'/workorders',query:{id:draft.execution.workOrderId}}">查看正式工单 {{ draft.execution.workOrderNo }}</RouterLink></div>
    </template>
    <div v-if="pending && canConfirm" class="draft-decision">
      <label class="draft-check"><input type="checkbox" v-model="reviewed" :disabled="busy" />我已核对本版本的设备范围、描述、优先级和来源证据</label>
      <label>拒绝原因（可选）<textarea v-model="reason" maxlength="1000" :disabled="busy" /></label>
      <div class="video-actions"><button class="primary" :disabled="busy || !reviewed" @click="emit('decide','confirm')">确认创建正式工单</button><button :disabled="busy" @click="emit('decide','reject',reason)">拒绝此草稿</button></div>
    </div>
    <p v-else-if="pending" class="video-muted">当前账号没有确认工单草稿的权限。</p>
    <p v-else-if="draft.status === 'PENDING_CONFIRMATION'" class="video-notice">草稿已过期，请重新生成并审阅。</p>
    <p v-else-if="['EXPIRED','INVALIDATED','REJECTED'].includes(draft.status)" class="video-muted">此草稿不可执行。如仍需维修，请重新生成草稿。</p>
  </section>
</template>

<style scoped>
.draft-review { display: grid; gap: 16px; }
.draft-review h2,.draft-review h3,.draft-review p { margin: 0; }
.draft-review h3 { font-size: 15px; font-weight: 500; }
.draft-state { color: var(--accent); background: var(--accent-soft); padding: 4px 10px; border-radius: 5px; font-size: 13px; }
.draft-details { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin: 0; }
.draft-details dt,.draft-device-id { color: var(--text-muted); font-size: 12px; }
.draft-details dd { margin: 5px 0 0; }
.draft-device-id { display:block; overflow-wrap:anywhere; }
.draft-text { white-space: pre-wrap; overflow-wrap:anywhere; }
.draft-evidence { padding-left: 20px; display: grid; gap: 10px; }
.draft-decision { border-top: 1px solid var(--border); padding-top: 18px; display:grid; gap:14px; }
.draft-check { display:flex; align-items:center; gap:10px; }
.draft-check input { width: 16px; flex:none; }
@media(max-width:640px) { .draft-details { grid-template-columns:1fr; } }
</style>
