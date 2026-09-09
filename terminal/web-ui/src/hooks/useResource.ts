import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

interface ResourceSnapshot<T> {
  owner: object
  data: T | null
  loading: boolean
  error: string | null
}

export function useResource<T>(loader: () => Promise<T>, dependencies: unknown[] = [], resourceKey = '') {
  const owner = useMemo(() => ({ resourceKey }), dependencies)
  const [snapshot, setSnapshot] = useState<ResourceSnapshot<T>>(
    () => ({ owner, data: null, loading: true, error: null }),
  )
  const generation = useRef(0)

  const refresh = useCallback(async () => {
    const request = ++generation.current
    setSnapshot((current) => ({
      owner,
      data: current.owner === owner ? current.data : null,
      loading: true,
      error: null,
    }))
    try {
      const next = await loader()
      if (request === generation.current) {
        setSnapshot({ owner, data: next, loading: false, error: null })
      }
    } catch (failure) {
      if (request === generation.current) {
        setSnapshot({
          owner,
          data: null,
          loading: false,
          error: failure instanceof Error ? failure.message : String(failure),
        })
      }
    } finally {
      if (request === generation.current) {
        setSnapshot((current) => current.owner === owner ? { ...current, loading: false } : current)
      }
    }
  }, dependencies)

  useEffect(() => {
    void refresh()
    return () => { generation.current++ }
  }, [refresh])

  useEffect(() => {
    const reload = () => void refresh()
    window.addEventListener('mcac:refresh', reload)
    return () => window.removeEventListener('mcac:refresh', reload)
  }, [refresh])

  const current = snapshot.owner === owner
  return {
    data: current ? snapshot.data : null,
    loading: current ? snapshot.loading : true,
    error: current ? snapshot.error : null,
    dataKey: current && snapshot.data !== null ? resourceKey : null,
    stateKey: current ? resourceKey : null,
    refresh,
  }
}
