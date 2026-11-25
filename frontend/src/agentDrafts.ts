import { request } from './api'

export interface AgentDraft {
  draftId: string
  runId: string
  revision: number
  contentHash: string
  status: string
  summary: string
  expiresAt: string
  confirmedAt: string | null
  devices: { deviceId: string; name: string; deviceType: string; assetCode: string }[]
  payload: {
    sourceType: 'MANUAL' | 'INCIDENT'
    incidentId: string | null
    title: string
    description: string
    workCategory: string
    priority: string
    priorityReason: string | null
    expectedResult: string
    deviceItems: { deviceId: string; deviceRole: string; issueDescription: string }[]
  }
  relatedEvidenceReferences: {
    evidenceId: string
    summary: string
    retrievedAt: string
    freshnessStatus: string
  }[]
  execution: {
    executionId: string
    status: string
    workOrderId: string | null
    workOrderNo: string | null
    errorCode: string | null
  } | null
}

export const loadDraft = (id: string) => request<AgentDraft>('/agent/drafts/' + encodeURIComponent(id))
export const decideDraft = (draft: AgentDraft, action: 'confirm' | 'reject', key: string, reason?: string) =>
  request<AgentDraft>(`/agent/drafts/${encodeURIComponent(draft.draftId)}/${action}`, {
    method: 'POST',
    headers: { 'Idempotency-Key': key },
    body: JSON.stringify({ draftRevision: draft.revision, contentHash: draft.contentHash, ...(action === 'reject' ? { reason: reason || null } : {}) }),
  })

export const draftStatus = (status: string) => (({
  PENDING_CONFIRMATION: '待你确认', CONFIRMED: '已确认，待派发', EXECUTING: '正在创建工单', EXECUTED: '正式工单已创建',
  REJECTED: '已拒绝', EXPIRED: '已过期', INVALIDATED: '已失效', FAILED: '创建失败',
  QUEUED: '等待派发', SUCCEEDED: '创建成功', RECONCILIATION_REQUIRED: '执行结果待核对',
} as Record<string,string>)[status] ?? status)
