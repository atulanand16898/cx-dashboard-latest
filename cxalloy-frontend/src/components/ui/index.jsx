import React from 'react'
import ReactDOM from 'react-dom'
import { AlertCircle, RefreshCw } from 'lucide-react'

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
  const s = (status || '').toLowerCase()
  let cls = 'tag-gray'
  if (['open', 'active', 'in_progress'].includes(s)) cls = 'tag-blue'
  if (['closed', 'complete', 'completed', 'done', 'resolved'].includes(s)) cls = 'tag-green'
  if (['pending', 'review', 'in review'].includes(s)) cls = 'tag-yellow'
  if (['failed', 'error', 'critical', 'overdue'].includes(s)) cls = 'tag-red'

  return (
    <span className={`status-badge ${cls}`}>
      {status || 'Unknown'}
    </span>
  )
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
          <circle cx="48" cy="48" r={r} fill="none" stroke="rgba(255,255,255,0.06)" strokeWidth="8" />
          <circle
            cx="48" cy="48" r={r} fill="none"
            stroke={color}
            strokeWidth="8"
            strokeLinecap="round"
            strokeDasharray={circ}
            strokeDashoffset={offset}
            transform="rotate(-90 48 48)"
            style={{ transition: 'stroke-dashoffset 1s ease' }}
          />
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center">
          <span className="text-lg font-800 text-white">{Math.round(pct)}%</span>
          <span className="text-[9px] text-dark-400 font-mono">{value}/{total}</span>
        </div>
      </div>
      {label && <span className="text-xs text-dark-400 font-500">{label}</span>}
    </div>
  )
}

// ─── Stat card ─────────────────────────────────────────────────────────────────
export function StatCard({ label, value, sub, trend, icon: Icon, color = 'sky' }) {
  const colorMap = {
    sky: 'from-sky-500/10 border-sky-500/20 text-sky-400',
    green: 'from-green-500/10 border-green-500/20 text-green-400',
    yellow: 'from-yellow-500/10 border-yellow-500/20 text-yellow-400',
    red: 'from-red-500/10 border-red-500/20 text-red-400',
    purple: 'from-purple-500/10 border-purple-500/20 text-purple-400',
  }

  return (
    <div className={`metric-card p-5 bg-gradient-to-br ${colorMap[color] || colorMap.sky}`}>
      <div className="flex items-start justify-between mb-3">
        <div className="text-xs font-600 text-dark-400 uppercase tracking-widest">{label}</div>
        {Icon && (
          <div className={`w-8 h-8 rounded-lg bg-current/10 flex items-center justify-center ${colorMap[color]?.split(' ').pop()}`}>
            <Icon size={15} className="opacity-80" />
          </div>
        )}
      </div>
      <div className="text-3xl font-800 text-white mb-1 tracking-tight">{value}</div>
      {sub && <div className="text-xs text-dark-500">{sub}</div>}
      {trend != null && (
        <div className={`text-xs font-600 mt-2 ${trend >= 0 ? 'text-green-400' : 'text-red-400'}`}>
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
            <tr
              key={i}
              onClick={() => onRowClick?.(row)}
              className={`border-b border-white/[0.04] transition-colors
                ${onRowClick ? 'cursor-pointer hover:bg-white/[0.03]' : ''}`}
            >
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

// ─── Modal ─────────────────────────────────────────────────────────────────────
export function Modal({ open, onClose, title, children }) {
  React.useEffect(() => {
    if (!open) return
    const handler = (e) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', handler)
    // Lock body scroll while modal is open
    document.body.style.overflow = 'hidden'
    return () => {
      window.removeEventListener('keydown', handler)
      document.body.style.overflow = ''
    }
  }, [open, onClose])

  if (!open) return null

  // Rendered via portal directly into document.body so that no ancestor
  // transform / filter / will-change creates a new stacking context that
  // would trap `position: fixed` and cause the overlay-but-no-content bug.
  return ReactDOM.createPortal(
    <div
      style={{
        position: 'fixed', inset: 0, zIndex: 99999,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        padding: 16,
        backgroundColor: 'rgba(0,0,0,0.82)',
        backdropFilter: 'blur(6px)',
        WebkitBackdropFilter: 'blur(6px)',
      }}
      onClick={onClose}
    >
      <div
        onClick={e => e.stopPropagation()}
        style={{
          position: 'relative',
          width: '100%', maxWidth: 660,
          maxHeight: '88vh', overflowY: 'auto',
          background: '#0f172a',
          border: '1px solid rgba(255,255,255,0.12)',
          borderRadius: 18,
          boxShadow: '0 32px 100px rgba(0,0,0,0.85)',
          animation: 'modal-slide-up 0.22s cubic-bezier(0.16,1,0.3,1) both',
        }}
      >
        {/* Header */}
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '16px 20px', borderBottom: '1px solid rgba(255,255,255,0.08)',
          position: 'sticky', top: 0, background: '#0f172a', zIndex: 1, borderRadius: '18px 18px 0 0',
        }}>
          <h3 style={{ margin: 0, fontSize: 15, fontWeight: 700, color: '#f8fafc' }}>{title}</h3>
          <button
            onClick={onClose}
            style={{
              background: 'rgba(255,255,255,0.07)', border: '1px solid rgba(255,255,255,0.1)',
              borderRadius: 8, width: 30, height: 30,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              cursor: 'pointer', color: '#94a3b8', fontSize: 20, lineHeight: 1,
              transition: 'background 0.15s',
            }}
            onMouseEnter={e => e.currentTarget.style.background = 'rgba(255,255,255,0.15)'}
            onMouseLeave={e => e.currentTarget.style.background = 'rgba(255,255,255,0.07)'}
          >×</button>
        </div>
        {/* Body */}
        <div style={{ padding: 20 }}>{children}</div>
      </div>
      <style>{`
        @keyframes modal-slide-up {
          from { opacity: 0; transform: translateY(20px) scale(0.97); }
          to   { opacity: 1; transform: translateY(0)    scale(1);    }
        }
      `}</style>
    </div>,
    document.body
  )
}

// ─── Tab switcher ─────────────────────────────────────────────────────────────
export function TabSwitcher({ tabs, active, onChange }) {
  return (
    <div className="flex gap-1 p-1 bg-dark-900/80 rounded-xl border border-white/[0.06]">
      {tabs.map(tab => (
        <button
          key={tab.value}
          onClick={() => onChange(tab.value)}
          className={`px-4 py-1.5 rounded-lg text-xs font-600 transition-all ${
            active === tab.value
              ? 'bg-sky-500/20 text-sky-300 border border-sky-500/30'
              : 'text-dark-400 hover:text-white'
          }`}
        >
          {tab.label}
        </button>
      ))}
    </div>
  )
}
