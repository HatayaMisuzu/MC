import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import { api, createPlan, executePlan, streamEvents, waitForOperation } from '../api/client'
import type { CompanionSnapshot, Instance, Operation, OperationPlan, StreamEvent, TerminalStatus } from '../types'

interface TerminalContextValue {
  status: TerminalStatus | null
  instances: Instance[]
  selectedId: string
  selected: Instance | null
  backendError: string | null
  loading: boolean
  events: StreamEvent[]
  companionSnapshot: CompanionSnapshot | null
  pendingPlan: OperationPlan | null
  operation: Operation | null
  planError: string | null
  select: (id: string) => void
  refresh: () => Promise<void>
  requestPlan: (category: string, body: Record<string, unknown>) => Promise<void>
  dismissPlan: () => void
  confirmPlan: () => Promise<void>
}

const Context = createContext<TerminalContextValue | null>(null)

export function TerminalProvider({ children }: { children: React.ReactNode }) {
  const [status, setStatus] = useState<TerminalStatus | null>(null)
  const [instances, setInstances] = useState<Instance[]>([])
  const [selectedId, setSelectedId] = useState(() => sessionStorage.getItem('mcac.instance') ?? '')
  const [backendError, setBackendError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [events, setEvents] = useState<StreamEvent[]>([])
  const [companionSnapshot, setCompanionSnapshot] = useState<CompanionSnapshot | null>(null)
  const [pendingPlan, setPendingPlan] = useState<OperationPlan | null>(null)
  const [operation, setOperation] = useState<Operation | null>(null)
  const [planError, setPlanError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    try {
      const [nextStatus, nextInstances] = await Promise.all([
        api<TerminalStatus>('/api/status'),
        api<Instance[]>('/api/instances'),
      ])
      setStatus(nextStatus)
      setInstances(nextInstances)
      setSelectedId((current) => {
        const next = nextInstances.some((value) => value.id === current)
          ? current
          : (nextStatus.selectedInstanceId ?? nextInstances[0]?.id ?? '')
        if (next) sessionStorage.setItem('mcac.instance', next)
        return next
      })
      setBackendError(null)
    } catch (failure) {
      setBackendError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
    const interval = window.setInterval(() => void refresh(), 5000)
    return () => window.clearInterval(interval)
  }, [refresh])

  useEffect(() => {
    const controller = new AbortController()
    void streamEvents(
      (event) => {
        setEvents((current) => [event, ...current].slice(0, 200))
        if (event.type === 'STATUS' && event.data) {
          setStatus(event.data as TerminalStatus)
          setBackendError(null)
        }
        if (event.type === 'COMPANIONS' && event.data)
          setCompanionSnapshot(event.data as CompanionSnapshot)
      },
      controller.signal,
      selectedId,
    ).catch((failure) => {
      if (!controller.signal.aborted) {
        setBackendError(failure instanceof Error ? failure.message : String(failure))
      }
    })
    return () => controller.abort()
  }, [selectedId])

  useEffect(() => {
    const windowId = crypto.randomUUID()
    const notify = (action: 'open' | 'heartbeat' | 'close') => {
      void fetch(`/api/window/${action}`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
          'Content-Type': 'application/json',
          'X-MCAC-CSRF': sessionStorage.getItem('mcac.csrf') ?? '',
        },
        body: JSON.stringify({ windowId }),
        keepalive: action === 'close',
      })
    }
    notify('open')
    const heartbeat = window.setInterval(() => notify('heartbeat'), 15000)
    const close = () => notify('close')
    window.addEventListener('pagehide', close)
    return () => {
      window.clearInterval(heartbeat)
      window.removeEventListener('pagehide', close)
      close()
    }
  }, [])

  const select = useCallback((id: string) => {
    sessionStorage.setItem('mcac.instance', id)
    setSelectedId(id)
  }, [])

  const requestPlan = useCallback(async (category: string, body: Record<string, unknown>) => {
    setPlanError(null)
    setOperation(null)
    try {
      setPendingPlan(await createPlan(category, body))
    } catch (failure) {
      setPlanError(failure instanceof Error ? failure.message : String(failure))
    }
  }, [])

  const confirmPlan = useCallback(async () => {
    if (!pendingPlan) return
    setPlanError(null)
    try {
      const queued = await executePlan(pendingPlan)
      setPendingPlan(null)
      setOperation(queued)
      const completed = await waitForOperation(queued.id, setOperation)
      if (completed.state === 'FAILED') setPlanError(completed.error ?? completed.message)
      await refresh()
      window.dispatchEvent(new Event('mcac:refresh'))
    } catch (failure) {
      setPlanError(failure instanceof Error ? failure.message : String(failure))
    }
  }, [pendingPlan, refresh])

  const value = useMemo<TerminalContextValue>(
    () => ({
      status,
      instances,
      selectedId,
      selected: instances.find((instance) => instance.id === selectedId) ?? null,
      backendError,
      loading,
      events,
      companionSnapshot,
      pendingPlan,
      operation,
      planError,
      select,
      refresh,
      requestPlan,
      dismissPlan: () => {
        setPendingPlan(null)
        setOperation(null)
        setPlanError(null)
      },
      confirmPlan,
    }),
    [
      status,
      instances,
      selectedId,
      backendError,
      loading,
      events,
      companionSnapshot,
      pendingPlan,
      operation,
      planError,
      select,
      refresh,
      requestPlan,
      confirmPlan,
    ],
  )

  return <Context.Provider value={value}>{children}</Context.Provider>
}

export function useTerminal() {
  const value = useContext(Context)
  if (!value) throw new Error('TerminalProvider is missing')
  return value
}
