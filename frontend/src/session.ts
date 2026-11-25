import { ref } from 'vue'
import { request, type LoginResult } from './api'

function readSession(): LoginResult | null {
  try {
    const saved = JSON.parse(
      sessionStorage.getItem('capyiot.session') ?? 'null',
    ) as LoginResult | null
    if (
      typeof saved?.accessToken === 'string' &&
      Date.parse(saved.expiresAt) > Date.now() &&
      typeof saved.user?.userId === 'string' &&
      typeof saved.user?.loginName === 'string' &&
      Array.isArray(saved.user?.roleCodes)
    )
      return saved
  } catch {
    // Invalid or expired storage requires a fresh login.
  }
  sessionStorage.removeItem('capyiot.token')
  sessionStorage.removeItem('capyiot.session')
  return null
}

export const session = ref<LoginResult | null>(readSession())

export async function login(username: string, password: string) {
  const result = await request<LoginResult>('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ loginName: username, password }),
  })
  sessionStorage.setItem('capyiot.token', result.accessToken)
  sessionStorage.setItem('capyiot.session', JSON.stringify(result))
  session.value = result
  window.dispatchEvent(new Event('capyiot:session-changed'))
}

export function clearSession() {
  sessionStorage.removeItem('capyiot.token')
  sessionStorage.removeItem('capyiot.session')
  session.value = null
  window.dispatchEvent(new Event('capyiot:session-changed'))
}

export async function logout() {
  try {
    await request('/auth/logout', { method: 'POST' })
  } finally {
    clearSession()
  }
}
