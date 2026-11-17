import type { Device, DeviceEvent, DeviceType } from './domain'

export const scenarioNames = {
  NETWORK_UPSTREAM_FAILURE: '交换机故障',
  DEVICE_FLAPPING: '设备状态抖动',
  SENSOR_THRESHOLD_BREACH: '温度越限',
} as const
export type TemplateId = keyof typeof scenarioNames

export interface Candidate {
  id: string
  code: string
  name: string
  type: DeviceType
  buildingId: string
  buildingName: string
  upstreamId: string | null
}
export interface Catalogue {
  templates: {
    id: TemplateId
    minimumSeconds: number
    defaultSeconds: number
    maximumSeconds: number
  }[]
  scope: { devices: Candidate[]; setupMessage: string | null }
  heartbeatSeconds: number
  timeoutSeconds: number
  sweepSeconds: number
}
export type Phase =
  | 'WARMING'
  | 'OBSERVING'
  | 'REBOUNDING'
  | 'OBSERVING_AGAIN'
  | 'RECOVERING'
  | 'RECOVERY_REQUIRED'
  | 'RECOVERED'
  | 'STOPPED'
  | 'FAILED'
export interface ScenarioRun {
  id: string
  campusId: string
  template: TemplateId
  operator: string
  targetIds: string[]
  durationSeconds: number
  temperature: number | null
  phase: Phase
  endMode: 'STOP' | 'RECOVER' | 'FAILURE' | null
  startedAt: string
  phaseAt: string
  observedAt: string | null
  endedAt: string | null
}
export interface ScenarioDetail {
  run: ScenarioRun
  targets: Device[]
  logs: { id: number; at: string; message: string }[]
  evidence: DeviceEvent[]
  checkedAt: string
}
export const phases: Record<Phase, string> = {
  WARMING: '心跳预热',
  OBSERVING: '故障观察',
  REBOUNDING: '恢复心跳',
  OBSERVING_AGAIN: '再次暂停心跳',
  RECOVERING: '恢复验证中',
  RECOVERY_REQUIRED: '需要重试恢复',
  RECOVERED: '已恢复',
  STOPPED: '已停止',
  FAILED: '注入失败，设备已恢复',
}
