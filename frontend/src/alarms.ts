import type { Device, Page } from './domain'
import type { WorkSummary } from './workorders'

export interface Topology {
  version: number
  nodes: Device[]
  edges: { id: string; source: string; target: string; version: number }[]
  totalNodes: number
  truncated: boolean
}

export interface AlarmSummary {
  active: number
  critical: number
  grouped: number
  affected: number
}

export interface Overview {
  devices: { total: number; online: number; offline: number; abnormal: number }
  alarms: AlarmSummary
  workorders: WorkSummary
}

export interface Alarm {
  id: number
  alarmNo: string
  deviceId: string
  deviceName: string
  buildingId: string
  alarmType: string
  severity: 'CRITICAL' | 'WARNING'
  status: 'OPEN' | 'ACKNOWLEDGED' | 'RECOVERED'
  relationKind: 'INDEPENDENT' | 'CONFIRMED_UPSTREAM' | 'SUSPECTED_UPSTREAM'
  parentAlarmId: number | null
  rootNetworkNodeId: string | null
  suppressedCount: number
  childCount: number
  affectedCount: number
  firstSeenAt: string
  lastSeenAt: string
  recoveredAt: string | null
  acknowledgedAt: string | null
  acknowledgedBy: string | null
  version: number
}

export interface Evidence {
  eventId: string
  causeEventId: string
  deviceId: string
  reason: string
  evidenceType: string
  upstreamNodeId: string | null
  topologyVersion: number
  traceId: string
  occurredAt: string
  receivedAt: string
  payload: Record<string, unknown> | null
}

export interface AlarmDetail {
  alarm: Alarm
  children: Page<Alarm>
  evidence: Page<Evidence>
  devices: Page<{
    deviceId: string
    deviceName: string
    impactKind: 'ROOT' | 'AFFECTED'
    firstObservedAt: string
  }>
}

export interface AlarmRules {
  topologyWindowSeconds: number
  minimumDownstream: number
  flapCooldownSeconds: number
  temperatureLimit: number
  temperatureRecovery: number
}

export interface ImpactMarker {
  deviceId: string
  alarmId: number
  relationKind: Alarm['relationKind']
  rootNetworkNodeId: string | null
}

export const alarmTypes: Record<string, string> = {
  NETWORK_INCIDENT: '上游网络故障',
  NETWORK_UPSTREAM_FAILURE: '交换机故障',
  DEVICE_OFFLINE: '设备离线',
  SENSOR_THRESHOLD_BREACH: '温度越限',
  DEVICE_FAULT: '设备故障',
}
export const severityLabels = { CRITICAL: '严重', WARNING: '警告' }
export const alarmStatuses = { OPEN: '待确认', ACKNOWLEDGED: '已确认', RECOVERED: '已恢复' }
export const relations = {
  CONFIRMED_UPSTREAM: '已确认上游故障',
  SUSPECTED_UPSTREAM: '疑似上游故障',
  INDEPENDENT: '独立告警',
}
export const evidenceTypes: Record<string, string> = {
  RULE_TRIGGER: '触发规则',
  REPEAT_SUPPRESSED: '重复抑制',
  FLAP_SUPPRESSED: '抖动抑制',
  UPSTREAM_FAULT: '上游故障证据',
  TOPOLOGY_CONFIRMED: '确认拓扑关联',
  TOPOLOGY_CLUSTER: '集中离线关联',
  RECOVERY: '恢复证据',
}

export function alarmTitle(alarm: Alarm) {
  return `${alarm.deviceName} · ${alarmTypes[alarm.alarmType] ?? alarm.alarmType}`
}
