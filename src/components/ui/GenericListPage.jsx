import React, { useCallback, useEffect, useMemo, useState } from 'react'
import ReactDOM from 'react-dom'
import { useLocation } from 'react-router-dom'
import {
  AlertCircle,
  BarChart2,
  CheckCircle2,
  ChevronDown,
  Clock,
  Download,
  ExternalLink,
  RefreshCw,
  Search,
  X,
} from 'lucide-react'
import toast from 'react-hot-toast'
import { EmptyState, Skeleton, SyncResultCard, Table } from './index'
import { isChecklistDone } from '../../utils/checklistStatusUtils'
import { emitSyncRefresh, useSyncRefreshSignal } from '../../hooks/useSyncRefreshSignal'

const PAGE_SIZE = 20

const ENTITY_PATH = {
  checklists: 'checklists',
  tasks: 'tasks',
  assets: 'equipment',
  issues: 'issues',
  persons: 'people',
  companies: 'companies',
  roles: 'roles',
  equipment: 'equipment',
}

function buildCxAlloyUrl(entityType, projectId, itemExternalId) {
  if (!entityType || !projectId || !itemExternalId) return null
  const path = ENTITY_PATH[entityType]
  if (!path) return null
  return `https://tq.cxalloy.com/project/${projectId}/${path}/${itemExternalId}`
}

function annotateRecordWithProject(record, project) {
  return {
    ...record,
    __sourceProjectId: project?.id ?? null,
    __sourceProjectName: project?.name || '',
    __sourceProjectExternalId: project?.externalId || '',
  }
}

function getRecordProjectId(record, fallbackProjectId) {
  return record?.projectId
    || record?.project_id
    || record?.__sourceProjectExternalId
    || fallbackProjectId
    || null
}

function csvValue(value) {
  if (value == null) return ''
  if (Array.isArray(value)) return value.join(', ')
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function csvEscape(value) {
  return `"${csvValue(value).replace(/"/g, '""')}"`
}

function toLabel(key) {
  return key.replace(/([A-Z])/g, ' $1').replace(/_/g, ' ').replace(/\b\w/g, (char) => char.toUpperCase()).trim()
}

function renderDetailValue(value) {
  if (value === null || value === undefined || value === '') {
    return <span style={{ color: '#64748b', fontStyle: 'italic' }}>-</span>
  }

  if (Array.isArray(value)) {
    if (!value.length) return <span style={{ color: '#64748b', fontStyle: 'italic' }}>empty</span>
    return (
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
        {value.slice(0, 8).map((item, index) => (
          <span
            key={`${index}-${String(item)}`}
            style={{
              background: 'rgba(56,189,248,0.1)',
              border: '1px solid rgba(56,189,248,0.2)',
              borderRadius: 5,
              padding: '2px 8px',
              fontSize: 11,
              color: '#7dd3fc',
            }}
          >
            {typeof item === 'object' ? (item.name || item.title || item.id || JSON.stringify(item).slice(0, 40)) : String(item)}
          </span>
        ))}
        {value.length > 8 ? <span style={{ color: '#475569', fontSize: 11 }}>+{value.length - 8} more</span> : null}
      </div>
    )
  }

  if (typeof value === 'object') {
    return (
      <pre
        style={{
          margin: 0,
          fontSize: 11,
          color: '#94a3b8',
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-all',
          maxHeight: 120,
          overflowY: 'auto',
          background: 'rgba(0,0,0,0.2)',
          borderRadius: 6,
          padding: '4px 8px',
        }}
      >
        {JSON.stringify(value, null, 2)}
      </pre>
    )
  }

  if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}/.test(value)) {
    try {
      const parsed = new Date(value)
      if (!Number.isNaN(parsed.getTime())) {
        return <span style={{ color: '#94a3b8' }}>{parsed.toLocaleString()}</span>
      }
    } catch {
      // fall through
    }
  }

  return <span style={{ wordBreak: 'break-all', color: '#e2e8f0' }}>{String(value)}</span>
}

