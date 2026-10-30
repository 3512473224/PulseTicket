import type { ApiResult } from './types'

let token = ''

export function setToken(value: string) {
  token = value
}

export async function api<T = any>(
  path: string,
  options: RequestInit = {},
  overrideToken?: string
): Promise<ApiResult<T>> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...((options.headers as Record<string, string>) || {})
  }
  const bearer = overrideToken ?? token
  if (bearer) headers.Authorization = `Bearer ${bearer}`

  try {
    const resp = await fetch(path, { ...options, headers })
    const text = await resp.text()
    return text ? JSON.parse(text) : { code: resp.status, message: resp.statusText, data: null as T }
  } catch (e: any) {
    return { code: 503, message: e?.message || '网络异常，请确认网关已启动', data: null as T }
  }
}

/**
 * Feign 降级时下游错误会被包裹成一长串原始 JSON，这里提取出真正给人看的那句话。
 */
export function readableMessage(raw?: string): string {
  if (!raw) return ''
  const match = raw.match(/"message"\s*:\s*"([^"]+)"/)
  const text = match ? match[1] : raw
  return text.length > 80 ? `${text.slice(0, 80)}…` : text
}
