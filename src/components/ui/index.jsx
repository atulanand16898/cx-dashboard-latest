import React from 'react'
import { AlertCircle, RefreshCw, X } from 'lucide-react'
import { CHECKLIST_DONE_STATUSES } from '../../utils/checklistStatusUtils'

// ─── Skeleton loader ────────────────────────────────────────────────────────
export function Skeleton({ className = '' }) {
  return <div className={`shimmer-bg rounded-lg ${className}`} />
}

export function CardSkeleton() {
  return (
    <div className="glass-card p-5 space-y-3">
      <Skeleton className="h-3 w-24" />
      <Skeleton className="h-8 w-16" />
      <Skeleton className="h-2 w-32" />
    </div>
  )
}

// ─── Error state ─────────────────────────────────────────────────────────────
export function ErrorState({ message, onRetry }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <div className="w-12 h-12 rounded-full bg-red-500/10 flex items-center justify-center mb-4">
        <AlertCircle size={22} className="text-red-400" />
      </div>
      <div className="text-sm font-600 text-white mb-1">Something went wrong</div>
      <div className="text-xs text-dark-400 mb-4 max-w-xs">{message}</div>
      {onRetry && (
        <button onClick={onRetry} className="btn-secondary text-xs py-1.5">
          <RefreshCw size={12} />
          Retry
        </button>
      )}
    </div>
  )
}

// ─── Empty state ─────────────────────────────────────────────────────────────
export function EmptyState({ icon: Icon, title, description }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center">
      <div className="w-12 h-12 rounded-full bg-dark-800 flex items-center justify-center mb-4">
        {Icon && <Icon size={22} className="text-dark-400" />}
      </div>
      <div className="text-sm font-600 text-dark-200 mb-1">{title}</div>
      {description && <div className="text-xs text-dark-500">{description}</div>}
    </div>
  )
}

// ─── Status badge ─────────────────────────────────────────────────────────────
export function StatusBadge({ status }) {
  const s = (status || '').toLowerCase().trim().replace(/ /g, '_').replace(/-/g, '_')
  let cls = 'tag-gray'
  // Open / active states
  if (['in_progress', 'inprogress', 'started', 'active', 'open', 'issue_opened',
       'correction_in_progress'].includes(s)) cls = 'tag-blue'
  // Closed / finished states
  if (CHECKLIST_DONE_STATUSES.has(s)
      || ['resolved', 'issue_closed'].includes(s)) cls = 'tag-green'
  // Pending / not started
  if (['not_started', 'notstarted', 'new', 'pending',
       'additional_information_needed'].includes(s)) cls = 'tag-gray'
  // Review / verify states
  if (['on_hold', 'onhold', 'hold', 'review', 'in_review',
       'gc_to_verify', 'cxa_to_verify', 'ready_for_retest'].includes(s)) cls = 'tag-yellow'
  // Error / failure states
  if (['failed', 'error', 'critical', 'overdue', 'cancelled', 'canceled'].includes(s)) cls = 'tag-red'
  const issueLabels = {
    issue_opened: 'Issue Opened',
    correction_in_progress: 'Correction in Progress',
    gc_to_verify: 'GC to Verify',
    cxa_to_verify: 'CxA to verify',
    issue_closed: 'Issue Closed',
    accepted_by_owner: 'Accepted by Owner',
    recommendation: 'Recommendation',
  }
  const label = issueLabels[s]
    || (status || 'Unknown').replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase())
  return <span className={`status-badge ${cls}`}>{label}</span>
}

// ─── Priority badge ─────────────────────────────────────────────────────────
export function PriorityBadge({ priority }) {
  const p = (priority || '').toLowerCase()
  let cls = 'tag-gray'
  if (['high', 'critical'].includes(p)) cls = 'tag-red'
  if (['medium', 'moderate'].includes(p)) cls = 'tag-yellow'
  if (['low'].includes(p)) cls = 'tag-green'
  return <span className={`status-badge ${cls}`}>{priority || 'None'}</span>
}

