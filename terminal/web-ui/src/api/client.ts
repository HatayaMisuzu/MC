import type { Operation, OperationPlan, StreamEvent } from '../types'

const csrf = (() => {
  const params = new URLSearchParams(window.location.hash.slice(1))
  const value = params.get('csrf') ?? sessionStorage.getItem('mcac.csrf') ?? ''
  if (value) sessionStorage.setItem('mcac.csrf', value)
  if (window.location.hash) history.replaceState(null, '', window.location.pathname)
  return value
})()

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: string,
  ) {
    super(message)
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(path, {
    ...init,
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
      'X-MCAC-CSRF': csrf,
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...init.headers,
    },
  })
  const payload = (await response.json().catch(() => ({}))) as Record<string, unknown>
  if (!response.ok) {
    throw new ApiError(
      String(payload.message ?? `请求失败 (${response.status})`),
      response.status,
      String(payload.code ?? 'REQUEST_FAILED'),
    )
  }
  return payload as T
}

export function post<T>(path: string, body: Record<string, unknown> = {}): Promise<T> {
  return api<T>(path, { method: 'POST', body: JSON.stringify(body) })
}

export function createPlan(
  category: string,
  body: Record<string, unknown>,
): Promise<OperationPlan> {
  return post<OperationPlan>(`/api/${category}/plan`, body)
}

export function executePlan(plan: OperationPlan): Promise<Operation> {
  return post<Operation>(`/api/${plan.category}/execute`, {
    planId: plan.planId,
    confirmation: plan.planId,
  })
}

export async function waitForOperation(
  id: string,
  onUpdate: (operation: Operation) => void,
): Promise<Operation> {
  for (;;) {
    const operation = await api<Operation>(`/api/operations/${encodeURIComponent(id)}`)
    onUpdate(operation)
    if (operation.state === 'SUCCEEDED' || operation.state === 'FAILED') return operation
    await new Promise((resolve) => window.setTimeout(resolve, 250))
  }
}

export async function streamEvents(
  onEvent: (event: StreamEvent) => void,
  signal: AbortSignal,
  instanceId = '',
): Promise<void> {
  const query = instanceId ? `?instanceId=${encodeURIComponent(instanceId)}` : ''
  const response = await fetch(`/api/events${query}`, {
    credentials: 'same-origin',
    headers: { Accept: 'text/event-stream', 'X-MCAC-CSRF': csrf },
    signal,
  })
  if (!response.ok || !response.body) throw new ApiError('实时事件连接失败', response.status, 'SSE_FAILED')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (!signal.aborted) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const data = block
        .split('\n')
        .filter((line) => line.startsWith('data: '))
        .map((line) => line.slice(6))
        .join('')
      if (data) onEvent(JSON.parse(data) as StreamEvent)
      boundary = buffer.indexOf('\n\n')
    }
  }
}

export async function streamLogSnapshots(
  instanceId: string,
  kind: 'minecraft' | 'runtime',
  onSnapshot: (snapshot: { kind: string; available: boolean; lines: string[] }) => void,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch(
    `/api/logs/stream?instanceId=${encodeURIComponent(instanceId)}&kind=${kind}`,
    {
      credentials: 'same-origin',
      headers: { Accept: 'text/event-stream', 'X-MCAC-CSRF': csrf },
      signal,
    },
  )
  if (!response.ok || !response.body)
    throw new ApiError('实时日志连接失败', response.status, 'LOG_SSE_FAILED')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (!signal.aborted) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const block = buffer.slice(0, boundary)
      buffer = buffer.slice(boundary + 2)
      const raw = block
        .split('\n')
        .filter((line) => line.startsWith('data: '))
        .map((line) => line.slice(6))
        .join('')
      if (raw) {
        const event = JSON.parse(raw) as StreamEvent
        if (event.type === 'LOG_SNAPSHOT' && event.data)
          onSnapshot(event.data as { kind: string; available: boolean; lines: string[] })
      }
      boundary = buffer.indexOf('\n\n')
    }
  }
}

export const csrfReady = Boolean(csrf)
