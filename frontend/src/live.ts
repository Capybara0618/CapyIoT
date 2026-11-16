import { onBeforeUnmount, onMounted, ref } from 'vue'

export function useLiveUpdates(onChange: () => void, path = '/api/v1/stream') {
  const status = ref<'connecting' | 'connected' | 'disconnected'>('connecting')
  let controller: AbortController | undefined
  let idleTimer: ReturnType<typeof setTimeout> | undefined
  let stopped = false
  let wake: (() => void) | undefined
  let retryTimer: ReturnType<typeof setTimeout> | undefined

  async function connect() {
    let delay = 1000
    while (!stopped) {
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
        onChange()
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
              if (
                frame
                  .split('\n')
                  .some((line) =>
                    /^event:\s*(events\.changed|device\.state\.changed|alarm\.updated)$/.test(line),
                  )
              )
                onChange()
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
      await new Promise<void>((resolve) => {
        wake = resolve
        retryTimer = setTimeout(resolve, delay)
      })
      delay = Math.min(delay * 2, 15000)
    }
  }
  function reconnect() {
    controller?.abort()
    clearTimeout(retryTimer)
    wake?.()
  }
  function offline() {
    status.value = 'disconnected'
    controller?.abort()
  }
  onMounted(() => {
    window.addEventListener('online', reconnect)
    window.addEventListener('offline', offline)
    void connect()
  })
  onBeforeUnmount(() => {
    stopped = true
    window.removeEventListener('online', reconnect)
    window.removeEventListener('offline', offline)
    controller?.abort()
    clearTimeout(idleTimer)
    clearTimeout(retryTimer)
    wake?.()
  })
  return status
}
