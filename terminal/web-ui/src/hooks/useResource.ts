import { useCallback, useEffect, useState } from 'react'

export function useResource<T>(loader: () => Promise<T>, dependencies: unknown[] = []) {
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setData(await loader())
      setError(null)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setLoading(false)
    }
  }, dependencies)

  useEffect(() => {
    void refresh()
  }, [refresh])

  useEffect(() => {
    const reload = () => void refresh()
    window.addEventListener('mcac:refresh', reload)
    return () => window.removeEventListener('mcac:refresh', reload)
  }, [refresh])

  return { data, loading, error, refresh }
}
