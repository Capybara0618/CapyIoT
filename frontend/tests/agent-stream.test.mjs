import { test } from 'node:test'
import assert from 'node:assert/strict'
import { AgentEventDecoder } from '../src/agentStream.ts'

const encoder = new TextEncoder()
const event = (name,seq,data={},ending='\n') => `id: ${seq}${ending}event: ${name}${ending}data: ${JSON.stringify({runId:'run-1',seq,...data})}${ending}${ending}`
test('POST SSE survives every UTF-8 and CRLF chunk boundary and suppresses hidden fields',()=>{
  const frames=[]
  const decoder=new AgentEventDecoder(value=>frames.push(value))
  const source=encoder.encode(': heartbeat\r\n\r\n'+event('run.started',1,{},'\r\n')+event('step.started',2,{summary:'核查摄像机连接',thinking:'private reasoning',arguments:{token:'private'}},'\r\n')+event('run.completed',3,{status:'SUCCEEDED'},'\r\n'))
  for(const byte of source) decoder.feed(new Uint8Array([byte]))
  decoder.finish()
  assert.equal(frames.length,3)
  assert.equal(frames[1].data.summary,'核查摄像机连接')
  assert.equal('thinking' in frames[1].data,false)
  assert.equal('arguments' in frames[1].data,false)
})
test('duplicate events are ignored and unknown reasoning events never enter the view',()=>{
  const frames=[],decoder=new AgentEventDecoder(value=>frames.push(value))
  decoder.feed(encoder.encode(event('run.started',1)+event('run.started',1)+event('thinking.delta',2,{contentDelta:'hidden'})+event('run.completed',3)))
  decoder.finish();assert.equal(frames.length,2)
})
test('multiline data is assembled and truncated events fail closed',()=>{
  const frames=[],decoder=new AgentEventDecoder(value=>frames.push(value))
  decoder.feed(encoder.encode('id: 1\nevent: run.started\ndata: {"runId":"run-1",\ndata: "seq":1}\n\n'))
  assert.equal(frames.length,1)
  decoder.feed(encoder.encode('event: answer.delta\ndata: {'))
  assert.throws(()=>decoder.finish(),/中断/)
})
test('cross-run injection and sequence mismatches are rejected',()=>{
  const decoder=new AgentEventDecoder(()=>{})
  decoder.feed(encoder.encode(event('run.started',1)))
  assert.throws(()=>decoder.feed(encoder.encode(event('tool.started',2,{runId:'other-run'}))),/其他运行/)
  const invalid=new AgentEventDecoder(()=>{})
  assert.throws(()=>invalid.feed(encoder.encode('id: 9\nevent: run.started\ndata: {"runId":"run-1","seq":1}\n\n')),/标识/)
})
test('oversized payloads are rejected before unbounded buffering',()=>{
  const decoder=new AgentEventDecoder(()=>{})
  assert.throws(()=>decoder.feed(encoder.encode('x'.repeat(262145))),/上限/)
})
test('events after a terminal event cannot replace the answer',()=>{
  const frames=[],decoder=new AgentEventDecoder(value=>frames.push(value))
  decoder.feed(encoder.encode(event('run.started',1)+event('run.failed',2)+event('answer.delta',3,{contentDelta:'late'})))
  decoder.finish();assert.equal(frames.length,2)
})
