/**
 * ApiCache — global in-memory request cache with:
 *   - Per-key TTL (default 5 min, configurable)
 *   - Request deduplication (in-flight requests are shared, not duplicated)
 *   - Stale-while-revalidate (return stale data immediately, refresh in background)
 *   - Manual invalidation by key prefix
 *   - Automatic stats tracking
 *
 * Usage:
 *   import { apiCache } from './apiCache'
 *   const data = await apiCache.get('checklists-47168', () => checklistsApi.getAll('47168'))
 *   apiCache.invalidate('checklists-')   // bust all checklist keys
 *   apiCache.invalidateAll()             // bust everything
 */

const DEFAULT_TTL_MS     = 5  * 60 * 1000   // 5 min — entity lists
const LONG_TTL_MS        = 15 * 60 * 1000   // 15 min — projects, roles, companies
const SHORT_TTL_MS       = 2  * 60 * 1000   // 2 min  — sync stats / dashboard
const STALE_WINDOW_MS    = 30 * 1000         // 30 sec — SWR window after TTL

// TTL overrides per key prefix
const TTL_MAP = {
  'projects':   LONG_TTL_MS,
  'roles':      LONG_TTL_MS,
  'companies':  LONG_TTL_MS,
  'persons':    DEFAULT_TTL_MS,
  'checklists': DEFAULT_TTL_MS,
  'tasks':      DEFAULT_TTL_MS,
  'issues':     DEFAULT_TTL_MS,
  'assets':     DEFAULT_TTL_MS,
  'sync-stats': SHORT_TTL_MS,
  'dashboard':  SHORT_TTL_MS,
  'file-':      10 * 60 * 1000,
}

function getTtl(key) {
  for (const [prefix, ttl] of Object.entries(TTL_MAP)) {
    if (key.startsWith(prefix)) return ttl
  }
  return DEFAULT_TTL_MS
}

class ApiCache {
  constructor() {
    this._cache   = new Map()   // key → { data, ts, ttl }
    this._inflight= new Map()   // key → Promise (dedup)
    this._stats   = { hits: 0, misses: 0, stale: 0, deduped: 0 }
  }

  /**
   * Get cached value or fetch fresh.
   * @param {string} key   — cache key
   * @param {Function} fn  — async fetcher, called only on cache miss
   * @param {object} opts  — { ttl, swr } overrides
   * @returns {Promise<any>}  resolved value (never the raw axios response)
   */
  async get(key, fn, opts = {}) {
    const ttl = opts.ttl ?? getTtl(key)
    const swr = opts.swr !== false  // stale-while-revalidate on by default
    const now = Date.now()

    const cached = this._cache.get(key)

    if (cached) {
      const age = now - cached.ts
      if (age < cached.ttl) {
        // Fresh cache hit
        this._stats.hits++
        return cached.data
      }
      if (swr && age < cached.ttl + STALE_WINDOW_MS) {
        // Stale-while-revalidate: return stale data, refresh in background
        this._stats.stale++
        this._revalidate(key, fn, ttl)
        return cached.data
      }
    }

    // Cache miss — fetch, deduplicate concurrent requests
    this._stats.misses++
    return this._fetch(key, fn, ttl)
  }

  _fetch(key, fn, ttl) {
    // If there's already an in-flight request for this key, share it
    if (this._inflight.has(key)) {
      this._stats.deduped++
      return this._inflight.get(key)
    }

    const promise = fn()
      .then(res => {
        // Support both raw values and axios { data: { data: ... } } responses
        const value = res?.data?.data !== undefined ? res.data.data : res
        this._cache.set(key, { data: value, ts: Date.now(), ttl })
        return value
      })
      .finally(() => {
        this._inflight.delete(key)
      })

    this._inflight.set(key, promise)
    return promise
  }

  _revalidate(key, fn, ttl) {
    if (this._inflight.has(key)) return  // already refreshing
    this._fetch(key, fn, ttl).catch(() => {}) // silent background refresh
  }

  /** Immediately invalidate all keys matching a prefix */
  invalidate(prefix) {
    for (const key of this._cache.keys()) {
      if (key.startsWith(prefix)) this._cache.delete(key)
    }
    for (const key of this._inflight.keys()) {
      if (key.startsWith(prefix)) this._inflight.delete(key)
    }
  }

  /** Invalidate a single exact key */
  invalidateKey(key) {
    this._cache.delete(key)
    this._inflight.delete(key)
  }

  /** Bust everything */
  invalidateAll() {
    this._cache.clear()
    this._inflight.clear()
  }

  /** Debug info */
  getStats() {
    return {
      ...this._stats,
      cacheSize: this._cache.size,
      inflight:  this._inflight.size,
      hitRate:   this._stats.hits + this._stats.misses > 0
        ? Math.round(this._stats.hits / (this._stats.hits + this._stats.misses) * 100) + '%'
        : 'n/a',
    }
  }

  /** Pre-warm: call multiple fetchers in parallel and cache all */
  async prefetch(entries) {
    return Promise.allSettled(entries.map(([key, fn]) => this.get(key, fn)))
  }
}

// Singleton — one cache for the entire app
export const apiCache = new ApiCache()

// Convenience key builders
export const cacheKeys = {
  projects:       ()             => 'projects-all',
  checklists:     (pid)          => `checklists-${pid}`,
  tasks:          (pid)          => `tasks-${pid}`,
  issues:         (pid)          => `issues-${pid}`,
  assets:         (pid)          => `assets-${pid}`,
  persons:        (pid)          => `persons-${pid}`,
  companies:      (pid)          => `companies-${pid}`,
  roles:          (pid)          => `roles-${pid}`,
  syncStats:      ()             => 'sync-stats',
  dashboard:      (pid)          => `dashboard-${pid}`,
  fileReport:     (pid)          => `file-report-${pid}`,
}
