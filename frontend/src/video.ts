import { computed, ref } from 'vue'
import { ApiError, request } from './api'

export interface Page<T> {
  items: T[]
  total: number
  page: number
  size: number
}
export interface Area {
  areaId: string
  areaCode: string
  name: string
  parentAreaId: string | null
  areaType: string
  version: number
}
export interface Device {
  deviceId: string
  assetCode: string
  deviceType: 'IPC' | 'NVR' | 'SWITCH'
  name: string
  areaId: string
  areaName: string
  assetStatus: string
  connectivityStatus: string
  operatingMode: string
  dataFreshness: string
  healthStatus: string
  healthAvailability?: 'AVAILABLE' | 'INCOMPLETE' | 'UNAVAILABLE' | 'NOT_QUERIED'
  healthCheckedAt?: string | null
  healthSourceVersion?: number | null
  version: number
  lastHeartbeatReceivedAt: string | null
  lastBusinessDataReceivedAt: string | null
  capabilities?: Record<string, boolean>
  networkDependencies?: Relation[]
  powerDependencies?: Relation[]
  videoChannels?: Relation[]
  monitoringPointBindings?: Binding[]
}
export interface Binding {
  bindingId: string
  monitoringPointId: string
  ipcDeviceId: string
  validFrom: string
  validTo: string | null
  version: number
}
export interface Point {
  monitoringPointId: string
  pointCode: string
  name: string
  areaId: string
  version: number
  status: string
}
export interface Relation {
  relationId: string
  relationType: string
  sourceDeviceId: string
  targetDeviceId: string
  version: number
  upstreamPortRef?: string
  poePort?: string
  channelNo?: number
}
export interface Topology {
  nodes: Device[]
  relations: Relation[]
  completeness: string
  asOf: string
}
export interface Evidence {
  evidenceId: string
  eventId: string
  receivedAt: string
  summaryJson?: unknown
}
export interface Anomaly {
  anomalyId: string
  deviceId: string
  anomalyType: string
  resourceKey: string
  status: string
  severity: string
  lastObservedAt: string
  version: number
  evidenceReferences?: Evidence[]
}
export interface Incident {
  incidentId: string
  incidentNo: string
  title: string
  priority: string
  status: string
  rootCauseStatus: string
  version: number
  candidateRootDeviceId: string | null
  confirmedRootCauseText: string | null
  openedAt: string
  impactSnapshot?: { actualDeviceIds: string[] }
  anomalyLinks?: { anomalyId: string; deviceId: string }[]
  evidenceReferences?: Evidence[]
  workOrders?: { workOrderId: number; workOrderNo: string; status: string }[]
}
export interface DeviceItem {
  deviceId: string
  deviceRole: string
  issueDescription: string
  deviceNameSnapshot: string
  assetCodeSnapshot: string
}
export interface Order {
  workOrderId: string
  workOrderNo: string
  sourceType: string
  incidentId: string | null
  title: string
  description: string
  priority: string
  status: string
  version: number
  assignedRepairerId: string | null
  designatedAcceptorId: string | null
  workCycleNo: number
  expectedResult: string
  completionSummary: string | null
  createdAt: string
  deviceItems: DeviceItem[]
  repairRecords?: {
    recordId: string
    recordType: string
    content: string
    operatorId: string
    recordedAt: string
  }[]
  acceptanceRecords?: {
    acceptanceId: string
    result: string
    comment: string
    acceptorId: string
    acceptedAt: string
    workCycleNo: number
  }[]
  statusHistory?: {
    action: string
    toStatus: string
    actor: string
    remark: string
    createdAt: string
  }[]
}
export interface Event {
  eventId: string
  deviceId: string
  eventType: string
  streamKey: string
  sequence: number
  sessionEpoch: number
  occurredAt: string
  receivedAt: string
  processingStatus: string
  traceId: string
  payload?: unknown
}
export interface Me {
  userId: string
  displayName: string
  permissionCodes: string[]
  roleCodes: string[]
}
export const me = ref<Me | null>(null)
export const allowed = (permission: string) =>
  computed(() => me.value?.permissionCodes.includes(permission) ?? false)
