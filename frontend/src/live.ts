import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'

const businessEvents = [
  'events.changed',
  'device.state.changed',
  'alarm.updated',
  'workorder.updated',
]

type Subscriber = { onChange: () => void; eventNames: readonly string[] }
const streams = new Map<string, ReturnType<typeof openStream>>()

function openStream(path: string) {
  const status = ref<'connecting' | 'connected' | 'disconnected'>('connecting')
  const subscribers = new Set<Subscriber>()
  let controller: AbortController | undefined
  let idleTimer: ReturnType<typeof setTimeout> | undefined
  let stopped = false
  let wake: (() => void) | undefined
  let retryTimer: ReturnType<typeof setTimeout> | undefined

  function notify(name?: string) {
    for (const subscriber of subscribers) {
      if (!name || subscriber.eventNames.includes(name)) subscriber.onChange()
    }
  }

  function pause(delay?: number) {
    return new Promise<void>((resolve) => {
      wake = () => {
        clearTimeout(retryTimer)
        retryTimer = undefined
        wake = undefined
        resolve()
      }
      if (delay !== undefined) retryTimer = setTimeout(wake, delay)
    })
  }

  async function connect() {
    let delay = 1000
    while (!stopped) {
      if (document.hidden || !navigator.onLine) {
        status.value = 'disconnected'
        await pause()
        continue
      }
      status.value = 'connecting'
      const connection = new AbortController()
      controller = connection
      const keepAlive = () => {
        clearTimeout(idleTimer)
        idleTimer = setTimeout(() => connection.abort(), 35000)
      }
      try {
        keepAlive()
        const response = await fetch(path, {
          headers: { Authorization: `Bearer ${sessionStorage.getItem('capyiot.token') ?? ''}` },
          signal: connection.signal,
        })
        if (response.status === 401) {
          window.dispatchEvent(new Event('capyiot:unauthorized'))
          break
        }
        if (
          !response.ok ||
          !response.body ||
          !response.headers.get('content-type')?.includes('text/event-stream')
        )
          throw new Error('Stream unavailable')
        status.value = 'connected'
        delay = 1000
        notify()
        const reader = response.body.getReader()
        const decoder = new TextDecoder()
        let buffer = ''
        try {
          while (!stopped) {
            const { value, done } = await reader.read()
            if (done) break
            keepAlive()
            buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n')
            if (buffer.length > 65536) throw new Error('Stream frame too large')
            let boundary = buffer.indexOf('\n\n')
            while (boundary >= 0) {
              const frame = buffer.slice(0, boundary)
              buffer = buffer.slice(boundary + 2)
              const event = frame.split('\n').find((line) => line.startsWith('event:'))
              if (event) notify(event.slice(6).trim())
              boundary = buffer.indexOf('\n\n')
            }
          }
        } finally {
          reader.releaseLock()
        }
      } catch {
        /* The next connection re-queries durable state through onChange. */
      } finally {
        clearTimeout(idleTimer)
        connection.abort()
      }
      status.value = 'disconnected'
      if (stopped) break
      await pause(delay)
      delay = Math.min(delay * 2, 15000)
    }
  }
  function reconnect() {
    controller?.abort()
    wake?.()
  }
  function offline() {
    status.value = 'disconnected'
    controller?.abort()
    wake?.()
  }

  function visibility() {
    if (document.hidden) offline()
    else reconnect()
  }

  window.addEventListener('online', reconnect)
  window.addEventListener('offline', offline)
  document.addEventListener('visibilitychange', visibility)

  function close() {
    stopped = true
    window.removeEventListener('online', reconnect)
    window.removeEventListener('offline', offline)
    document.removeEventListener('visibilitychange', visibility)
    controller?.abort()
    clearTimeout(idleTimer)
    wake?.()
  }
  return {
    status,
    subscribers,
    close,
    add(subscriber: Subscriber) {
      subscribers.add(subscriber)
      if (subscribers.size === 1) void connect()
      else if (status.value === 'connected') subscriber.onChange()
    },
  }
}

export function useLiveUpdates(
  onChange: () => void,
  path = '/api/v1/stream',
  eventNames: readonly string[] = businessEvents,
) {
  const connection = shallowRef<ReturnType<typeof openStream>>()
  const subscriber = { onChange, eventNames }
  onMounted(() => {
    const stream = streams.get(path) ?? openStream(path)
    streams.set(path, stream)
    connection.value = stream
    stream.add(subscriber)
  })
  onBeforeUnmount(() => {
    const stream = connection.value
    if (!stream) return
    stream.subscribers.delete(subscriber)
    if (stream.subscribers.size === 0) {
      stream.close()
      streams.delete(path)
    }
  })
  return computed(() => connection.value?.status.value ?? 'connecting')
}
