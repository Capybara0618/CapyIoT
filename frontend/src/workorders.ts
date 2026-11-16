import type { Page } from './domain'

export const workStatuses = {
  OPEN: '待指派',
  ASSIGNED: '已指派',
  IN_PROGRESS: '处理中',
  VERIFYING: '待验证',
  CLOSED: '已关闭',
} as const
export const workPriorities = { HIGH: '高', MEDIUM: '中', LOW: '低' } as const
export const workActions = {
  ASSIGN: '指派处理人',
  REASSIGN: '改派处理人',
  START_PROCESSING: '开始处理',
  ADD_NOTE: '添加处置记录',
  COMPLETE_PROCESSING: '完成处理',
  VERIFY_RECOVERY: '通过恢复验证',
  RETURN_TO_PROCESSING: '退回处理',
  CLOSE: '关闭工单',
  REOPEN: '重开工单',
} as const

export type WorkStatus = keyof typeof workStatuses
export type WorkPriority = keyof typeof workPriorities
export type WorkAction = keyof typeof workActions

export interface WorkSummary {
  total: number
  pending: number
  processing: number
  verifying: number
  closed: number
}

export interface WorkOrder {
  id: number
  orderNo: string
  sourceAlarmId: number
  sourceAlarmNo: string
  title: string
  priority: WorkPriority
  status: WorkStatus
  assignee: string | null
  createdBy: string
  version: number
  verifiedAt: string | null
  closedAt: string | null
  createdAt: string
  updatedAt: string
  source: {
    status: 'OPEN' | 'ACKNOWLEDGED' | 'RECOVERED'
    recoveredAt: string | null
    parentAlarmId: number | null
    deviceName: string
    type: string
  }
}

export interface WorkDetail {
  order: WorkOrder
  timeline: Page<{
    version: number
    action: WorkAction | 'CREATE'
    fromStatus: WorkStatus | null
    toStatus: WorkStatus | null
    actor: string
    remark: string | null
    createdAt: string
    assignee: string | null
  }>
  actions: WorkAction[]
  recoveryVerified: boolean
}

export interface WorkCreated {
  order: WorkOrder
  created: boolean
}

export const workStream = '/api/v1/workorders/stream'
