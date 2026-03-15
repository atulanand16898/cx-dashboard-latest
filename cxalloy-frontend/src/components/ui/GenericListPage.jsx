import React, { useState, useMemo, useCallback } from 'react'
import ReactDOM from 'react-dom'
import { Search, RefreshCw, X, ExternalLink, CheckCircle2, Clock, AlertCircle, BarChart2, Zap, Hash, FileText, Tag as TagIcon } from 'lucide-react'
import { Table, StatusBadge, EmptyState, Skeleton, SyncResultCard } from './index'
import { useFetch } from '../../hooks/useFetch'
import { apiCache } from '../../services/apiCache'
import toast from 'react-hot-toast'

// ─── Detail Drawer ─────────────────────────────────────────────────────────────
function DetailDrawer({ record, onClose }) {
  if (!record) return null

  // Build the richest possible dataset:
  // 1. Start with all DB row fields (includes id, externalId, name, status, etc.)
  // 2. Parse and merge rawJson (contains original CxAlloy response with ALL fields)
  let merged = {}

  // Add all DB fields first — include even null ones so user sees the full schema
  Object.entries(record).forEach(([k, v]) => {
    if (k !== 'rawJson' && k !== 'raw_json') merged[k] = v
  })

  // Overlay rawJson — this has the richest data
  const rawJsonStr = record.rawJson || record.raw_json
  let parsedRaw = null
  if (rawJsonStr) {
    try {
      parsedRaw = JSON.parse(rawJsonStr)
      // Merge all rawJson fields in (overwriting DB nulls with real values)
      Object.entries(parsedRaw).forEach(([k, v]) => {
        if (v !== null && v !== undefined && v !== '') merged[k] = v
      })
    } catch (_) {
      // rawJson wasn't parseable — show it as a string at the bottom
    }
  }

  const SKIP = new Set(['rawJson', 'raw_json', '__typename'])
  const allEntries = Object.entries(merged).filter(([k]) => !SKIP.has(k))

  // Even if merged is empty — show rawJson directly as fallback
  const hasData = allEntries.some(([, v]) => v !== null && v !== undefined && v !== '')

  const ID_KEYS     = new Set(['id','externalId','external_id','projectId','project_id','assetId','asset_id','issueId','issue_id','checklistId','checklist_id'])
  const STATUS_KEYS = new Set(['status','state','priority','severity','type','checklistType','checklist_type','assetType','asset_type'])
  const DATE_KEYS   = new Set(['createdAt','created_at','updatedAt','updated_at','dueDate','due_date','completedDate','completed_date','syncedAt','synced_at'])

  // Include null values too — user should see the full field list
  const idFields     = allEntries.filter(([k]) => ID_KEYS.has(k))
  const statusFields = allEntries.filter(([k]) => STATUS_KEYS.has(k))
  const dateFields   = allEntries.filter(([k]) => DATE_KEYS.has(k))
  const otherFields  = allEntries.filter(([k]) => !ID_KEYS.has(k) && !STATUS_KEYS.has(k) && !DATE_KEYS.has(k))

  const renderValue = (v) => {
    if (v === null || v === undefined || v === '')
      return <span style={{ color: '#475569', fontStyle: 'italic' }}>—</span>
    if (Array.isArray(v)) {
      if (v.length === 0) return <span style={{ color: '#475569', fontStyle: 'italic' }}>empty list</span>
      return (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
          {v.slice(0, 10).map((item, i) => (
            <span key={i} style={{ background: 'rgba(255,255,255,0.07)', border: '1px solid rgba(255,255,255,0.1)', borderRadius: 5, padding: '2px 8px', fontSize: 11, color: '#cbd5e1' }}>
              {typeof item === 'object' && item !== null
                ? (item.name || item.title || item.label || item.id || JSON.stringify(item).slice(0, 50))
                : String(item)}
            </span>
          ))}
          {v.length > 10 && <span style={{ color: '#64748b', fontSize: 11 }}>+{v.length - 10} more</span>}
        </div>
      )
    }
    if (typeof v === 'object') {
      return (
        <pre style={{ margin: 0, fontSize: 11, color: '#94a3b8', whiteSpace: 'pre-wrap', wordBreak: 'break-all', maxHeight: 100, overflowY: 'auto', background: 'rgba(0,0,0,0.2)', borderRadius: 6, padding: '6px 8px' }}>
          {JSON.stringify(v, null, 2)}
        </pre>
      )
    }
    if (typeof v === 'string' && /^\d{4}-\d{2}-\d{2}/.test(v)) {
      try { const d = new Date(v); if (!isNaN(d)) return <span style={{ color: '#94a3b8' }}>{d.toLocaleString()}</span> }
      catch (_) {}
    }
    return <span style={{ wordBreak: 'break-all', color: '#e2e8f0' }}>{String(v)}</span>
  }

  const fieldLabel = (k) => k.replace(/([A-Z])/g, ' $1').replace(/_/g, ' ').trim()

  // Section component — defined outside render, passed as HOF to avoid remount
  const renderSection = (icon, label, fields) => {
    if (!fields.length) return null
    return (
      <div key={label}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 7, margin: '18px 0 10px' }}>
          {React.createElement(icon, { size: 13, style: { color: '#38bdf8', flexShrink: 0 } })}
          <span style={{ fontSize: 10, fontWeight: 700, color: '#38bdf8', textTransform: 'uppercase', letterSpacing: '0.1em', whiteSpace: 'nowrap' }}>{label}</span>
          <div style={{ flex: 1, height: 1, background: 'rgba(56,189,248,0.15)' }} />
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
          {fields.map(([k, v]) => (
            <div key={k} style={{
              background: 'rgba(255,255,255,0.03)',
              border: '1px solid rgba(255,255,255,0.07)',
              borderRadius: 10,
              padding: '10px 13px',
              gridColumn: (typeof v === 'string' && v.length > 55) || (typeof v === 'object' && v !== null) || Array.isArray(v) ? 'span 2' : 'span 1',
            }}>
              <div style={{ fontSize: 10, fontWeight: 600, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.07em', marginBottom: 5 }}>
                {fieldLabel(k)}
              </div>
              <div style={{ fontSize: 12, lineHeight: 1.5, fontFamily: 'ui-monospace, monospace' }}>
                {renderValue(v)}
              </div>
            </div>
          ))}
        </div>
      </div>
    )
  }

  const title    = merged.name || merged.title || merged.externalId || merged.external_id || 'Record Details'
  const subtitle = merged.externalId || merged.external_id

  return ReactDOM.createPortal(
    <div
      onClick={onClose}
      style={{
        position: 'fixed', inset: 0, zIndex: 99999,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        padding: 20,
        backgroundColor: 'rgba(0,0,0,0.87)',
        backdropFilter: 'blur(8px)',
        WebkitBackdropFilter: 'blur(8px)',
      }}
    >
      <div
        onClick={e => e.stopPropagation()}
        style={{
          position: 'relative',
          width: '100%', maxWidth: 720,
          maxHeight: '92vh', overflowY: 'auto',
          background: '#0d1829',
          border: '1px solid rgba(255,255,255,0.1)',
          borderRadius: 20,
          boxShadow: '0 40px 120px rgba(0,0,0,0.9)',
          animation: 'detailSlideUp 0.2s cubic-bezier(0.16,1,0.3,1) both',
        }}
      >
        {/* ── Sticky Header ── */}
        <div style={{
          position: 'sticky', top: 0, zIndex: 10,
          display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between',
          padding: '18px 20px 14px',
          borderBottom: '1px solid rgba(255,255,255,0.08)',
          background: '#0d1829',
          borderRadius: '20px 20px 0 0',
        }}>
          <div style={{ flex: 1, minWidth: 0, paddingRight: 14 }}>
            <div style={{ fontSize: 10, fontWeight: 700, color: '#38bdf8', textTransform: 'uppercase', letterSpacing: '0.12em', marginBottom: 5 }}>
              Record Details
            </div>
            <h3 style={{ margin: 0, fontSize: 15, fontWeight: 700, color: '#f1f5f9', lineHeight: 1.35, wordBreak: 'break-word' }}>
              {title}
            </h3>
            {subtitle && subtitle !== title && (
              <span style={{ marginTop: 5, display: 'inline-block', fontFamily: 'monospace', fontSize: 11, color: '#38bdf8', background: 'rgba(56,189,248,0.1)', padding: '2px 9px', borderRadius: 5, border: '1px solid rgba(56,189,248,0.2)' }}>
                {subtitle}
              </span>
            )}
          </div>

          {/* ── Close button ── */}
          <button
            onClick={onClose}
            title="Close (Esc)"
            style={{
              flexShrink: 0, width: 36, height: 36,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              background: 'rgba(255,255,255,0.07)',
              border: '1px solid rgba(255,255,255,0.15)',
              borderRadius: 10, cursor: 'pointer', color: '#94a3b8',
              transition: 'all 0.15s',
            }}
            onMouseEnter={e => { e.currentTarget.style.background = 'rgba(239,68,68,0.2)'; e.currentTarget.style.borderColor = 'rgba(239,68,68,0.4)'; e.currentTarget.style.color = '#fca5a5' }}
            onMouseLeave={e => { e.currentTarget.style.background = 'rgba(255,255,255,0.07)'; e.currentTarget.style.borderColor = 'rgba(255,255,255,0.15)'; e.currentTarget.style.color = '#94a3b8' }}
          >
            <X size={16} />
          </button>
        </div>

        {/* ── Body ── */}
        <div style={{ padding: '2px 20px 24px' }}>
          {!hasData && !rawJsonStr && (
            <div style={{ textAlign: 'center', padding: '40px 0', color: '#475569' }}>
              <div style={{ fontSize: 30, marginBottom: 10 }}>⚠️</div>
              <div style={{ fontSize: 14, fontWeight: 600, color: '#94a3b8', marginBottom: 6 }}>No Data Available</div>
              <div style={{ fontSize: 12 }}>This record has no populated fields. Try running a Sync first.</div>
            </div>
          )}

          {renderSection(Hash, 'Identifiers', idFields)}
          {renderSection(TagIcon, 'Status & Classification', statusFields)}
          {renderSection(FileText, 'Details', otherFields)}
          {renderSection(Clock, 'Dates', dateFields)}

          {/* Raw JSON fallback — always show if rawJson exists but couldn't be parsed */}
          {rawJsonStr && !parsedRaw && (
            <div style={{ marginTop: 18 }}>
              <div style={{ fontSize: 10, fontWeight: 700, color: '#f59e0b', textTransform: 'uppercase', letterSpacing: '0.1em', marginBottom: 8 }}>
                ⚠ Raw JSON (unparseable)
              </div>
              <pre style={{ fontSize: 11, color: '#94a3b8', background: 'rgba(0,0,0,0.3)', borderRadius: 8, padding: 12, maxHeight: 200, overflowY: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                {rawJsonStr}
              </pre>
            </div>
          )}
        </div>
      </div>

      <style>{`
        @keyframes detailSlideUp {
          from { opacity: 0; transform: translateY(20px) scale(0.97); }
          to   { opacity: 1; transform: translateY(0) scale(1); }
        }
      `}</style>
    </div>,
    document.body
  )
}

const PAGE_SIZE = 20

// ─── GenericListPage ────────────────────────────────────────────────────────────
export default function GenericListPage({
  fetchFn,
  syncFn,
  columns,
  emptyIcon,
  emptyTitle,
  emptyDesc,
  activeProjectId,
  searchKeys = ['name', 'title', 'externalId'],
  cacheKey,
}) {
  const [search, setSearch]         = useState('')
  const [syncing, setSyncing]       = useState(false)
  const [syncResult, setSyncResult] = useState(null)
  const [detail, setDetail]         = useState(null)
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE)

  const resolvedKey = cacheKey || (activeProjectId ? `entity-list-${activeProjectId}` : null)

  const { data, loading, revalidating, refetch } = useFetch(
    resolvedKey,
    () => fetchFn(activeProjectId),
    [activeProjectId],
    { swr: true }
  )

  const items = data || []

  const handleSync = useCallback(async () => {
    if (!syncFn) return
    setSyncing(true)
    setSyncResult(null)
    try {
      const res = await syncFn(activeProjectId)
      setSyncResult(res.data.data)
      toast.success('Synced!')
      if (resolvedKey) apiCache.invalidateKey(resolvedKey)
      refetch()
    } catch {
      toast.error('Sync failed')
    } finally {
      setSyncing(false)
    }
  }, [syncFn, activeProjectId, resolvedKey, refetch])

  // Reset to first page whenever search changes
  React.useEffect(() => { setVisibleCount(PAGE_SIZE) }, [search, activeProjectId])

  const filtered = useMemo(() =>
    items.filter(item =>
      !search || searchKeys.some(k =>
        String(item[k] || '').toLowerCase().includes(search.toLowerCase())
      )
    ), [items, search, searchKeys])

  const visibleItems = useMemo(() => filtered.slice(0, visibleCount), [filtered, visibleCount])
  const hasMore = visibleCount < filtered.length

  const stats = useMemo(() => {
    if (!items.length) return null
    const total    = items.length
    const finished = items.filter(i =>
      ['finished','complete','completed','done','closed','resolved','signed_off']
        .includes((i.status || '').toLowerCase().replace(/ /g,'_').replace(/-/g,'_'))
    ).length
    const inProg   = items.filter(i =>
      ['in_progress','inprogress','started','active','open']
        .includes((i.status || '').toLowerCase().replace(/ /g,'_').replace(/-/g,'_'))
    ).length
    const pct = total > 0 ? Math.round((finished / total) * 100) : 0
    return { total, finished, inProg, pct }
  }, [items])

  // Escape key closes detail
  React.useEffect(() => {
    if (!detail) return
    const handler = (e) => { if (e.key === 'Escape') setDetail(null) }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [detail])

  return (
    <div className="space-y-5 animate-fade-in">

      {/* Stats bar */}
      {!loading && stats && stats.total > 0 && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 12 }}>
          {[
            { label: 'Total',       value: stats.total,     color: '#38bdf8', bg: 'rgba(14,165,233,0.08)',  border: 'rgba(14,165,233,0.2)',  Icon: BarChart2 },
            { label: 'Finished',    value: stats.finished,  color: '#4ade80', bg: 'rgba(34,197,94,0.08)',   border: 'rgba(34,197,94,0.2)',   Icon: CheckCircle2 },
            { label: 'In Progress', value: stats.inProg,    color: '#fbbf24', bg: 'rgba(234,179,8,0.08)',   border: 'rgba(234,179,8,0.2)',   Icon: Clock },
            {
              label: 'Completion', value: `${stats.pct}%`,
              color: stats.pct >= 80 ? '#4ade80' : stats.pct >= 50 ? '#fbbf24' : '#f87171',
              bg: 'rgba(255,255,255,0.03)', border: 'rgba(255,255,255,0.08)', Icon: AlertCircle
            },
          ].map(({ label, value, color, bg, border, Icon }) => (
            <div key={label} style={{ background: bg, border: `1px solid ${border}`, borderRadius: 12, padding: '14px 16px', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div>
                <div style={{ fontSize: 10, fontWeight: 600, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 4 }}>{label}</div>
                <div style={{ fontSize: 26, fontWeight: 800, color, lineHeight: 1 }}>{value}</div>
              </div>
              <div style={{ width: 36, height: 36, borderRadius: 10, background: `${color}18`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                <Icon size={18} style={{ color }} />
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Progress bar */}
      {!loading && stats && stats.total > 0 && (
        <div style={{ height: 5, borderRadius: 999, background: 'rgba(255,255,255,0.06)', overflow: 'hidden' }}>
          <div style={{
            height: '100%', width: `${stats.pct}%`, borderRadius: 999,
            background: stats.pct >= 80 ? 'linear-gradient(90deg,#22c55e,#4ade80)'
              : stats.pct >= 50 ? 'linear-gradient(90deg,#f59e0b,#fbbf24)'
              : 'linear-gradient(90deg,#ef4444,#f87171)',
            transition: 'width 0.6s ease',
          }} />
        </div>
      )}

      {/* Toolbar */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative flex-1 min-w-48">
          <Search size={14} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-dark-500" />
          <input
            type="text"
            value={search}
            onChange={e => setSearch(e.target.value)}
            className="input-field pl-9"
            placeholder="Search..."
          />
        </div>
        {syncFn && (
          <button onClick={handleSync} disabled={syncing} className="btn-secondary">
            <RefreshCw size={14} className={syncing ? 'animate-spin' : ''} />
            {syncing ? 'Syncing...' : 'Sync'}
          </button>
        )}
        <div className="glass-card-light px-4 py-2 text-xs text-dark-400 flex items-center gap-2">
          <span className="font-700 text-white">{filtered.length}</span> of {items.length} records
          {revalidating && <Zap size={11} className="text-sky-400 animate-pulse" title="Refreshing in background" />}
        </div>
      </div>

      {/* Sync result */}
      {syncResult && (
        <div className="relative">
          <button onClick={() => setSyncResult(null)} className="absolute top-2 right-2 text-dark-400 hover:text-white z-10">
            <X size={14} />
          </button>
          <SyncResultCard result={syncResult} />
        </div>
      )}

      {/* Table */}
      {loading ? (
        <div className="space-y-2">{[...Array(6)].map((_, i) => <Skeleton key={i} className="h-12" />)}</div>
      ) : filtered.length === 0 ? (
        <EmptyState icon={emptyIcon} title={emptyTitle} description={emptyDesc} />
      ) : (
        <>
          <Table
            columns={[
              ...columns,
              {
                key: '_detail', label: '', render: (_, row) => (
                  <button
                    onClick={e => { e.stopPropagation(); window.scrollTo({ top: 0, behavior: 'smooth' }); setDetail(row) }}
                    className="p-1.5 rounded-lg text-dark-400 hover:text-sky-400 hover:bg-sky-400/10 transition-all"
                    title="View details"
                  >
                    <ExternalLink size={13} />
                  </button>
                )
              }
            ]}
            data={visibleItems}
            onRowClick={(row) => { window.scrollTo({ top: 0, behavior: 'smooth' }); setDetail(row) }}
          />

          {/* Load More */}
          {hasMore && (
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, padding: '8px 0 4px' }}>
              <div style={{ fontSize: 12, color: '#475569' }}>
                Showing <span style={{ color: '#e2e8f0', fontWeight: 700 }}>{visibleItems.length}</span> of <span style={{ color: '#e2e8f0', fontWeight: 700 }}>{filtered.length}</span> records
              </div>
              <button
                onClick={() => setVisibleCount(c => c + PAGE_SIZE)}
                style={{
                  display: 'flex', alignItems: 'center', gap: 8,
                  padding: '10px 28px',
                  background: 'rgba(56,189,248,0.08)',
                  border: '1px solid rgba(56,189,248,0.25)',
                  borderRadius: 10, cursor: 'pointer',
                  color: '#38bdf8', fontSize: 13, fontWeight: 600,
                  transition: 'all 0.15s',
                }}
                onMouseEnter={e => { e.currentTarget.style.background = 'rgba(56,189,248,0.16)'; e.currentTarget.style.borderColor = 'rgba(56,189,248,0.5)' }}
                onMouseLeave={e => { e.currentTarget.style.background = 'rgba(56,189,248,0.08)'; e.currentTarget.style.borderColor = 'rgba(56,189,248,0.25)' }}
              >
                <RefreshCw size={13} />
                Load More ({Math.min(PAGE_SIZE, filtered.length - visibleCount)} more)
              </button>
            </div>
          )}
        </>
      )}

      {/* Detail drawer — portal-based, always has data */}
      <DetailDrawer record={detail} onClose={() => setDetail(null)} />
    </div>
  )
}
