import { useEffect, useState } from 'react'

const SYNC_REFRESH_EVENT = 'modum:sync-refresh'
const SYNC_REFRESH_STORAGE_KEY = 'modum:last-sync-refresh'

export function emitSyncRefresh({ projectId = null, scope = 'sync' } = {}) {
  if (typeof window === 'undefined') return

  const detail = {
    projectId: projectId || null,
    scope,
    at: new Date().toISOString(),
  }

  window.dispatchEvent(new CustomEvent(SYNC_REFRESH_EVENT, { detail }))

  try {
    window.localStorage.setItem(SYNC_REFRESH_STORAGE_KEY, JSON.stringify(detail))
  } catch {
    // Best effort only; the custom event already updates the current tab.
  }
}

export function useSyncRefreshSignal(projectIds = []) {
  const scopeKey = Array.isArray(projectIds)
    ? projectIds.filter(Boolean).map(String).sort().join(',')
    : String(projectIds || '')

  const [signal, setSignal] = useState(0)

  useEffect(() => {
    const ids = scopeKey ? scopeKey.split(',') : []
    const matchesScope = (detail) => !ids.length || !detail?.projectId || ids.includes(String(detail.projectId))
    const bump = () => setSignal((value) => value + 1)

    const handleRefresh = (event) => {
      if (matchesScope(event.detail)) {
        bump()
      }
    }

    const handleStorage = (event) => {
      if (event.key !== SYNC_REFRESH_STORAGE_KEY || !event.newValue) return
      try {
        const detail = JSON.parse(event.newValue)
        if (matchesScope(detail)) {
          bump()
        }
      } catch {
        // Ignore malformed payloads.
      }
    }

    window.addEventListener(SYNC_REFRESH_EVENT, handleRefresh)
    window.addEventListener('storage', handleStorage)

    return () => {
      window.removeEventListener(SYNC_REFRESH_EVENT, handleRefresh)
      window.removeEventListener('storage', handleStorage)
    }
  }, [scopeKey])

  return signal
}
