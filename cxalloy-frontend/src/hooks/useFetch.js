import { useState, useEffect, useCallback, useRef } from 'react'
import { apiCache } from '../services/apiCache'

/**
 * useFetch — data-fetching hook with integrated ApiCache.
 *
 * Features:
 *   - Automatic caching via ApiCache (TTL, dedup, SWR)
 *   - Stale data shown immediately while fresh data loads in background
 *   - Deduplication: same key fetched by two components → single network call
 *   - Manual refetch (bypasses cache) and soft-refetch (uses SWR)
 *   - Tracks loading vs revalidating states separately so UI can show
 *     "refreshing" indicator without unmounting/remounting content
 *
 * Usage:
 *   const { data, loading, revalidating, error, refetch } = useFetch(
 *     'checklists-47168',
 *     () => checklistsApi.getAll('47168'),
 *     [activeProjectId]       // re-fetch when these change
 *   )
 *
 *   // Without caching (pass null key):
 *   const { data } = useFetch(null, () => someApi.call(), [dep])
 */
export function useFetch(cacheKey, fetchFn, deps = [], opts = {}) {
  const [data, setData]               = useState(null)
  const [loading, setLoading]         = useState(true)
  const [revalidating, setRevalidating] = useState(false)
  const [error, setError]             = useState(null)
  const mountedRef                    = useRef(true)
  const fetchFnRef                    = useRef(fetchFn)

  useEffect(() => {
    fetchFnRef.current = fetchFn
  })

  useEffect(() => {
    mountedRef.current = true
    return () => { mountedRef.current = false }
  }, [])

  const execute = useCallback(async (force = false) => {
    if (!mountedRef.current) return

    // If we already have data, show revalidating indicator instead of full spinner
    const hasData = data !== null
    if (hasData) setRevalidating(true)
    else setLoading(true)
    setError(null)

    try {
      let result
      if (cacheKey && !force) {
        result = await apiCache.get(cacheKey, () => fetchFnRef.current(), opts)
      } else if (cacheKey && force) {
        // Hard refetch: invalidate cache first
        apiCache.invalidateKey(cacheKey)
        result = await apiCache.get(cacheKey, () => fetchFnRef.current(), opts)
      } else {
        // No caching
        const res = await fetchFnRef.current()
        result = res?.data?.data !== undefined ? res.data.data : res
      }

      if (mountedRef.current) {
        setData(result)
        setError(null)
      }
    } catch (err) {
      if (mountedRef.current) {
        setError(err?.response?.data?.message || err.message || 'Request failed')
      }
    } finally {
      if (mountedRef.current) {
        setLoading(false)
        setRevalidating(false)
      }
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cacheKey, ...deps])

  useEffect(() => {
    execute(false)
  }, [execute])

  return {
    data,
    loading,
    revalidating,
    error,
    refetch:     () => execute(true),   // force-bypass cache
    softRefetch: () => execute(false),  // use SWR (returns stale while refreshing)
  }
}
