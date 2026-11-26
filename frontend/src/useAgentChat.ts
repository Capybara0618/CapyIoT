import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from './api'
import { session } from './session'
import { allowed, loadMe } from './video'
import {
  activeRun,
  cancelRun,
  conversations,
  createConversation,
  getRun,
  history,
  sendMessage,
  toolLabels,
  type AgentRun,
  type ChatMessage,
  type Conversation,
} from './agentChat'
import type { AgentEvent } from './agentStream'

interface Pending {
  owner: string
  conversationId: string
  key: string
  runId?: string
}
interface Progress {
  callId: string
  label: string
  status: string
}
const pendingKey = 'capyiot.agent.pending'

export function useAgentChat() {
  const route = useRoute(),
    router = useRouter()
  const id = computed(() => String(route.params.conversationId ?? ''))
  const list = ref<Conversation[]>([]),
    total = ref(0),
    listPage = ref(1)
  const messages = ref<ChatMessage[]>([]),
    runs = ref<Record<string, AgentRun>>({})
  const input = ref(''),
    error = ref(''),
    notice = ref(''),
    loading = ref(false),
    sending = ref(false),
    creating = ref(false),
    loadingOlder = ref(false)
  const progress = ref<Progress[]>([]),
    phase = ref(''),
    currentRunId = ref(''),
    hasOlder = ref(false),
    authEpoch = ref(0)
  const authorized = ref(false)
  const canUse = computed(() => authorized.value && allowed('agent.use').value)
  const blocked = computed(
    () =>
      sending.value ||
      creating.value ||
      loading.value ||
      Object.values(runs.value).some((run) => activeRun(run.status)),
  )
  let generation = 0,
    adopting = '',
    stream: AbortController | undefined,
    reading: AbortController | undefined,
    poll: ReturnType<typeof setTimeout> | undefined,
    pollCount = 0,
    disposed = false
  let answerFragment = '',
    answerLookup: Promise<AgentRun | null> | undefined
  let retry: { content: string; key: string; conversationId: string } | undefined
  const owner = () => session.value?.user.userId ?? ''
  const remember = (value: Pending) => sessionStorage.setItem(pendingKey, JSON.stringify(value))
  function pending(): Pending | null {
    try {
      const value = JSON.parse(sessionStorage.getItem(pendingKey) ?? 'null') as Pending | null
      return value?.owner === owner() ? value : null
    } catch {
      return null
    }
  }
  function forget(run?: AgentRun) {
    const saved = pending()
    if (!run || saved?.runId === run.runId || saved?.key === run.requestKey)
      sessionStorage.removeItem(pendingKey)
  }
  function clearViews() {
    messages.value = []
    runs.value = {}
    progress.value = []
    currentRunId.value = ''
    ++authEpoch.value
  }
  async function loadList(more = false) {
    const currentOwner = owner(),
      page = more ? listPage.value + 1 : 1
    const result = await conversations(page)
    if (disposed || owner() !== currentOwner) return
    list.value = more ? [...list.value, ...result.items] : result.items
    total.value = result.total
    listPage.value = page
  }
  function updateRun(run: AgentRun) {
    if (run.conversationId !== id.value) return
    runs.value[run.runId] = run
    if (!activeRun(run.status)) forget(run)
  }
  function startPoll(runId: string) {
    clearTimeout(poll)
    if (pollCount++ >= 90) {
      notice.value = '暂未获得最终状态，请点击“刷新对话”核对；不会自动重发。'
      return
    }
    poll = setTimeout(async () => {
      if (disposed || !navigator.onLine) return
      const current = generation
      try {
        const run = await getRun(runId)
        if (current !== generation) return
        updateRun(run)
        if (activeRun(run.status)) startPoll(runId)
        else await reload()
      } catch (e) {
        if (current === generation) {
          runs.value = {}
          notice.value = '状态查询未完成，请恢复连接后刷新对话。'
          if (e instanceof ApiError && [401, 403, 404].includes(e.status)) {
            clearViews()
            error.value = e.message
          }
        }
      }
    }, 1500)
  }
  async function reload(older = false) {
    if (!id.value || !canUse.value) return
    const current = generation,
      conversationId = id.value
    reading?.abort()
    reading = new AbortController()
    if (older) loadingOlder.value = true
    else loading.value = true
    try {
      const first = messages.value.find((message) => message.messageSeq > 0)?.messageSeq
      const page = await history(
        conversationId,
        older ? first : undefined,
        AbortSignal.any([reading.signal, AbortSignal.timeout(30000)]),
      )
      if (current !== generation || id.value !== conversationId) return
      const combined = older
        ? [
            ...page.messages,
            ...messages.value.filter(
              (message) => !page.messages.some((item) => item.messageId === message.messageId),
            ),
          ]
        : page.messages
      messages.value = combined.sort((a, b) => a.messageSeq - b.messageSeq)
      if (!older) runs.value = {}
      for (const message of page.messages) if (message.run) updateRun(message.run)
      hasOlder.value = page.hasMoreBefore
      error.value = ''
      const saved = pending()
      if (saved?.conversationId === conversationId) {
        const matched = Object.values(runs.value).find(
          (run) => run.runId === saved.runId || run.requestKey === saved.key,
        )
        if (matched) {
          currentRunId.value = matched.runId
          if (!activeRun(matched.status)) forget(matched)
        } else if (saved.runId) {
          const run = await getRun(saved.runId)
          if (current !== generation) return
          updateRun(run)
          currentRunId.value = run.runId
        } else
          notice.value = '上次消息的受理状态尚未核实。请刷新或查看更早记录核对，系统不会自动重发。'
      }
      const active = Object.values(runs.value).find((run) => activeRun(run.status))
      if (active && !sending.value) {
        currentRunId.value = active.runId
        startPoll(active.runId)
      }
    } catch (e) {
      if (current === generation) {
        clearViews()
        error.value = (e as Error).message
      }
    } finally {
      if (current === generation) {
        loading.value = false
        loadingOlder.value = false
      }
    }
  }
  function receive(event: AgentEvent) {
    currentRunId.value = event.runId
    const saved = pending()
    if (saved?.conversationId === id.value) remember({ ...saved, runId: event.runId })
    if (event.name === 'run.started') {
      phase.value = '已受理，正在查询当前可见的数据'
      const row = [...messages.value]
        .reverse()
        .find((message) => message.messageId.startsWith('pending-'))
      if (row) row.runId = event.runId
      runs.value[event.runId] = {
        runId: event.runId,
        requestKey: saved?.key ?? null,
        conversationId: id.value,
        status: 'RUNNING',
        startedAt: new Date().toISOString(),
        finishedAt: null,
        answerMessageId: null,
        answer: null,
        toolCallCount: 0,
        draftId: null,
        executionStatus: 'NOT_EXECUTED',
        errorCode: null,
        version: 0,
        contentStatus: 'VISIBLE',
        evidenceReferences: [],
      }
    }
    if (event.name === 'step.started') phase.value = '正在核查业务证据'
    if (event.name === 'tool.started' || event.name === 'tool.completed') {
      const callId = String(event.data.toolCallId ?? ''),
        name = String(event.data.toolName ?? '')
      if (!/^[A-Za-z0-9_-]{1,64}$/.test(callId)) return
      const row = {
        callId,
        label: toolLabels[name] ?? '查询业务证据',
        status: event.name === 'tool.started' ? 'STARTED' : String(event.data.status ?? 'FAILED'),
      }
      const old = progress.value.findIndex((item) => item.callId === callId)
      if (old < 0 && progress.value.length < 20) progress.value.push(row)
      else if (old >= 0) progress.value[old] = row
    }
    if (event.name === 'answer.delta') {
      if (
        typeof event.data.contentDelta !== 'string' ||
        answerFragment.length + event.data.contentDelta.length > 128000
      ) {
        stream?.abort()
        notice.value = '回答片段格式异常，请核对保存状态。'
        return
      }
      answerFragment += event.data.contentDelta
      phase.value = '已收到回答，正在核查引用与最终状态'
      // Render only the current-authorized persisted answer; never raw provider JSON/reasoning.
      const current = generation,
        epoch = authEpoch.value
      if (!answerLookup)
        answerLookup = getRun(event.runId)
          .then((run) => {
            if (current === generation && epoch === authEpoch.value) {
              updateRun(run)
              return run
            }
            return null
          })
          .catch(() => null)
    }
    if (event.name === 'draft.ready') phase.value = '草稿已准备，等待你审阅确认'
    if (event.name === 'run.failed')
      notice.value = '本次查询未完成。请查看运行状态；查询失败不表示设备没有异常。'
    if (event.name === 'run.cancelled') notice.value = '本次查询已停止，已保存的记录和草稿会保留。'
  }
  async function submit(content: string = input.value.trim(), key: string = crypto.randomUUID()) {
    if (!content || content.length > 12000 || blocked.value || !canUse.value) return
    error.value = ''
    notice.value = ''
    progress.value = []
    answerFragment = ''
    answerLookup = undefined
    phase.value = '正在提交消息'
    currentRunId.value = ''
    pollCount = 0
    if (!id.value) {
      creating.value = true
      try {
        const conversation = await createConversation(content.slice(0, 60))
        adopting = conversation.conversationId
        await router.replace('/assistant/' + conversation.conversationId)
        void loadList().catch(() => {})
      } catch (e) {
        error.value = (e as Error).message
        return
      } finally {
        creating.value = false
      }
    }
    const conversationId = id.value,
      current = generation
    const saved = pending()
    if (saved && saved.conversationId === conversationId && saved.key !== key) {
      error.value = '上次消息状态尚未核实，请先刷新对话核对。'
      return
    }
    retry = { content, key, conversationId }
    remember({ owner: owner(), conversationId, key })
    sending.value = true
    input.value = ''
    const controller = new AbortController()
    stream = controller
    const optimistic: ChatMessage = {
      messageId: 'pending-' + key,
      messageSeq: (messages.value.at(-1)?.messageSeq ?? 0) + 1,
      role: 'USER',
      content,
      contentStatus: 'AVAILABLE',
      createdAt: new Date().toISOString(),
      runId: null,
      run: null,
    }
    messages.value.push(optimistic)
    try {
      const replay = await sendMessage(conversationId, content, key, controller.signal, (event) => {
        if (current === generation) receive(event)
      })
      if (current !== generation) return
      if (replay) {
        currentRunId.value = replay.runId
        updateRun(replay)
      }
    } catch (e) {
      if (current !== generation) return
      if (e instanceof ApiError && [400, 401, 403, 404].includes(e.status)) {
        forget()
        input.value = content
        error.value = e.message
        if ([401, 403].includes(e.status)) clearViews()
      } else {
        notice.value = controller.signal.aborted
          ? '消息订阅已停止，正在核对已保存状态。'
          : '消息连接中断，正在核对状态；不会自动重发。'
        if (e instanceof ApiError && typeof e.details.existingRunId === 'string')
          currentRunId.value = e.details.existingRunId
      }
    } finally {
      if (current === generation) {
        const submissionError = error.value
        sending.value = false
        stream = undefined
        if (currentRunId.value) {
          try {
            const run = (await answerLookup) ?? (await getRun(currentRunId.value))
            if (current === generation) {
              updateRun(run)
              if (activeRun(run.status)) startPoll(run.runId)
            }
          } catch {
            notice.value = '运行结果暂不可查询，请恢复连接后刷新对话。'
          }
        }
        if (current === generation) {
          await reload()
          if (submissionError) error.value = submissionError
          void loadList().catch(() => {})
        }
      }
    }
  }
  async function retryMessage() {
    if (retry?.conversationId === id.value) await submit(retry.content, retry.key)
  }
  async function stop() {
    const runId = currentRunId.value
    stream?.abort()
    clearTimeout(poll)
    if (runId) {
      try {
        updateRun(await cancelRun(runId))
      } catch {
        notice.value = '停止请求未确认，请刷新运行状态。'
      }
    }
    if (!sending.value) await reload()
  }
  async function open() {
    const current = ++generation
    stream?.abort()
    stream = undefined
    reading?.abort()
    clearTimeout(poll)
    sending.value = false
    loading.value = false
    loadingOlder.value = false
    authorized.value = false
    clearViews()
    error.value = ''
    notice.value = ''
    retry = undefined
    pollCount = 0
    input.value = ''
    try {
      await loadMe()
      if (current !== generation) return
      authorized.value = true
      if (!canUse.value) {
        error.value = '当前账号没有使用运维助手的权限'
        return
      }
      await loadList()
      if (current === generation) await reload()
    } catch (e) {
      if (current === generation) error.value = (e as Error).message
    }
  }
  function sessionChanged() {
    ++generation
    stream?.abort()
    reading?.abort()
    forget()
    list.value = []
    authorized.value = false
    clearViews()
    if (session.value) void open()
  }
  function disconnected() {
    if (sending.value) {
      stream?.abort()
      notice.value = '连接已断开，查询会停止；恢复后请核对已保存结果。'
    }
  }
  function refocus() {
    if (document.hidden || disposed) return
    ++authEpoch.value
    for (const run of Object.values(runs.value)) {
      run.answer = null
      run.evidenceReferences = []
      run.contentStatus = 'RECHECKING'
    }
    if (!sending.value) void reload()
  }
  watch(
    id,
    () => {
      if (adopting && adopting === id.value) {
        adopting = ''
        return
      }
      void open()
    },
    { immediate: true },
  )
  window.addEventListener('capyiot:session-changed', sessionChanged)
  window.addEventListener('offline', disconnected)
  window.addEventListener('online', refocus)
  window.addEventListener('focus', refocus)
  onBeforeUnmount(() => {
    disposed = true
    ++generation
    stream?.abort()
    reading?.abort()
    clearTimeout(poll)
    window.removeEventListener('capyiot:session-changed', sessionChanged)
    window.removeEventListener('offline', disconnected)
    window.removeEventListener('online', refocus)
    window.removeEventListener('focus', refocus)
    clearViews()
  })
  const turns = computed(() =>
    messages.value
      .filter(
        (message) =>
          message.role === 'USER' ||
          !messages.value.some((other) => other.role === 'USER' && other.runId === message.runId),
      )
      .map((message) => ({ message, run: message.runId ? runs.value[message.runId] : null })),
  )
  const canRetry = computed(() => !!retry && !!pending() && !blocked.value && !currentRunId.value)
  return {
    id,
    list,
    total,
    listPage,
    messages,
    runs,
    input,
    error,
    notice,
    loading,
    loadingOlder,
    sending,
    creating,
    progress,
    phase,
    currentRunId,
    hasOlder,
    authEpoch,
    canUse,
    canRetry,
    blocked,
    turns,
    loadList,
    reload,
    submit,
    stop,
    retryMessage,
    getRun,
    updateRun,
  }
}
