import { useState, useEffect, useCallback, useRef } from 'react'

/**
 * useFetch — generic data-fetching hook.
 *
 * Usage:
 *   const { data, loading, error, refetch } = useFetch(
 *     () => issuesApi.getAll(projectId),
 *     [projectId]           // re-fetch whenever these change
 *   )
 *
 * Fix applied: Previously the hook spread `deps` directly into useCallback, which
 * meant the `fetchFn` closure itself was NOT in the dependency array. This caused
 * stale closures where the callback captured an outdated `fetchFn` reference.
 * We now store the latest `fetchFn` in a ref so useCallback can safely reference
 * it without adding it as a reactive dependency (avoiding infinite render loops).
 */
export function useFetch(fetchFn, deps = []) {
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  // Keep a stable ref to the latest fetchFn so the callback never goes stale
  // without requiring fetchFn itself to be referentially stable (which it often isn't).
  const fetchFnRef = useRef(fetchFn)
  useEffect(() => {
    fetchFnRef.current = fetchFn
  })

  const execute = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await fetchFnRef.current()
      setData(res.data.data)
    } catch (err) {
      setError(err.response?.data?.message || err.message)
    } finally {
      setLoading(false)
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  useEffect(() => { execute() }, [execute])

  return { data, loading, error, refetch: execute }
}