// ─── Donut chart ─────────────────────────────────────────────────────────────
export function DonutChart({ value, total, label, color = '#0ea5e9', size = 96 }) {
  const pct = total > 0 ? (value / total) * 100 : 0
  const r = 40
  const circ = 2 * Math.PI * r
  const offset = circ - (pct / 100) * circ
  return (
    <div className="flex flex-col items-center gap-2">
      <div className="relative" style={{ width: size, height: size }}>
        <svg width={size} height={size} viewBox="0 0 96 96">
          {/* Track circle — uses CSS var so it's visible in both light and dark */}
          <circle cx="48" cy="48" r={r} fill="none" stroke="var(--donut-track)" strokeWidth="8" />
          <circle cx="48" cy="48" r={r} fill="none" stroke={color} strokeWidth="8" strokeLinecap="round"
            strokeDasharray={circ} strokeDashoffset={offset} transform="rotate(-90 48 48)"
            style={{ transition: 'stroke-dashoffset 1s ease' }} />
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center">
          <span style={{ fontSize: size > 90 ? 17 : 13, fontWeight: 800, color: 'var(--text-primary)', lineHeight: 1.1 }}>{Math.round(pct)}%</span>
          <span style={{ fontSize: 9, color: 'var(--text-muted)', fontFamily: 'monospace', marginTop: 1 }}>{value}/{total}</span>
        </div>
      </div>
      {label && <span style={{ fontSize: 11, color: 'var(--text-muted)', fontWeight: 500 }}>{label}</span>}
    </div>
  )
}

