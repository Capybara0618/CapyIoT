/** Incremental POST-SSE decoder. Unknown events/fields are never rendered as model text. */
export const agentEventNames = new Set([
  'run.started', 'step.started', 'tool.started', 'tool.completed', 'answer.delta',
  'draft.ready', 'run.completed', 'run.failed', 'run.cancelled',
])
export interface AgentEvent {
  name: string
  runId: string
  seq: number
  data: Record<string, unknown>
}
export class AgentEventDecoder {
  private decoder = new TextDecoder('utf-8', { fatal: true })
  private buffer = ''
  private lines: string[] = []
  private bytes = 0
  private sequence = 0
  private identity = ''
  private completed = false
  private count = 0
  private receive: (event: AgentEvent) => void
  constructor(receive: (event: AgentEvent) => void) { this.receive = receive }
  feed(chunk: Uint8Array) { this.consume(this.decoder.decode(chunk, { stream: true })) }
  finish() { this.consume(this.decoder.decode()); if (this.buffer || this.lines.length) throw new Error('响应流中断，请核对运行状态') }
  private consume(text: string) {
    this.buffer += text
    if (this.buffer.length > 262144) throw new Error('响应片段超出上限')
    while (true) {
      const match = /\r\n|\r|\n/.exec(this.buffer)
      if (!match) break
      // A CRLF may straddle two network chunks.
      if (match[0] === '\r' && match.index === this.buffer.length - 1) break
      const line = this.buffer.slice(0, match.index)
      this.buffer = this.buffer.slice(match.index + match[0].length)
      if (!line) { this.dispatch(); this.lines = []; this.bytes = 0 }
      else if (!line.startsWith(':')) {
        this.bytes += line.length
        if (this.bytes > 262144) throw new Error('响应事件超出上限')
        this.lines.push(line)
      }
    }
  }
  private dispatch() {
    let name = 'message', id = '', content = ''
    for (const line of this.lines) {
      const split = line.indexOf(':')
      const field = split < 0 ? line : line.slice(0, split)
      const value = split < 0 ? '' : line.slice(split + 1).replace(/^ /, '')
      if (field === 'event') name = value
      else if (field === 'id') id = value
      else if (field === 'data') content += value + '\n'
    }
    if (!content || !agentEventNames.has(name) || this.completed) return
    const data: unknown = JSON.parse(content)
    if (!data || typeof data !== 'object' || Array.isArray(data)) throw new Error('响应事件格式无效')
    const values = data as Record<string, unknown>
    const seq = values.seq, runId = values.runId
    if (!Number.isSafeInteger(seq) || typeof seq !== 'number' || seq < 1 || typeof runId !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(runId) || (id && id !== String(seq))) throw new Error('响应事件标识无效')
    if (this.identity && this.identity !== runId) throw new Error('响应事件混入其他运行')
    if (seq <= this.sequence) return
    if (!this.identity && (name !== 'run.started' || seq !== 1)) throw new Error('缺少运行开始事件')
    if (++this.count > 1000) throw new Error('响应事件数超出上限')
    this.identity = runId; this.sequence = seq
    this.completed = ['run.completed', 'run.failed', 'run.cancelled'].includes(name)
    // No thinking/reasoning/raw tool arguments are forwarded to the view layer.
    const allowed = ['conversationId','stepType','summary','toolCallId','toolName','status','durationMs','evidenceCount','contentDelta','draftId','draftRevision','draftStatus','contentHash','draftSummary','answerMessageId','errorCode','safeMessage']
    this.receive({ name, runId, seq, data: Object.fromEntries(Object.entries(values).filter(([key]) => allowed.includes(key))) })
  }
}
