import React, { useState, useEffect, useMemo, useCallback } from 'react'
import ReactDOM from 'react-dom'
import { Search, RefreshCw, X, ExternalLink, CheckCircle2, Clock, AlertCircle, BarChart2, ChevronDown, Download } from 'lucide-react'
import { Table, StatusBadge, EmptyState, Skeleton, SyncResultCard } from './index'
import toast from 'react-hot-toast'

const PAGE_SIZE = 20

// ─── CxAlloy URL builder ────────────────────────────────────────────────────────
// Maps our entity types to CxAlloy's URL path segments
const ENTITY_PATH = {
  checklists:  'checklists',
  tasks:       'tasks',
  assets:      'equipment',
  issues:      'issues',
  persons:     'people',
  companies:   'companies',
  roles:       'roles',
}

function buildCxAlloyUrl(entityType, projectId, itemExternalId) {
  if (!entityType || !projectId || !itemExternalId) return null
  const path = ENTITY_PATH[entityType]
  if (!path) return null
  return `https://tq.cxalloy.com/project/${projectId}/${path}/${itemExternalId}`
}

// ─── Portal Detail Modal ────────────────────────────────────────────────────────
function DetailModal({ record, onClose, entityType, projectId }) {
  useEffect(() => {
    if (!record) return
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => { document.body.style.overflow = prev }
  }, [record])

  useEffect(() => {
    if (!record) return
    const handler = (e) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [record, onClose])

  if (!record) return null

  let merged = {}
  Object.entries(record).forEach(([k, v]) => {
    if (k !== 'rawJson' && k !== 'raw_json') merged[k] = v
  })
  const rawJsonStr = record.rawJson || record.raw_json
  if (rawJsonStr) {
    try {
      const parsed = JSON.parse(rawJsonStr)
      Object.entries(parsed).forEach(([k, v]) => {
        if (v !== null && v !== undefined && v !== '') merged[k] = v
      })
    } catch (_) {}
  }

  const SKIP = new Set(['rawJson', 'raw_json', '__typename'])
  const entries = Object.entries(merged).filter(([k]) => !SKIP.has(k))

  const renderValue = (v) => {
    if (v === null || v === undefined || v === '')
      return <span style={{ color: '#64748b', fontStyle: 'italic' }}>—</span>
    if (Array.isArray(v)) {
      if (!v.length) return <span style={{ color: '#64748b', fontStyle: 'italic' }}>empty</span>
      return (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
          {v.slice(0, 8).map((item, i) => (
            <span key={i} style={{ background: 'rgba(56,189,248,0.1)', border: '1px solid rgba(56,189,248,0.2)', borderRadius: 5, padding: '2px 8px', fontSize: 11, color: '#7dd3fc' }}>
              {typeof item === 'object' ? (item.name || item.title || item.id || JSON.stringify(item).slice(0, 40)) : String(item)}
            </span>
          ))}
          {v.length > 8 && <span style={{ color: '#475569', fontSize: 11 }}>+{v.length - 8} more</span>}
        </div>
      )
    }
    if (typeof v === 'object') {
      return <pre style={{ margin: 0, fontSize: 11, color: '#94a3b8', whiteSpace: 'pre-wrap', wordBreak: 'break-all', maxHeight: 80, overflowY: 'auto', background: 'rgba(0,0,0,0.2)', borderRadius: 6, padding: '4px 8px' }}>{JSON.stringify(v, null, 2)}</pre>
    }
    if (typeof v === 'string' && /^\d{4}-\d{2}-\d{2}/.test(v)) {
      try { const d = new Date(v); if (!isNaN(d)) return <span style={{ color: '#94a3b8' }}>{d.toLocaleString()}</span> } catch (_) {}
    }
    return <span style={{ wordBreak: 'break-all', color: '#e2e8f0' }}>{String(v)}</span>
  }

  const toLabel = (k) => k.replace(/([A-Z])/g, ' $1').replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase()).trim()
  const title    = merged.name || merged.title || merged.externalId || merged.external_id || 'Record Details'
  const subtitle = merged.externalId || merged.external_id
  const cxUrl    = buildCxAlloyUrl(entityType, projectId, merged.externalId || merged.external_id)

  return ReactDOM.createPortal(
    <div
      onClick={onClose}
      style={{
        position: 'fixed', inset: 0, zIndex: 999999,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        padding: '20px 16px',
        background: 'rgba(0,0,0,0.85)',
        backdropFilter: 'blur(6px)',
        WebkitBackdropFilter: 'blur(6px)',
      }}
    >
      <div
        onClick={e => e.stopPropagation()}
        style={{
          position: 'relative',
          width: '100%', maxWidth: 680,
          maxHeight: '88vh', overflowY: 'auto',
          background: '#0d1829',
          border: '1px solid rgba(255,255,255,0.12)',
          borderRadius: 18,
          boxShadow: '0 32px 100px rgba(0,0,0,0.9)',
          animation: 'glpModalIn 0.2s cubic-bezier(0.16,1,0.3,1) both',
        }}
      >
        {/* Sticky header */}
        <div style={{
          position: 'sticky', top: 0, zIndex: 10,
          padding: '16px 20px 12px',
          borderBottom: '1px solid var(--border)',
          background: '#0d1829',
          borderRadius: '18px 18px 0 0',
        }}>
          <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12 }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 10, fontWeight: 700, color: '#38bdf8', textTransform: 'uppercase', letterSpacing: '0.12em', marginBottom: 4 }}>
                Record Details
              </div>
              <div style={{ fontSize: 15, fontWeight: 700, color: '#f1f5f9', lineHeight: 1.3, wordBreak: 'break-word' }}>
                {title}
              </div>
              {subtitle && subtitle !== title && (
                <span style={{ marginTop: 5, display: 'inline-block', fontFamily: 'monospace', fontSize: 11, color: '#38bdf8', background: 'rgba(56,189,248,0.1)', padding: '2px 8px', borderRadius: 5, border: '1px solid rgba(56,189,248,0.2)' }}>
                  {subtitle}
                </span>
              )}
            </div>

            {/* Right side: Open in CxAlloy + Close */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
              {cxUrl && (
                <a
                  href={cxUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  onClick={e => e.stopPropagation()}
                  title="Open in CxAlloy"
                  style={{
                    display: 'inline-flex', alignItems: 'center', gap: 6,
                    padding: '6px 14px',
                    background: 'rgba(56,189,248,0.12)',
                    border: '1px solid rgba(56,189,248,0.35)',
                    borderRadius: 9, cursor: 'pointer',
                    color: '#38bdf8', fontSize: 12, fontWeight: 600,
                    textDecoration: 'none',
                    transition: 'all 0.15s',
                    whiteSpace: 'nowrap',
                  }}
                  onMouseEnter={e => { e.currentTarget.style.background = 'rgba(56,189,248,0.22)'; e.currentTarget.style.borderColor = 'rgba(56,189,248,0.7)' }}
                  onMouseLeave={e => { e.currentTarget.style.background = 'rgba(56,189,248,0.12)'; e.currentTarget.style.borderColor = 'rgba(56,189,248,0.35)' }}
                >
                  <ExternalLink size={12} />
                  Open in CxAlloy
                </a>
              )}
              <button
                onClick={onClose}
                style={{
                  width: 34, height: 34, flexShrink: 0,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  background: 'var(--border)', border: '1px solid rgba(255,255,255,0.15)',
                  borderRadius: 9, cursor: 'pointer', color: '#94a3b8', transition: 'all 0.15s',
                }}
                onMouseEnter={e => { e.currentTarget.style.background = 'rgba(239,68,68,0.2)'; e.currentTarget.style.color = '#fca5a5'; e.currentTarget.style.borderColor = 'rgba(239,68,68,0.4)' }}
                onMouseLeave={e => { e.currentTarget.style.background = 'var(--border)'; e.currentTarget.style.color = '#94a3b8'; e.currentTarget.style.borderColor = 'rgba(255,255,255,0.15)' }}
              >
                <X size={15} />
              </button>
            </div>
          </div>
        </div>

        {/* Fields grid */}
        <div style={{ padding: '16px 20px 24px' }}>
          {entries.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '32px 0', color: '#475569' }}>
              <div style={{ fontSize: 28, marginBottom: 8 }}>⚠️</div>
              <div style={{ fontSize: 13, color: '#94a3b8' }}>No data — try syncing first</div>
            </div>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
              {entries.map(([k, v]) => (
                <div key={k} style={{
                  background: 'var(--border-subtle)',
                  border: '1px solid var(--border)',
                  borderRadius: 10, padding: '10px 13px',
                  gridColumn: (typeof v === 'string' && v.length > 50) || (typeof v === 'object' && v !== null) || Array.isArray(v) ? 'span 2' : 'span 1',
                }}>
                  <div style={{ fontSize: 10, fontWeight: 600, color: '#475569', textTransform: 'uppercase', letterSpacing: '0.07em', marginBottom: 5 }}>
                    {toLabel(k)}
                  </div>
                  <div style={{ fontSize: 12, lineHeight: 1.5, fontFamily: 'ui-monospace, monospace' }}>
                    {renderValue(v)}
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* CxAlloy direct link at bottom of modal too */}
          {cxUrl && (
            <div style={{ marginTop: 20, paddingTop: 16, borderTop: '1px solid var(--border)', textAlign: 'center' }}>
              <a
                href={cxUrl}
                target="_blank"
                rel="noopener noreferrer"
                style={{
                  display: 'inline-flex', alignItems: 'center', gap: 8,
                  padding: '10px 24px',
                  background: 'rgba(56,189,248,0.08)',
                  border: '1px solid rgba(56,189,248,0.25)',
                  borderRadius: 10, cursor: 'pointer',
                  color: '#38bdf8', fontSize: 13, fontWeight: 600,
                  textDecoration: 'none',
                  transition: 'all 0.15s',
                }}
                onMouseEnter={e => { e.currentTarget.style.background = 'rgba(56,189,248,0.18)'; e.currentTarget.style.borderColor = 'rgba(56,189,248,0.6)' }}
                onMouseLeave={e => { e.currentTarget.style.background = 'rgba(56,189,248,0.08)'; e.currentTarget.style.borderColor = 'rgba(56,189,248,0.25)' }}
              >
                <ExternalLink size={14} />
                View full record in CxAlloy →
              </a>
            </div>
          )}
        </div>
      </div>

      <style>{`
        @keyframes glpModalIn {
          from { opacity: 0; transform: scale(0.95) translateY(12px); }
          to   { opacity: 1; transform: scale(1) translateY(0); }
        }
      `}</style>
    </div>,
    document.body
  )
}

// ─── GenericListPage ────────────────────────────────────────────────────────────
export default function GenericListPage({
  fetchFn,
  syncFn,
  columns,
  emptyIcon,
  emptyTitle,
  emptyDesc,
  activeProjectId,
  entityType,          // 'checklists' | 'tasks' | 'assets' | 'issues' | 'persons' | 'companies' | 'roles'
  searchKeys = ['name', 'title', 'externalId'],
  showStats = true,
  filterConfigs = [],
  topContent = null,
}) {
  const [items, setItems]               = useState([])
  const [loading, setLoading]           = useState(true)
  const [search, setSearch]             = useState('')
  const [filters, setFilters]           = useState({})
  const [syncing, setSyncing]           = useState(false)
  const [syncResult, setSyncResult]     = useState(null)
  const [detail, setDetail]             = useState(null)
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await fetchFn(activeProjectId)
      setItems(res.data.data || [])
    } catch {
      toast.error('Failed to load data')
    } finally {
      setLoading(false)
    }
  }, [fetchFn, activeProjectId])

  useEffect(() => { load() }, [load])
  useEffect(() => { setVisibleCount(PAGE_SIZE) }, [search, activeProjectId, filters])
  useEffect(() => { setFilters({}) }, [activeProjectId, entityType])

  const handleSync = async () => {
    if (!syncFn) return
    setSyncing(true)
    setSyncResult(null)
    try {
      const res = await syncFn(activeProjectId)
      setSyncResult(res.data.data)
      toast.success('Synced!')
      load()
    } catch {
      toast.error('Sync failed')
    } finally {
      setSyncing(false)
    }
  }

  const stats = useMemo(() => {
    if (!items.length) return null
    const total    = items.length
    const finished = items.filter(i => ['finished','complete','completed','done','closed','resolved','signed_off']
      .includes((i.status || '').toLowerCase().replace(/ /g,'_').replace(/-/g,'_'))).length
    const inProg   = items.filter(i => ['in_progress','inprogress','started','active','open']
      .includes((i.status || '').toLowerCase().replace(/ /g,'_').replace(/-/g,'_'))).length
    const pct = total > 0 ? Math.round((finished / total) * 1000) / 10 : 0
    return { total, finished, inProg, pct }
  }, [items])

  const filterOptions = useMemo(() =>
    filterConfigs.map(config => {
      const values = config.options?.length
        ? config.options
        : Array.from(new Set(items
            .map(item => String(config.getValue ? config.getValue(item) : item[config.key] || '').trim())
            .filter(Boolean)))
            .sort((a, b) => a.localeCompare(b))

      return { ...config, values }
    }),
  [filterConfigs, items])

  const filtered = useMemo(() =>
    items.filter(item =>
      (!search || searchKeys.some(k => String(item[k] || '').toLowerCase().includes(search.toLowerCase())))
      && filterConfigs.every(config => {
        const selected = filters[config.key]
        if (!selected || selected === 'all') return true
        const rawValue = config.getValue ? config.getValue(item) : item[config.key]
        return String(rawValue || '').toLowerCase() === String(selected).toLowerCase()
      })
    ), [items, search, searchKeys, filterConfigs, filters])

  const visibleItems = useMemo(() => filtered.slice(0, visibleCount), [filtered, visibleCount])
  const hasMore   = visibleCount < filtered.length
  const remaining = Math.min(PAGE_SIZE, filtered.length - visibleCount)

  const csvValue = (value) => {
    if (value == null) return ''
    if (Array.isArray(value)) return value.join(', ')
    if (typeof value === 'object') return JSON.stringify(value)
    return String(value)
  }

  const csvEscape = (value) => `"${csvValue(value).replace(/"/g, '""')}"`

  const handleExportCsv = () => {
    const exportColumns = columns.filter(column => column.key && !column.key.startsWith('_'))
    const header = exportColumns.map(column => csvEscape(column.label)).join(',')
    const rows = filtered.map(row =>
      exportColumns.map(column => {
        const value = column.exportValue ? column.exportValue(row[column.key], row) : row[column.key]
        return csvEscape(value)
      }).join(',')
    )

    const blob = new Blob([[header, ...rows].join('\r\n')], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${entityType || 'records'}-${activeProjectId || 'workspace'}.csv`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
  }

  // ── Table columns: user columns + detail icon only ──────────────────────────
  // CxAlloy links are available inside the detail modal (not as a separate column)
  const detailColumn = {
    key: '_detail',
    label: '',
    render: (_, row) => (
      <button
        onClick={e => { e.stopPropagation(); setDetail(row) }}
        className="p-1.5 rounded-lg text-dark-400 hover:text-sky-400 hover:bg-sky-400/10 transition-all"
        title="View details"
      >
        <ExternalLink size={13} />
      </button>
    )
  }

  const renderedTopContent = typeof topContent === 'function'
    ? topContent({
        items,
        filteredItems: filtered,
        loading,
        activeProjectId,
      })
    : topContent

  return (
    <div className="space-y-5 animate-fade-in">
      {renderedTopContent}

      {/* Stats bar */}
      {showStats && !loading && stats && stats.total > 0 && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12 }}>
          {[
            { label: 'Total',       value: stats.total,    color: '#38bdf8', bg: 'rgba(14,165,233,0.08)',  border: 'rgba(14,165,233,0.2)',  Icon: BarChart2 },
            { label: 'Finished',    value: stats.finished, color: '#4ade80', bg: 'rgba(34,197,94,0.08)',   border: 'rgba(34,197,94,0.2)',   Icon: CheckCircle2 },
            { label: 'In Progress', value: stats.inProg,   color: '#fbbf24', bg: 'rgba(234,179,8,0.08)',   border: 'rgba(234,179,8,0.2)',   Icon: Clock },
            { label: 'Completion',  value: `${stats.pct % 1 === 0 ? stats.pct : stats.pct.toFixed(1)}%`,
              color: stats.pct >= 80 ? '#4ade80' : stats.pct >= 50 ? '#fbbf24' : '#f87171',
              bg: 'var(--border-subtle)', border: 'var(--border)', Icon: AlertCircle },
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
      {showStats && !loading && stats && stats.total > 0 && (
        <div style={{ height: 5, borderRadius: 999, background: 'var(--border)', overflow: 'hidden' }}>
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
          <input type="text" value={search} onChange={e => setSearch(e.target.value)}
            className="input-field pl-9" placeholder="Search..." />
        </div>
        {filterOptions.map(config => (
          <select
            key={config.key}
            value={filters[config.key] || 'all'}
            onChange={e => setFilters(prev => ({ ...prev, [config.key]: e.target.value }))}
            className="input-field w-auto"
          >
            <option value="all">{config.label}</option>
            {config.values.map(option => (
              <option key={option} value={option}>{option}</option>
            ))}
          </select>
        ))}
        {syncFn && (
          <button onClick={handleSync} disabled={syncing} className="btn-secondary">
            <RefreshCw size={14} className={syncing ? 'animate-spin' : ''} />
            {syncing ? 'Syncing...' : 'Sync'}
          </button>
        )}
        <button onClick={handleExportCsv} className="btn-secondary">
          <Download size={14} />
          Export CSV
        </button>
        <div className="glass-card-light px-4 py-2 text-xs text-dark-400">
          <span className="font-700 text-white">{filtered.length}</span> records
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
            columns={[...columns, detailColumn]}
            data={visibleItems}
            onRowClick={setDetail}
          />

          {/* Row count + Load More */}
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10, padding: '4px 0 8px' }}>
            <div style={{ fontSize: 12, color: '#475569' }}>
              Showing{' '}
              <span style={{ color: '#cbd5e1', fontWeight: 700 }}>{visibleItems.length}</span>
              {' '}of{' '}
              <span style={{ color: '#cbd5e1', fontWeight: 700 }}>{filtered.length}</span>
              {' '}records
            </div>
            {hasMore && (
              <button
                onClick={() => setVisibleCount(c => c + PAGE_SIZE)}
                style={{
                  display: 'inline-flex', alignItems: 'center', gap: 8,
                  padding: '10px 32px',
                  background: 'rgba(56,189,248,0.08)',
                  border: '1px solid rgba(56,189,248,0.3)',
                  borderRadius: 10, cursor: 'pointer',
                  color: '#38bdf8', fontSize: 13, fontWeight: 600,
                  transition: 'all 0.15s',
                }}
                onMouseEnter={e => { e.currentTarget.style.background = 'rgba(56,189,248,0.18)'; e.currentTarget.style.borderColor = 'rgba(56,189,248,0.6)' }}
                onMouseLeave={e => { e.currentTarget.style.background = 'rgba(56,189,248,0.08)'; e.currentTarget.style.borderColor = 'rgba(56,189,248,0.3)' }}
              >
                <ChevronDown size={15} />
                Load {remaining} more
              </button>
            )}
          </div>
        </>
      )}

      {/* Detail modal — portal to document.body */}
      <DetailModal
        record={detail}
        onClose={() => setDetail(null)}
        entityType={entityType}
        projectId={activeProjectId}
      />
    </div>
  )
}
