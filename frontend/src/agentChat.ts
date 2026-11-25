import { ApiError, request, type ApiResponse } from './api'
import { AgentEventDecoder, type AgentEvent } from './agentStream'

export interface EvidenceObject {
  sourceType: string
  sourceId: string
  sourceVersion?: number
}
export interface EvidenceReference {
  evidenceId: string
  summary: string
  retrievedAt: string
  freshnessStatus: string
  toolName: string
  completeness?: string
  sourceObjects: EvidenceObject[]
}
export interface AgentClaim {
  statement: string
  evidenceIds: string[]
}
export interface AgentAnswer {
  facts: AgentClaim[]
  inferences: AgentClaim[]
  recommendations: string[]
  unknowns: string[]
}
export interface AgentRun {
  runId: string
  requestKey: string | null
  conversationId: string
  status: string
  startedAt: string
  finishedAt: string | null
  answerMessageId: string | null
  answer: AgentAnswer | null
  toolCallCount: number
  draftId: string | null
  executionStatus: string
  errorCode: string | null
  version: number
  contentStatus: string
  evidenceReferences: EvidenceReference[]
}
export interface Conversation {
  conversationId: string
  title: string
  status: string
  createdAt: string
  lastMessageAt: string | null
}
export interface ChatMessage {
  messageId: string
  messageSeq: number
  role: 'USER' | 'ASSISTANT'
  content: string | null
  contentStatus: string
  createdAt: string
  runId: string | null
  run: AgentRun | null
}
export interface ChatHistory extends Conversation {
  messages: ChatMessage[]
  firstSequence: number | null
  lastSequence: number | null
  hasMoreBefore: boolean
  hasMoreAfter: boolean
}
export interface ConversationList {
  items: Conversation[]
  total: number
  page: number
  size: number
}
export const activeRun = (status: string) => ['CREATED', 'PLANNING', 'RUNNING'].includes(status)
export const runStatus = (status: string) =>
  (
    ({
      CREATED: '等待处理',
      PLANNING: '准备查询',
      RUNNING: '正在查询',
      WAITING_CONFIRMATION: '草稿待确认',
      SUCCEEDED: '已完成',
      PARTIAL_SUCCEEDED: '部分查询完成',
      FAILED: '查询失败',
      CANCELLED: '已停止',
      TIMED_OUT: '查询超时',
    }) as Record<string, string>
  )[status] ?? '状态待核对'
export const toolLabels: Record<string, string> = {
  query_devices: '查询设备',
  get_device_detail: '核查设备详情',
  get_device_status_history: '查询状态历史',
  query_anomalies: '查询异常',
  query_incidents: '查询故障',
  get_incident_detail: '核查故障证据',
  get_topology_context: '查询关联拓扑',
  query_work_orders: '查询维修工单',
  get_maintenance_history: '查询维修历史',
  prepare_work_order: '准备待确认草稿',
}
export const conversations = (page = 1, signal?: AbortSignal) =>
  request<ConversationList>(`/agent/conversations?page=${page}&size=20`, { signal })
export const createConversation = (title: string) =>
  request<Conversation>('/agent/conversations', { method: 'POST', body: JSON.stringify({ title }) })
export const history = (id: string, before?: number, signal?: AbortSignal) =>
  request<ChatHistory>(
    `/agent/conversations/${encodeURIComponent(id)}?size=10&${before ? 'before=' + before : 'latest=true'}`,
    { signal: signal ?? AbortSignal.timeout(30000) },
  )
export const getRun = (id: string, signal?: AbortSignal) =>
  request<AgentRun>('/agent/runs/' + encodeURIComponent(id), {
    signal: signal ?? AbortSignal.timeout(30000),
  })
export const cancelRun = (id: string) =>
  request<AgentRun>('/agent/runs/' + encodeURIComponent(id) + '/cancel', { method: 'POST' })

export async function sendMessage(
  conversationId: string,
  content: string,
  key: string,
  signal: AbortSignal,
  receive: (event: AgentEvent) => void,
): Promise<AgentRun | null> {
  const connection = new AbortController()
  const stop = () => connection.abort()
  signal.addEventListener('abort', stop, { once: true })
  if (signal.aborted) stop()
  let idle: ReturnType<typeof setTimeout>
  const alive = () => {
    clearTimeout(idle)
    idle = setTimeout(stop, 35000)
  }
  const deadline = setTimeout(stop, 95000)
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
  try {
    alive()
    const response = await fetch(
      `/api/v1/agent/conversations/${encodeURIComponent(conversationId)}/messages`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          'Idempotency-Key': key,
          'X-Request-Id': key,
          'X-Correlation-Id': key,
          Authorization: 'Bearer ' + (sessionStorage.getItem('capyiot.token') ?? ''),
        },
        body: JSON.stringify({ content }),
        signal: connection.signal,
      },
    )
    const type = response.headers.get('Content-Type') ?? ''
    if (!response.ok) {
      const error = await response.json().catch(() => null)
      if (response.status === 401) window.dispatchEvent(new Event('capyiot:unauthorized'))
      throw new ApiError(
        error?.message ?? '消息未被受理，请核对运行状态',
        response.status,
        error?.code,
        error?.details,
      )
    }
    if (type.includes('application/json')) {
      const replay = (await response.json()) as ApiResponse<AgentRun>
      if (replay.code !== 'OK' || !replay.data?.runId) throw new Error('重放结果无效')
      return replay.data
    }
    if (!type.includes('text/event-stream') || !response.body) throw new Error('服务未返回消息流')
    const decoder = new AgentEventDecoder(receive)
    reader = response.body.getReader()
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      alive()
      decoder.feed(value)
    }
    decoder.finish()
    return null
  } finally {
    clearTimeout(idle!)
    clearTimeout(deadline)
    signal.removeEventListener('abort', stop)
    if (reader) {
      await reader.cancel().catch(() => {})
      reader.releaseLock()
    }
  }
}
