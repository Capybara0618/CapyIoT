<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import PageHeader from '../components/PageHeader.vue'
import AgentAnswer from '../components/AgentAnswer.vue'
import AgentDraftCard from '../components/AgentDraftCard.vue'
import { activeRun, runStatus } from '../agentChat'
import { useAgentChat } from '../useAgentChat'
import { formatTime } from '../domain'
import type { AgentDraft } from '../agentDrafts'
import '../agentChat.css'

const chat = useAgentChat()
const {
  id,
  list,
  total,
  messages,
  runs,
  input,
  error,
  notice,
  loading,
  loadingOlder,
  sending,
  progress,
  phase,
  currentRunId,
  hasOlder,
  authEpoch,
  canUse,
  canRetry,
  blocked,
  turns,
} = chat
const transcript = ref<HTMLElement | null>(null)
const title = computed(
  () => list.value.find((item) => item.conversationId === id.value)?.title ?? '新的运维对话',
)
const stoppable = computed(
  () => sending.value || Object.values(runs.value).some((run) => activeRun(run.status)),
)
const examples = [
  '查看当前视频设备的连接状态',
  '分析多台摄像机离线的关联线索',
  '查找尚未完成的维修工单',
]
watch(
  () => [messages.value.length, sending.value],
  async () => {
    await nextTick()
    if (transcript.value && !loadingOlder.value)
      transcript.value.scrollTop = transcript.value.scrollHeight
  },
)
async function draftUpdated(runId: string, draft: AgentDraft) {
  if (['EXECUTED', 'REJECTED', 'FAILED', 'EXPIRED', 'INVALIDATED'].includes(draft.status)) {
    try {
      chat.updateRun(await chat.getRun(runId))
    } catch {
      notice.value = '草稿结果已更新，请刷新对话核对最新权限与状态。'
    }
  }
}
function submitKey(event: KeyboardEvent) {
  if (event.ctrlKey && event.key === 'Enter' && !event.isComposing) {
    event.preventDefault()
    void chat.submit()
  }
}
</script>

<template>
  <div class="video-page agent-page">
    <PageHeader
      ><button :disabled="sending || loading || !id" @click="chat.reload()">
        刷新对话
      </button></PageHeader
    >
    <p v-if="error" class="video-error" role="alert">{{ error }}</p>
    <div class="agent-chat-layout">
      <aside class="agent-sessions" aria-label="运维对话历史">
        <RouterLink class="agent-new-session" to="/assistant">新建对话</RouterLink>
        <h2>最近对话</h2>
        <nav class="agent-session-list" aria-label="会话列表">
          <RouterLink
            v-for="item in list"
            :key="item.conversationId"
            :to="'/assistant/' + encodeURIComponent(item.conversationId)"
            :class="{ selected: item.conversationId === id }"
            ><strong>{{ item.title }}</strong
            ><small>{{ formatTime(item.lastMessageAt ?? item.createdAt) }}</small></RouterLink
          >
        </nav>
        <p v-if="!list.length" class="video-muted">你的对话会保存在这里。</p>
        <button
          v-if="list.length < total"
          @click="chat.loadList(true).catch((e) => (error = e.message))"
        >
          加载更多对话
        </button>
      </aside>
      <section class="agent-conversation" aria-label="AI运维对话">
        <header class="agent-conversation-title">
          <h2>{{ title }}</h2>
          <span class="video-muted">基于当前授权范围查询</span>
        </header>
        <div ref="transcript" class="agent-transcript" role="log" aria-label="对话记录">
          <div v-if="!turns.length && !loading" class="agent-welcome">
            <h2>从一个运维问题开始</h2>
            <p>
              查询设备、核查故障证据、整理维修建议。需要创建工单时，我会先准备草稿，交给你审阅确认。
            </p>
            <div class="agent-examples">
              <button
                v-for="example in examples"
                :key="example"
                :disabled="!canUse || blocked"
                @click="input = example"
              >
                {{ example }}
              </button>
            </div>
          </div>
          <button
            v-if="hasOlder"
            :disabled="loadingOlder"
            class="agent-load-older"
            @click="chat.reload(true)"
          >
            {{ loadingOlder ? '正在读取…' : '查看更早消息' }}
          </button>
          <p v-if="loading" class="video-muted" role="status">正在读取并核查对话记录…</p>
          <article v-for="turn in turns" :key="turn.message.messageId" class="agent-turn">
            <div v-if="turn.message.role === 'USER'" class="agent-user-message">
              <small>你 · {{ formatTime(turn.message.createdAt) }}</small>
              <p>{{ turn.message.content }}</p>
            </div>
            <div v-if="turn.run" class="agent-assistant-message" :data-run-id="turn.run.runId">
              <div class="agent-answer-heading">
                <strong>运维助手</strong><span>{{ runStatus(turn.run.status) }}</span
                ><small v-if="turn.run.toolCallCount"
                  >已调用 {{ turn.run.toolCallCount }} 个工具</small
                >
              </div>
              <AgentAnswer :run="turn.run" />
              <p v-if="['FAILED', 'TIMED_OUT'].includes(turn.run.status)" class="video-notice">
                本次查询未完成，不能据此判断设备正常或没有异常。你可以调整问题后重新查询。
              </p>
              <p v-if="turn.run.status === 'CANCELLED'" class="video-muted">
                查询已停止。已保存的证据和草稿仍可按当前权限核查。
              </p>
              <AgentDraftCard
                v-if="turn.run.draftId && turn.run.contentStatus === 'VISIBLE'"
                :key="authEpoch + ':' + turn.run.draftId"
                :draft-id="turn.run.draftId"
                @updated="(draft) => draftUpdated(turn.run!.runId, draft)"
              />
            </div>
          </article>
          <div v-if="sending" class="agent-progress" role="status" aria-live="polite">
            <p>{{ phase }}</p>
            <ul v-if="progress.length">
              <li v-for="step in progress" :key="step.callId">
                <span>{{ step.label }}</span><small>{{
                  step.status === 'STARTED'
                    ? '进行中'
                    : ['SUCCEEDED', 'EMPTY'].includes(step.status)
                      ? '已完成'
                      : '未完成'
                }}</small>
              </li>
            </ul>
          </div>
        </div>
        <form class="agent-composer" @submit.prevent="chat.submit()">
          <p v-if="notice" class="video-notice" role="status">{{ notice }}</p>
          <label for="agent-message">运维问题</label
          ><textarea
            id="agent-message"
            v-model="input"
            :disabled="!canUse || blocked"
            maxlength="12000"
            rows="3"
            placeholder="描述设备、区域和需要核查的问题…"
            @keydown="submitKey"
          />
          <div class="agent-composer-actions">
            <small>{{ input.length }}/12000 · Ctrl + Enter 发送</small
            ><button v-if="stoppable" type="button" @click="chat.stop()">停止查询</button
            ><button v-else class="primary" :disabled="!canUse || blocked || !input.trim()">
              发送消息
            </button>
          </div>
          <p v-if="sending" class="video-muted">
            切换会话或离开页面会停止当前查询，已保存结果会保留。
          </p>
          <p v-if="notice && !stoppable" class="agent-recovery-actions">
            <button type="button" @click="chat.reload()">核对已保存状态</button
            ><button v-if="canRetry" type="button" @click="chat.retryMessage()">
              按原请求重试消息
            </button>
          </p>
        </form>
      </section>
    </div>
    <p v-if="currentRunId" class="video-muted">回答与执行状态以最新保存的业务记录为准。</p>
  </div>
</template>
