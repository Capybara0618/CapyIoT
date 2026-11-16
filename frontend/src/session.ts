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
      typeof saved.user?.username === 'string' &&
      typeof saved.user?.campusId === 'string' &&
      ['ADMIN', 'OPERATOR'].includes(saved.user?.role)
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
    body: JSON.stringify({ username, password }),
  })
  sessionStorage.setItem('capyiot.token', result.accessToken)
  sessionStorage.setItem('capyiot.session', JSON.stringify(result))
  session.value = result
}

export function logout() {
  sessionStorage.removeItem('capyiot.token')
  sessionStorage.removeItem('capyiot.session')
  session.value = null
}
