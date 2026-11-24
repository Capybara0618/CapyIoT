export const deviceTypes = {
  CAMERA: '摄像头',
  NVR: '网络录像机',
  ACCESS_CONTROL: '门禁',
  SWITCH: '网络交换机',
  SENSOR: '环境传感器',
} as const
export type DeviceType = keyof typeof deviceTypes
export interface Device {
  id: string
  deviceCode: string
  type: DeviceType
  name: string
  enabled: boolean
  campusId: string
  buildingId: string
  buildingName: string
  floorId: string
  floorName: string
  areaId: string | null
  areaName: string | null
  upstreamNodeId: string | null
  upstreamNodeName: string | null
  connectivityStatus: 'UNKNOWN' | 'ONLINE' | 'OFFLINE'
  healthStatus: 'UNKNOWN' | 'NORMAL' | 'ABNORMAL'
  lastHeartbeatAt: string | null
  createdAt: string
  updatedAt: string
  version: number
  stateVersion: number
  sessionEpoch: number
  seq: number
}
export interface Page<T> {
  items: T[]
  total: number
  page: number
  size: number
}
export interface Locations {
  campus: { id: string; name: string }
  buildings: { id: string; name: string }[]
  floors: { id: string; name: string; buildingId: string }[]
  areas: { id: string; name: string; floorId: string }[]
}
export interface DeviceEvent {
  id: number
  eventId: string
  deviceId: string
  deviceCode: string
  deviceName: string
  eventType: string
  source: string
  processingStatus: string
  occurredAt: string
  receivedAt: string
  traceId: string
  sessionEpoch: number
  seq: number
  payload: Record<string, unknown>
  partition: number
  offset: number
}
export interface EventPage extends Page<DeviceEvent> {
  resumeCursor: string
  nextCursor: string
  hasMore: boolean
}
export const eventTypes = {
  HEARTBEAT: '设备心跳',
  DEVICE_FAULT: '设备故障',
  DEVICE_RECOVERED: '故障恢复',
  SENSOR_READING: '传感器上报',
} as const
export const eventStatuses: Record<string, string> = {
  APPLIED: '已处理',
  PROCESSED: '已处理',
  IGNORED_STALE: '忽略旧序号',
  IGNORED_SESSION: '忽略旧会话',
  IGNORED_DISABLED: '设备已停用',
}
export function formatTime(value: string | null) {
  if (!value) return '尚未上报'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).format(new Date(value))
}