// ─── Stat card ─────────────────────────────────────────────────────────────────
export function StatCard({ label, value, sub, trend, icon: Icon, color = 'sky' }) {
  const accentMap = {
    sky:    { accent: '#0ea5e9', border: 'rgba(14,165,233,0.20)',  bg: 'rgba(14,165,233,0.08)'  },
    green:  { accent: '#22c55e', border: 'rgba(34,197,94,0.20)',   bg: 'rgba(34,197,94,0.08)'   },
    yellow: { accent: '#eab308', border: 'rgba(234,179,8,0.20)',   bg: 'rgba(234,179,8,0.08)'   },
    red:    { accent: '#ef4444', border: 'rgba(239,68,68,0.20)',   bg: 'rgba(239,68,68,0.08)'   },
    purple: { accent: '#a855f7', border: 'rgba(168,85,247,0.20)',  bg: 'rgba(168,85,247,0.08)'  },
  }
  const { accent, border, bg } = accentMap[color] || accentMap.sky
  return (
    <div style={{ background: 'var(--bg-card)', border: `1px solid ${border}`, borderRadius: 16, padding: '18px 20px', transition: 'all 0.25s ease' }}
      onMouseEnter={e => { e.currentTarget.style.transform = 'translateY(-2px)'; e.currentTarget.style.boxShadow = `0 8px 28px ${bg}` }}
      onMouseLeave={e => { e.currentTarget.style.transform = ''; e.currentTarget.style.boxShadow = '' }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 12 }}>
        <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.09em' }}>{label}</div>
        {Icon && (
          <div style={{ width: 32, height: 32, borderRadius: 9, background: bg, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
            <Icon size={15} style={{ color: accent }} />
          </div>
        )}
      </div>
      <div style={{ fontSize: 32, fontWeight: 800, color: 'var(--text-primary)', lineHeight: 1, marginBottom: 4 }}>{value}</div>
      {sub  && <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{sub}</div>}
      {trend != null && (
        <div style={{ fontSize: 12, fontWeight: 600, marginTop: 8, color: trend >= 0 ? '#22c55e' : '#ef4444' }}>
          {trend >= 0 ? '↑' : '↓'} {Math.abs(trend)}% vs last period
        </div>
      )}
    </div>
  )
}

// ─── Table ─────────────────────────────────────────────────────────────────────
export function Table({ columns, data, onRowClick }) {
  return (
    <div className="overflow-x-auto rounded-xl border border-white/[0.06]">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-white/[0.06] bg-dark-900/60">
            {columns.map(col => (
              <th key={col.key} className="px-4 py-3 text-left text-[10px] font-700 text-dark-400 uppercase tracking-widest whitespace-nowrap">
                {col.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.map((row, i) => (
            <tr key={i} onClick={() => onRowClick?.(row)}
              className={`border-b border-white/[0.04] transition-colors ${onRowClick ? 'cursor-pointer hover:bg-white/[0.03]' : ''}`}>
              {columns.map(col => (
                <td key={col.key} className="px-4 py-3 text-dark-200 whitespace-nowrap">
                  {col.render ? col.render(row[col.key], row) : (row[col.key] ?? '—')}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ─── Sync result card ─────────────────────────────────────────────────────────
export function SyncResultCard({ result }) {
  if (!result) return null
  const ok = result.status === 'SUCCESS' || result.overallStatus === 'SUCCESS'
  return (
    <div className={`glass-card-light p-4 border ${ok ? 'border-green-500/20 bg-green-500/5' : 'border-red-500/20 bg-red-500/5'}`}>
      <div className={`text-sm font-700 mb-2 ${ok ? 'text-green-400' : 'text-red-400'}`}>
        {ok ? '✓ Sync Successful' : '✗ Sync Failed'}
      </div>
      <pre className="text-xs text-dark-300 font-mono whitespace-pre-wrap break-all max-h-48 overflow-y-auto">
        {JSON.stringify(result, null, 2)}
      </pre>
    </div>
  )
}

// ─── Modal — fixed portal with solid background so it's visible in both themes ──
export function Modal({ open, onClose, title, children }) {
  React.useEffect(() => {
    if (!open) return
    const handler = (e) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [open, onClose])

  if (!open) return null

  return (
    <div
      style={{
        position: 'fixed', inset: 0, zIndex: 9999,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        padding: 16,
        backgroundColor: 'rgba(0,0,0,0.75)',
        backdropFilter: 'blur(4px)',
      }}
      onClick={onClose}
    >
      <div
        onClick={e => e.stopPropagation()}
        style={{
          position: 'relative',
          width: '100%', maxWidth: 640,
          maxHeight: '85vh',
          overflowY: 'auto',
          background: 'var(--modal-bg)',
          border: '1px solid var(--modal-border)',
          borderRadius: 16,
          boxShadow: '0 24px 80px rgba(0,0,0,0.6)',
          animation: 'slide-up 0.22s ease both',
        }}
      >
        {/* Header */}
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '16px 20px',
          borderBottom: '1px solid rgba(255,255,255,0.08)',
        }}>
          <h3 style={{ margin: 0, fontSize: 15, fontWeight: 700, color: 'var(--text-primary)' }}>{title}</h3>
          <button
            onClick={onClose}
            style={{
              background: 'rgba(255,255,255,0.06)', border: 'none', borderRadius: 8,
              width: 28, height: 28, display: 'flex', alignItems: 'center', justifyContent: 'center',
              cursor: 'pointer', color: 'var(--text-secondary)', transition: 'all 0.15s',
            }}
            onMouseEnter={e => { e.currentTarget.style.background = 'rgba(255,255,255,0.12)'; e.currentTarget.style.color = 'var(--text-primary)' }}
            onMouseLeave={e => { e.currentTarget.style.background = 'rgba(255,255,255,0.06)'; e.currentTarget.style.color = 'var(--text-secondary)' }}
          >
            <X size={14} />
          </button>
        </div>
        {/* Body */}
        <div style={{ padding: 20 }}>{children}</div>
      </div>
    </div>
  )
}

// ─── Detail field grid — used inside Modal for key/value display ──────────────
export function DetailGrid({ data }) {
  if (!data) return null
  const entries = Object.entries(data).filter(([k]) => !['rawJson', 'id'].includes(k))
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 10 }}>
      {entries.map(([k, v]) => {
        const label = k.replace(/([A-Z])/g, ' $1').trim()
        const display = v === null || v === undefined
          ? '—'
          : Array.isArray(v)
            ? v.map(item => typeof item === 'object' && item !== null ? JSON.stringify(item) : String(item)).join(', ') || '—'
            : typeof v === 'object'
              ? JSON.stringify(v, null, 2)
              : String(v)
        return (
          <div key={k} style={{
            background: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.07)',
            borderRadius: 10, padding: '10px 12px',
          }}>
            <div style={{ fontSize: 10, fontWeight: 600, color: '#475569', textTransform: 'uppercase', letterSpacing: '0.07em', marginBottom: 4 }}>
              {label}
            </div>
            <div style={{ fontSize: 12, fontFamily: 'monospace', color: 'var(--text-secondary)', wordBreak: 'break-all' }}>
              {display}
            </div>
          </div>
        )
      })}
    </div>
  )
}

// ─── Tab switcher ─────────────────────────────────────────────────────────────
export function TabSwitcher({ tabs, active, onChange }) {
  return (
    <div className="flex gap-1 p-1 bg-dark-900/80 rounded-xl border border-white/[0.06]">
      {tabs.map(tab => (
        <button key={tab.value} onClick={() => onChange(tab.value)}
          className={`px-4 py-1.5 rounded-lg text-xs font-600 transition-all ${
            active === tab.value ? 'bg-sky-500/20 text-sky-300 border border-sky-500/30' : 'text-dark-400 hover:text-white'
          }`}>
          {tab.label}
        </button>
      ))}
    </div>
  )
}
