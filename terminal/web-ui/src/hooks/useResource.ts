import { useCallback, useEffect, useRef, useState } from 'react'

export function useResource<T>(loader: () => Promise<T>, dependencies: unknown[] = []) {
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const generation = useRef(0)

  const refresh = useCallback(async () => {
    const request = ++generation.current
    setLoading(true)
    try {
      const next = await loader()
      if (request === generation.current) {
        setData(next)
        setError(null)
      }
    } catch (failure) {
      if (request === generation.current)
        setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      if (request === generation.current) setLoading(false)
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

  return { data, loading, error, refresh }
}
