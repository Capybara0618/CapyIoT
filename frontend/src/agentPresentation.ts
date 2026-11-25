import type { AgentAnswer, EvidenceReference } from './agentChat'

const identity = (value: unknown): value is string =>
  typeof value === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(value)
export function visibleAnswer(
  answer: unknown,
  references: EvidenceReference[],
): AgentAnswer | null {
  if (!answer || typeof answer !== 'object') return null
  const value = answer as Record<string, unknown>
  const ids = new Set(references.map((item) => item.evidenceId))
  for (const field of ['facts', 'inferences', 'recommendations', 'unknowns']) {
    const entries = value[field]
    if (!Array.isArray(entries) || entries.length > 20) return null
    if (field === 'facts' || field === 'inferences') {
      if (
        entries.some(
          (item) =>
            !item ||
            typeof item.statement !== 'string' ||
            item.statement.length > 2000 ||
            !Array.isArray(item.evidenceIds) ||
            !item.evidenceIds.length ||
            item.evidenceIds.some((id: unknown) => !identity(id) || !ids.has(id)),
        )
      )
        return null
    } else if (entries.some((item) => typeof item !== 'string' || item.length > 4000)) return null
  }
  return value as unknown as AgentAnswer
}

export function evidenceLinks(reference: EvidenceReference) {
  const links: { label: string; path: string; query: Record<string, string> }[] = []
  for (const object of (reference.sourceObjects ?? []).slice(0, 8)) {
    if (
      !identity(object.sourceId) ||
      !['DEVICE', 'INCIDENT', 'WORK_ORDER'].includes(object.sourceType)
    )
      continue
    const target = {
      DEVICE: { path: '/devices', label: '设备' },
      INCIDENT: { path: '/alarms', label: '故障' },
      WORK_ORDER: { path: '/workorders', label: '工单' },
    }[object.sourceType as 'DEVICE' | 'INCIDENT' | 'WORK_ORDER']
    if (target)
      links.push({
        ...target,
        label: target.label + ' ' + object.sourceId,
        query: { id: object.sourceId },
      })
  }
  const device = reference.sourceObjects?.find(
    (item) => item.sourceType === 'DEVICE' && identity(item.sourceId),
  )
  if (reference.toolName === 'get_topology_context' && device)
    links.unshift({
      label: '查看关联拓扑',
      path: '/topology',
      query: { deviceId: device.sourceId },
    })
  return links
}