export async function loadMe() {
  me.value = await core<Me>('/auth/me')
}
window.addEventListener('capyiot:unauthorized', () => {
  me.value = null
  pending.clear()
})
export function core<T>(path: string, init: RequestInit = {}) {
  // Backend remains authoritative; a different login must not reuse cached permissions or command keys.
  const headers = new Headers(init.headers)
  headers.set('X-CapyIoT-Legacy-View', 'false')
  return request<T>(path, { ...init, headers })
}
const pending = new Map<string, string>()
let sessionGeneration = 0
// Keep the exact unresolved command in memory for this login, including its original key.
export const uncertainWorkOrder = ref<{
  path: string
  content: string
  method: string
  operationId?: string
} | null>(null)
export interface WorkOperation {
  operationId: string
  state: string
  workOrderId: string | null
  checkedAt: string
}
export async function checkWorkOperation() {
  const command = uncertainWorkOrder.value
  const generation = sessionGeneration
  if (!command?.operationId) return null
  const result = await core<WorkOperation>(
    '/workorder-operations/' + encodeURIComponent(command.operationId),
  )
  if (generation !== sessionGeneration) throw new ApiError('会话已变化，请重新加载', 401)
  if (result.operationId !== command.operationId)
    throw new ApiError('操作状态响应不匹配，请保留原请求继续核对', 502)
  if (['COMMITTED', 'ABORTED'].includes(result.state)) {
    pending.delete(command.method + command.path + command.content)
    uncertainWorkOrder.value = null
  }
  return result
}
export async function retryWorkOrderCommand() {
  const unresolved = uncertainWorkOrder.value
  if (!unresolved) return null
  return command<{ workOrderId?: string }>(
    unresolved.path,
    JSON.parse(unresolved.content),
    unresolved.method,
  )
}
window.addEventListener('capyiot:unauthorized', () => {
  sessionGeneration++
  uncertainWorkOrder.value = null
})
window.addEventListener('capyiot:session-changed', () => {
  sessionGeneration++
  me.value = null
  pending.clear()
  uncertainWorkOrder.value = null
})
export async function command<T>(path: string, body: unknown, method = 'POST'): Promise<T> {
  const generation = sessionGeneration
  const unresolved = uncertainWorkOrder.value
  const content = JSON.stringify(body),
    signature = method + path + content
  if (
    path.startsWith('/workorders') &&
    uncertainWorkOrder.value &&
    signature !==
      uncertainWorkOrder.value.method +
        uncertainWorkOrder.value.path +
        uncertainWorkOrder.value.content
  )
    throw new ApiError('请先核对上一笔工单操作的结果', 409, 'PREVIOUS_COMMAND_UNRESOLVED')
  const key = pending.get(signature) ?? crypto.randomUUID()
  pending.set(signature, key)
  try {
    const result = await core<T>(path, {
      method,
      body: content,
      headers: { 'Idempotency-Key': key },
    })
    if (generation !== sessionGeneration) return result
    pending.delete(signature)
    if (path.startsWith('/workorders')) uncertainWorkOrder.value = null
    return result
  } catch (error) {
    if (generation !== sessionGeneration) throw error
    // Unknown network outcomes reuse their original key on a retry of the same command.
    if (
      error instanceof ApiError &&
      error.status >= 400 &&
      error.status < 500 &&
      (!path.startsWith('/workorders') ||
        !unresolved ||
        error.code === 'WORKORDER_OPERATION_ABORTED')
    ) {
      pending.delete(signature)
      if (path.startsWith('/workorders')) uncertainWorkOrder.value = null
    } else if (path.startsWith('/workorders')) {
      const id =
        (error instanceof ApiError ? error.details.operationId : undefined) ??
        unresolved?.operationId
      uncertainWorkOrder.value = {
        path,
        content,
        method,
        ...(typeof id === 'string' && /^[A-Za-z0-9_-]{1,128}$/.test(id) ? { operationId: id } : {}),
      }
    }
    throw error
  }
}
export function query(values: Record<string, string | number | undefined>) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(values))
    if (value !== undefined && value !== '') params.set(key, String(value))
  return params.toString()
}
export const labels: Record<string, string> = {
  PREPARED: '已受理，待核对',
  RESERVED: '已预约，待完成',
  COMMITTED: '操作已完成',
  ABORTED: '操作已终止',
  RECONCILIATION_REQUIRED: '结果待核对',
  IPC: '网络摄像机',
  NVR: '录像存储主机',
  SWITCH: '交换机',
  REGISTERED: '待投用',
  IN_SERVICE: '使用中',
  OUT_OF_SERVICE: '停用',
  RETIRED: '已退役',
  ONLINE: '在线',
  OFFLINE: '离线',
  UNKNOWN: '未知',
  NORMAL: '正常',
  LOW_POWER: '低功耗',
  NO_DATA: '暂无数据',
  FRESH: '数据新鲜',
  STALE: '数据陈旧',
  HEALTHY: '健康',
  WARNING: '预警',
  FAULT: '故障',
  ACTIVE: '活动中',
  RECOVERED: '已恢复',
  DISMISSED: '已排除',
  PENDING_REVIEW: '待研判',
  OPEN: '待处理',
  PROCESSING: '处理中',
  PENDING_CLOSE: '待关闭',
  CLOSED: '已关闭',
  REJECTED: '已拒绝',
  UNCONFIRMED: '候选根因，待确认',
  CONFIRMED: '已确认',
  ASSIGNED: '已指派',
  ACCEPTED: '已接单',
  IN_PROGRESS: '维修中',
  VERIFYING: '待独立验收',
  RETURNED: '已退回',
  CANCELLED: '已取消',
  MANUAL: '独立工单',
  INCIDENT: '关联故障',
  NETWORK: '网络依赖',
  POWER: 'PoE 供电',
  VIDEO: '录像通道',
  STATUS_OBSERVATION: '状态观察',
  DEVICE_FAULT: '设备故障',
  DEVICE_RECOVERED: '设备恢复',
  APPLIED: '已应用',
  IGNORED_STALE: '已忽略旧序号',
  DEVICE_CONNECTION_LOST: '设备失联',
  CAMERA_NO_VIDEO: '摄像机无视频信号',
  SWITCH_DEVICE_ERROR: '交换机设备异常',
  SWITCH_PORT_LINK_DOWN: '交换机端口断链',
  SWITCH_POE_FAULT: '交换机供电异常',
  NVR_RECORDING_STOPPED: '录像停止',
  PASS: '验收通过',
  RETURN: '退回维修',
  DIAGNOSIS: '故障诊断',
  ACTION: '维修操作',
  RESULT: '处置结果',
  NOTE: '补充记录',
}
export const label = (value: string | null | undefined) => (value ? (labels[value] ?? value) : '—')