function DetailModal({ entityType, projectId, record, onClose }) {
  useEffect(() => {
    if (!record) return undefined
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = previousOverflow
    }
  }, [record])

  useEffect(() => {
    if (!record) return undefined
    const handleKeyDown = (event) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [onClose, record])

  if (!record) return null

  const merged = {}
  Object.entries(record).forEach(([key, value]) => {
    if (key !== 'rawJson' && key !== 'raw_json') merged[key] = value
  })

  const rawJson = record.rawJson || record.raw_json
  if (rawJson) {
    try {
      const parsed = JSON.parse(rawJson)
      Object.entries(parsed).forEach(([key, value]) => {
        if (value !== null && value !== undefined && value !== '') {
          merged[key] = value
        }
      })
    } catch {
      // ignore invalid raw json payloads
    }
  }

  const hiddenKeys = new Set(['rawJson', 'raw_json', '__typename'])
  const entries = Object.entries(merged).filter(([key]) => !hiddenKeys.has(key))
  const title = merged.name || merged.title || merged.externalId || merged.external_id || 'Record Details'
  const subtitle = merged.externalId || merged.external_id
  const cxUrl = buildCxAlloyUrl(entityType, projectId, merged.externalId || merged.external_id)

  return ReactDOM.createPortal(
    <div
      onClick={onClose}
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 999999,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '20px 16px',
        background: 'rgba(0,0,0,0.85)',
        backdropFilter: 'blur(6px)',
      }}
    >
      <div
        onClick={(event) => event.stopPropagation()}
        style={{
          position: 'relative',
          width: '100%',
          maxWidth: 680,
          maxHeight: '88vh',
          overflowY: 'auto',
          background: '#0d1829',
          border: '1px solid rgba(255,255,255,0.12)',
          borderRadius: 18,
          boxShadow: '0 32px 100px rgba(0,0,0,0.9)',
        }}
      >
        <div
          style={{
            position: 'sticky',
            top: 0,
            zIndex: 10,
            padding: '16px 20px 12px',
            borderBottom: '1px solid var(--border)',
            background: '#0d1829',
            borderRadius: '18px 18px 0 0',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12 }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 10, fontWeight: 700, color: '#38bdf8', textTransform: 'uppercase', letterSpacing: '0.12em', marginBottom: 4 }}>
                Record Details
              </div>
              <div style={{ fontSize: 15, fontWeight: 700, color: '#f1f5f9', lineHeight: 1.3, wordBreak: 'break-word' }}>
                {title}
              </div>
              {subtitle && subtitle !== title ? (
                <span
                  style={{
                    marginTop: 5,
                    display: 'inline-block',
                    fontFamily: 'monospace',
                    fontSize: 11,
                    color: '#38bdf8',
                    background: 'rgba(56,189,248,0.1)',
                    padding: '2px 8px',
                    borderRadius: 5,
                    border: '1px solid rgba(56,189,248,0.2)',
                  }}
                >
                  {subtitle}
                </span>
              ) : null}
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
              {cxUrl ? (
                <a
                  href={cxUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  onClick={(event) => event.stopPropagation()}
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 6,
                    padding: '6px 14px',
                    background: 'rgba(56,189,248,0.12)',
                    border: '1px solid rgba(56,189,248,0.35)',
                    borderRadius: 9,
                    color: '#38bdf8',
                    fontSize: 12,
                    fontWeight: 600,
                    textDecoration: 'none',
                    whiteSpace: 'nowrap',
                  }}
                >
                  <ExternalLink size={12} />
                  Open in CxAlloy
                </a>
              ) : null}
              <button
                onClick={onClose}
                style={{
                  width: 34,
                  height: 34,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  background: 'var(--border)',
                  border: '1px solid rgba(255,255,255,0.15)',
                  borderRadius: 9,
                  cursor: 'pointer',
                  color: '#94a3b8',
                }}
              >
                <X size={15} />
              </button>
            </div>
          </div>
        </div>

        <div style={{ padding: '16px 20px 24px' }}>
          {entries.length === 0 ? (
            <div style={{ textAlign: 'center', padding: '32px 0', color: '#475569' }}>
              <div style={{ fontSize: 13, color: '#94a3b8' }}>No data - try syncing first</div>
            </div>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
              {entries.map(([key, value]) => (
                <div
                  key={key}
                  style={{
                    background: 'var(--border-subtle)',
                    border: '1px solid var(--border)',
                    borderRadius: 10,
                    padding: '10px 13px',
                    gridColumn: (typeof value === 'string' && value.length > 50) || (typeof value === 'object' && value !== null) || Array.isArray(value) ? 'span 2' : 'span 1',
                  }}
                >
                  <div style={{ fontSize: 10, fontWeight: 600, color: '#475569', textTransform: 'uppercase', letterSpacing: '0.07em', marginBottom: 5 }}>
                    {toLabel(key)}
                  </div>
                  <div style={{ fontSize: 12, lineHeight: 1.5, fontFamily: 'ui-monospace, monospace' }}>
                    {renderDetailValue(value)}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>,
    document.body
  )
}

export default function GenericListPage({
  fetchFn,
  syncFn,
  columns,
  emptyIcon,
  emptyTitle,
  emptyDesc,
  primaryProjectId,
  primaryProjectName,
  targetProjects = [],
  entityType,
  searchKeys = ['name', 'title', 'externalId'],
  showStats = true,
  filterConfigs = [],
  topContent = null,
}) {
  const location = useLocation()
  const scopeProjects = targetProjects.length
    ? targetProjects
    : (primaryProjectId ? [{ externalId: primaryProjectId, name: primaryProjectName || '' }] : [])
  const scopeProjectIds = scopeProjects.map((project) => project?.externalId || project?.id).filter(Boolean)
  const scopeKey = scopeProjectIds.join(',')
  const isMultiProject = scopeProjects.length > 1
  const refreshSignal = useSyncRefreshSignal(scopeProjectIds)
  const [items, setItems] = useState([])
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState('')
  const [filters, setFilters] = useState({})
  const [syncing, setSyncing] = useState(false)
  const [syncResult, setSyncResult] = useState(null)
  const [detail, setDetail] = useState(null)
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE)

  const load = useCallback(async () => {
    if (!scopeProjects.length && !primaryProjectId) {
      setItems([])
      setLoading(false)
      return
    }

    setLoading(true)
    try {
      const projectResults = await Promise.all(
        scopeProjects.map(async (project) => {
          const response = await fetchFn(project.externalId)
          const records = response.data?.data || []
          return records.map((record) => annotateRecordWithProject(record, project))
        })
      )
      setItems(projectResults.flat())
    } catch {
      toast.error('Failed to load data')
    } finally {
      setLoading(false)
    }
  }, [fetchFn, primaryProjectId, scopeProjects])

  useEffect(() => {
    load()
  }, [load, refreshSignal])

  useEffect(() => {
    setVisibleCount(PAGE_SIZE)
  }, [filters, scopeKey, search])

  useEffect(() => {
    const preset = location.state?.listPreset
    if (preset?.entityType === entityType) {
      setSearch(preset.search || '')
      setFilters(preset.filters || {})
      return
    }
    setSearch('')
    setFilters({})
  }, [entityType, location.key, location.state, scopeKey])

  const handleSync = async () => {
    if (!syncFn) return
    if (!primaryProjectId) {
      toast.error('Select a primary project first')
      return
    }

    setSyncing(true)
    setSyncResult(null)
    try {
      const response = await syncFn(primaryProjectId)
      setSyncResult(response.data?.data)
      toast.success('Synced!')
      await load()
      emitSyncRefresh({ projectId: primaryProjectId, scope: `${entityType}-sync` })
    } catch {
      toast.error('Sync failed')
    } finally {
      setSyncing(false)
    }
  }

  const stats = useMemo(() => {
    if (!items.length) return null
    const total = items.length
    const finished = items.filter((item) => {
      const normalized = (item.status || '').toLowerCase().replace(/ /g, '_').replace(/-/g, '_')
      return isChecklistDone(item.status) || normalized === 'resolved'
    }).length
    const inProg = items.filter((item) =>
      ['in_progress', 'inprogress', 'started', 'active', 'open']
        .includes((item.status || '').toLowerCase().replace(/ /g, '_').replace(/-/g, '_'))
    ).length
    const pct = total > 0 ? Math.round((finished / total) * 1000) / 10 : 0
    return { total, finished, inProg, pct }
  }, [items])

  const filterOptions = useMemo(() => (
    filterConfigs.map((config) => {
      const values = config.options?.length
        ? config.options
        : Array.from(new Set(items
          .map((item) => String(config.getValue ? config.getValue(item) : item[config.key] || '').trim())
          .filter(Boolean)))
          .sort((left, right) => left.localeCompare(right))

      return { ...config, values }
    })
  ), [filterConfigs, items])

  const filtered = useMemo(() => (
    items.filter((item) =>
      (!search || (
        searchKeys.some((key) => String(item[key] || '').toLowerCase().includes(search.toLowerCase()))
        || String(item.__sourceProjectName || '').toLowerCase().includes(search.toLowerCase())
        || String(item.__sourceProjectExternalId || '').toLowerCase().includes(search.toLowerCase())
      ))
      && filterConfigs.every((config) => {
        const selected = filters[config.key]
        if (!selected || selected === 'all') return true
        const rawValue = config.getValue ? config.getValue(item) : item[config.key]
        return String(rawValue || '').toLowerCase() === String(selected).toLowerCase()
      })
    )
  ), [filterConfigs, filters, items, search, searchKeys])

  const visibleItems = useMemo(() => filtered.slice(0, visibleCount), [filtered, visibleCount])
  const hasMore = visibleCount < filtered.length
  const remaining = Math.min(PAGE_SIZE, filtered.length - visibleCount)

  const handleExportCsv = () => {
    const baseColumns = columns.filter((column) => column.key && !column.key.startsWith('_'))
    const exportColumns = isMultiProject
      ? [{
          key: '__sourceProjectName',
          label: 'Project',
          exportValue: (_, row) => row.__sourceProjectName || row.__sourceProjectExternalId || '-',
        }, ...baseColumns]
      : baseColumns

    const header = exportColumns.map((column) => csvEscape(column.label)).join(',')
    const rows = filtered.map((row) => exportColumns.map((column) => {
      const value = column.exportValue ? column.exportValue(row[column.key], row) : row[column.key]
      return csvEscape(value)
    }).join(','))

    const blob = new Blob([[header, ...rows].join('\r\n')], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${entityType || 'records'}-${isMultiProject ? 'multi-project-scope' : (primaryProjectId || 'workspace')}.csv`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
  }

  const tableColumns = useMemo(() => (
    isMultiProject
      ? [{
          key: '__sourceProjectName',
          label: 'Project',
          render: (_, row) => (
            <div>
              <div className="font-600 text-white">{row.__sourceProjectName || row.__sourceProjectExternalId || '-'}</div>
              {row.__sourceProjectExternalId ? <div className="font-mono text-[10px] text-dark-500">{row.__sourceProjectExternalId}</div> : null}
            </div>
          ),
        }, ...columns]
      : columns
  ), [columns, isMultiProject])

  const renderedTopContent = typeof topContent === 'function'
    ? topContent({
        items,
        filteredItems: filtered,
        loading,
        primaryProjectId,
        scopeProjects,
        isMultiProject,
      })
    : topContent

  return (
    <div className="space-y-5 animate-fade-in">
      {renderedTopContent}

      {showStats && !loading && stats && stats.total > 0 ? (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12 }}>
          {[
            { label: 'Total', value: stats.total, color: '#38bdf8', bg: 'rgba(14,165,233,0.08)', border: 'rgba(14,165,233,0.2)', Icon: BarChart2 },
            { label: 'Finished', value: stats.finished, color: '#4ade80', bg: 'rgba(34,197,94,0.08)', border: 'rgba(34,197,94,0.2)', Icon: CheckCircle2 },
            { label: 'In Progress', value: stats.inProg, color: '#fbbf24', bg: 'rgba(234,179,8,0.08)', border: 'rgba(234,179,8,0.2)', Icon: Clock },
            {
              label: 'Completion',
              value: `${stats.pct % 1 === 0 ? stats.pct : stats.pct.toFixed(1)}%`,
              color: stats.pct >= 80 ? '#4ade80' : stats.pct >= 50 ? '#fbbf24' : '#f87171',
              bg: 'var(--border-subtle)',
              border: 'var(--border)',
              Icon: AlertCircle,
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
      ) : null}

      {showStats && !loading && stats && stats.total > 0 ? (
        <div style={{ height: 5, borderRadius: 999, background: 'var(--border)', overflow: 'hidden' }}>
          <div
            style={{
              height: '100%',
              width: `${stats.pct}%`,
              borderRadius: 999,
              background: stats.pct >= 80 ? 'linear-gradient(90deg,#22c55e,#4ade80)'
                : stats.pct >= 50 ? 'linear-gradient(90deg,#f59e0b,#fbbf24)'
                : 'linear-gradient(90deg,#ef4444,#f87171)',
              transition: 'width 0.6s ease',
            }}
          />
        </div>
      ) : null}

      <div className="flex flex-wrap items-center gap-3">
        <div className="relative flex-1 min-w-48">
          <Search size={14} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-dark-500" />
          <input type="text" value={search} onChange={(event) => setSearch(event.target.value)} className="input-field pl-9" placeholder="Search..." />
        </div>
        {filterOptions.map((config) => (
          <select
            key={config.key}
            value={filters[config.key] || 'all'}
            onChange={(event) => setFilters((current) => ({ ...current, [config.key]: event.target.value }))}
            className="input-field w-auto"
          >
            <option value="all">{config.label}</option>
            {config.values.map((option) => (
              <option key={option} value={option}>
                {config.formatOptionLabel ? config.formatOptionLabel(option) : option}
              </option>
            ))}
          </select>
        ))}
        {syncFn ? (
          <button onClick={handleSync} disabled={syncing} className="btn-secondary">
            <RefreshCw size={14} className={syncing ? 'animate-spin' : ''} />
            {syncing ? 'Syncing...' : isMultiProject ? 'Sync Primary' : 'Sync'}
          </button>
        ) : null}
        <button onClick={handleExportCsv} className="btn-secondary">
          <Download size={14} />
          Export CSV
        </button>
        <div className="glass-card-light px-4 py-2 text-xs text-dark-400">
          <span className="font-700 text-white">{filtered.length}</span> records
        </div>
        <div className="glass-card-light px-4 py-2 text-xs text-dark-400">
          <span className="font-700 text-white">{scopeProjects.length}</span> {scopeProjects.length === 1 ? 'project' : 'projects'} in scope
        </div>
      </div>

      {syncResult ? (
        <div className="relative">
          <button onClick={() => setSyncResult(null)} className="absolute top-2 right-2 text-dark-400 hover:text-white z-10">
            <X size={14} />
          </button>
          <SyncResultCard result={syncResult} />
        </div>
      ) : null}

      {loading ? (
        <div className="space-y-2">{[...Array(6)].map((_, index) => <Skeleton key={index} className="h-12" />)}</div>
      ) : filtered.length === 0 ? (
        <EmptyState icon={emptyIcon} title={emptyTitle} description={emptyDesc} />
      ) : (
        <>
          <Table columns={tableColumns} data={visibleItems} onRowClick={setDetail} />

          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10, padding: '4px 0 8px' }}>
            <div style={{ fontSize: 12, color: '#475569' }}>
              Showing <span style={{ color: '#cbd5e1', fontWeight: 700 }}>{visibleItems.length}</span> of <span style={{ color: '#cbd5e1', fontWeight: 700 }}>{filtered.length}</span> records
            </div>
            {hasMore ? (
              <button
                onClick={() => setVisibleCount((current) => current + PAGE_SIZE)}
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 8,
                  padding: '10px 32px',
                  background: 'rgba(56,189,248,0.08)',
                  border: '1px solid rgba(56,189,248,0.3)',
                  borderRadius: 10,
                  cursor: 'pointer',
                  color: '#38bdf8',
                  fontSize: 13,
                  fontWeight: 600,
                  transition: 'all 0.15s',
                }}
                onMouseEnter={(event) => {
                  event.currentTarget.style.background = 'rgba(56,189,248,0.18)'
                  event.currentTarget.style.borderColor = 'rgba(56,189,248,0.6)'
                }}
                onMouseLeave={(event) => {
                  event.currentTarget.style.background = 'rgba(56,189,248,0.08)'
                  event.currentTarget.style.borderColor = 'rgba(56,189,248,0.3)'
                }}
              >
                <ChevronDown size={15} />
                Load {remaining} more
              </button>
            ) : null}
          </div>
        </>
      )}

      <DetailModal
        record={detail}
        onClose={() => setDetail(null)}
        entityType={entityType}
        projectId={getRecordProjectId(detail, primaryProjectId)}
      />
    </div>
  )
}
