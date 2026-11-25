<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '../components/PageHeader.vue'
import AgentDraftReview from '../components/AgentDraftReview.vue'
import { type AgentDraft, decideDraft, loadDraft } from '../agentDrafts'
import { allowed, loadMe } from '../video'

const route = useRoute(),
  router = useRouter()
const draft = ref<AgentDraft | null>(null),
  error = ref(''),
  busy = ref(false),
  input = ref(''),
  now = ref(Date.now())
const canConfirm = computed(
  () => allowed('agent.draft.confirm').value && allowed('workorder.create').value,
)
const keys = new Map<string, string>()
let poll: ReturnType<typeof setTimeout> | undefined,
  generation = 0,
  attempts = 0
const clock = setInterval(() => {
  now.value = Date.now()
}, 1000)
async function refresh() {
  const id = String(route.params.draftId ?? ''),
    current = ++generation
  if (!id || busy.value) return
  error.value = ''
  try {
    const view = await loadDraft(id)
    if (current !== generation) return
    draft.value = view
    if (['CONFIRMED', 'EXECUTING'].includes(view.status) && attempts++ < 60) {
      clearTimeout(poll)
      poll = setTimeout(() => void refresh(), 2000)
    }
  } catch (e) {
    if (current === generation) {
      draft.value = null
      error.value = (e as Error).message
    }
  }
}
async function decide(action: 'confirm' | 'reject', reason?: string) {
  if (busy.value || !draft.value) return
  const reviewed = draft.value,
    cacheKey = `${reviewed.draftId}:${reviewed.revision}:${action}`,
    current = ++generation
  if (!keys.has(cacheKey)) keys.set(cacheKey, crypto.randomUUID())
  busy.value = true
  error.value = ''
  clearTimeout(poll)
  try {
    const result = await decideDraft(reviewed, action, keys.get(cacheKey)!, reason)
    if (current === generation) draft.value = result
  } catch (e) {
    if (current === generation) {
      error.value = (e as Error).message
      draft.value = null
    }
  } finally {
    busy.value = false
    attempts = 0
    if (current === generation && !error.value) void refresh()
  }
}
watch(
  () => route.params.draftId,
  async () => {
    ++generation
    clearTimeout(poll)
    draft.value = null
    error.value = ''
    attempts = 0
    try {
      await loadMe()
      await refresh()
    } catch (e) {
      error.value = (e as Error).message
    }
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  ++generation
  clearTimeout(poll)
  clearInterval(clock)
})
</script>

<template>
  <div class="video-page">
    <PageHeader
      ><RouterLink to="/workorders">工单协同中心</RouterLink
      ><button :disabled="busy || !route.params.draftId" @click="refresh">
        刷新草稿
      </button></PageHeader
    >
    <p v-if="error" class="video-error" role="alert">{{ error }}</p>
    <form
      v-if="!route.params.draftId"
      class="video-panel video-toolbar"
      @submit.prevent="router.push('/agent-drafts/' + encodeURIComponent(input.trim()))"
    >
      <label>草稿编号<input v-model="input" required pattern="[A-Za-z0-9_-]{1,64}" /></label
      ><button class="primary">查看草稿</button>
      <p class="video-muted">
        在 Agent 生成草稿后打开对应审阅链接。确认前请核查具体版本与设备范围。
      </p>
    </form>
    <AgentDraftReview
      v-if="draft"
      :draft="draft"
      :busy="busy"
      :can-confirm="canConfirm"
      :now="now"
      @decide="decide"
    />
    <p v-else-if="route.params.draftId && !error" class="video-panel" role="status">
      正在读取草稿…
    </p>
  </div>
</template>
