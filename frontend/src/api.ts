export interface ApiResponse<T> {
  code: string
  message: string
  data: T
  traceId: string
}

export interface UserInfo {
  username: string
  role: 'ADMIN' | 'OPERATOR'
  campusId: string
}

export interface LoginResult {
  accessToken: string
  tokenType: 'Bearer'
  expiresAt: string
  user: UserInfo
}

export interface Health {
  service: string
  status: 'UP'
  checkedAt: string
}

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message)
  }
}

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body) headers.set('Content-Type', 'application/json')
  const token = sessionStorage.getItem('capyiot.token')
  if (token) headers.set('Authorization', `Bearer ${token}`)
  let response: Response
  try {
    response = await fetch(`/api/v1${path}`, {
      ...init,
      headers,
      signal: AbortSignal.timeout(10000),
    })
  } catch {
    throw new ApiError('请求未完成，请检查服务连接后重试', 0)
  }
  const result = (await response.json().catch(() => null)) as ApiResponse<T> | null
  if (!response.ok) {
    if (response.status === 401 && path !== '/auth/login') {
      sessionStorage.removeItem('capyiot.token')
      sessionStorage.removeItem('capyiot.session')
      window.dispatchEvent(new Event('capyiot:unauthorized'))
    }
    throw new ApiError(result?.message ?? '请求失败，请稍后重试', response.status)
  }
  if (!result || result.code !== 'OK') throw new ApiError('服务响应格式异常', response.status)
  return result.data
}
