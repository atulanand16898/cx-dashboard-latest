import React, { useState, useEffect, useMemo, useCallback } from 'react'
import { Sparkles, Download, RefreshCw, FileText, Activity, Clock } from 'lucide-react'
import { useProject } from '../context/ProjectContext'
import { checklistsApi, issuesApi, briefsApi } from '../services/api'
import toast from 'react-hot-toast'

// ── Helpers ────────────────────────────────────────────────────────────────────
function getISOWeekLabel(date = new Date()) {
  const d = new Date(Date.UTC(date.getFullYear(), date.getMonth(), date.getDate()))
  const dayNum = d.getUTCDay() || 7
  d.setUTCDate(d.getUTCDate() + 4 - dayNum)
  const yearStart = new Date(Date.UTC(d.getUTCFullYear(), 0, 1))
  const wk = Math.ceil((((d - yearStart) / 86400000) + 1) / 7)
  return `${d.getUTCFullYear()}-W${String(wk).padStart(2, '0')}`
}

function fmtDate(iso) {
  if (!iso) return ''
  try { return new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) }
  catch { return '' }
}

function fmtTime(iso) {
  if (!iso) return ''
  try { return new Date(iso).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' }) }
  catch { return '' }
}

function reportTypeLabel(period) {
  if (period === 'D') return 'Daily Report'
  if (period === 'W') return 'Weekly Snapshot'
  if (period === 'M') return 'Monthly Report'
  return 'Overall Snapshot'
}

function downloadCSV(rows, filename) {
  const csv = rows.map(r => r.map(v => `"${String(v ?? '').replace(/"/g, '""')}"`).join(',')).join('\n')
  const blob = new Blob([csv], { type: 'text/csv' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a'); a.href = url; a.download = filename; a.click()
  URL.revokeObjectURL(url)
}

// ── Period badge ───────────────────────────────────────────────────────────────
function PeriodBadge({ period }) {
  const map = { D: ['#fbbf24','rgba(251,191,36,0.12)'], W: ['#60a5fa','rgba(96,165,250,0.12)'],
    M: ['#a78bfa','rgba(167,139,250,0.12)'], Overall: ['#34d399','rgba(52,211,153,0.12)'] }
  const [color, bg] = map[period] || map.Overall
  return (
    <span style={{ fontSize: 10, fontWeight: 700, color, background: bg, padding: '2px 7px',
      borderRadius: 5, textTransform: 'uppercase', letterSpacing: '0.06em' }}>
      {period || 'Overall'}
    </span>
  )
}

// ── KPI Card ──────────────────────────────────────────────────────────────────
function KPICard({ label, value, sub, color, icon: Icon }) {
  return (
    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)',
      borderRadius: 14, padding: '18px 20px', flex: 1, minWidth: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
        <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--text-muted)',
          textTransform: 'uppercase', letterSpacing: '0.08em' }}>{label}</div>
        {Icon && <Icon size={15} style={{ color: color || '#60a5fa', opacity: 0.7 }} />}
      </div>
      <div style={{ fontSize: 30, fontWeight: 800, color: color || '#60a5fa', lineHeight: 1, marginBottom: 5 }}>{value}</div>
      {sub && <div style={{ fontSize: 12, color: 'var(--text-muted)', lineHeight: 1.4 }}>{sub}</div>}
    </div>
  )
}

// ── Main component ─────────────────────────────────────────────────────────────
export default function TrackerBriefsPage() {
  const { selectedProjects, activeProject, period } = useProject()

  const targets = selectedProjects.length > 0
    ? selectedProjects
    : (activeProject ? [activeProject] : [])

  const portfolioTitle = targets.length > 1
    ? `${targets.length} Projects Portfolio`
    : (targets[0]?.name || 'No project selected')

  const visibleWindow = period === 'D' ? 'Daily' : period === 'W' ? 'Weekly'
    : period === 'M' ? 'Monthly' : 'Overall'

  // ── Server briefs state ───────────────────────────────────────────────────
  const [briefs, setBriefs]             = useState([])
  const [briefsLoading, setBriefsLoading] = useState(false)
  const [generating, setGenerating]     = useState(false)
  const [exporting, setExporting]       = useState(false)
  const [lastExport, setLastExport]     = useState(null)

  // ── Fetch briefs from backend ─────────────────────────────────────────────
  const fetchBriefs = useCallback(async (quiet = false) => {
    if (!targets.length) { setBriefs([]); return }
    if (!quiet) setBriefsLoading(true)
    try {
      const results = await Promise.all(
        targets.map(p =>
          briefsApi.getByProject(p.externalId, period)
            .then(r => r.data?.data || [])
            .catch(() => [])
        )
      )
      const merged = results.flat().sort((a, b) =>
        new Date(b.exportedAt || 0) - new Date(a.exportedAt || 0)
      )
      setBriefs(merged)
      return merged
    } finally {
      if (!quiet) setBriefsLoading(false)
    }
  }, [targets.map(p => p.externalId).join(','), period])  // eslint-disable-line

  // On mount / project change — fetch, and auto-generate if empty
  useEffect(() => {
    if (!targets.length) return
    fetchBriefs().then(async (existing) => {
      // Auto-generate a snapshot if no briefs exist for this project yet
      if (!existing || existing.length === 0) {
        try {
          await Promise.all(
            targets.map(p => briefsApi.generate(p.externalId, period).catch(() => null))
          )
          await fetchBriefs(true)
        } catch { /* silent */ }
      }
    })
  }, [targets.map(p => p.externalId).join(','), period])  // eslint-disable-line

  // ── Generate snapshot (manual button) ────────────────────────────────────
  const handleGenerateSnapshot = useCallback(async () => {
    if (!targets.length || generating) return
    setGenerating(true)
    try {
      await Promise.all(
        targets.map(p => briefsApi.generate(p.externalId, period))
      )
      await fetchBriefs(true)
      toast.success(`Snapshot generated for ${targets.length} project${targets.length > 1 ? 's' : ''}`)
    } catch (err) {
      toast.error('Failed to generate snapshot')
      console.error(err)
    } finally {
      setGenerating(false)
    }
  }, [targets, generating, period, fetchBriefs])

  // ── Export CSV handler ────────────────────────────────────────────────────
  const handleExportCSV = useCallback(async () => {
    if (!targets.length || exporting) return
    setExporting(true)
    try {
      const weekLabel = getISOWeekLabel()

      const results = await Promise.all(
        targets.map(p => Promise.all([
          checklistsApi.getAll(p.externalId).then(r => r.data?.data || []).catch(() => []),
          issuesApi.getAll(p.externalId).then(r => r.data?.data || []).catch(() => []),
        ]))
      )
      const allChecklists = results.flatMap(r => r[0])
      const allIssues     = results.flatMap(r => r[1])

      if (allChecklists.length > 0) {
        downloadCSV([
          ['ID', 'Name', 'Status', 'Tag Level', 'Project', 'Created'],
          ...allChecklists.map(c => [
            c.externalId || '', c.name || '', c.status || '',
            c.tagLevel || c.tag_level || '', c.projectId || '', c.createdAt || '',
          ]),
        ], `checklists-${portfolioTitle.replace(/\s+/g, '-')}-${weekLabel}.csv`)
      }

      if (allIssues.length > 0) {
        downloadCSV([
          ['ID', 'Title', 'Status', 'Priority', 'Location', 'Project', 'Created'],
          ...allIssues.map(i => [
            i.externalId || '', i.title || i.name || '', i.status || '',
            i.priority || '', i.location || i.spaceId || '', i.projectId || '', i.createdAt || '',
          ]),
        ], `issues-${portfolioTitle.replace(/\s+/g, '-')}-${weekLabel}.csv`)
      }

      const totalRows = allChecklists.length + allIssues.length

      // Persist brief record for each target project
      await Promise.all(
        targets.map(p =>
          briefsApi.create({
            projectId: p.externalId,
            title:     `${p.name} ${reportTypeLabel(period)} ${weekLabel}`,
            subtitle:  `${reportTypeLabel(period)} | ${weekLabel}`,
            items:  allChecklists.filter(c => !c.projectId || c.projectId === p.externalId).length || allChecklists.length,
            issues: allIssues.filter(i    => !i.projectId  || i.projectId  === p.externalId).length || allIssues.length,
            period,
          }).catch(() => null)
        )
      )

      setLastExport({ count: totalRows, time: new Date().toISOString() })
      await fetchBriefs(true)
      toast.success(`CSV exported — ${totalRows} rows across ${targets.length} project${targets.length > 1 ? 's' : ''}`)
    } catch (err) {
      toast.error('Export failed')
      console.error(err)
    } finally {
      setExporting(false)
    }
  }, [targets, exporting, period, portfolioTitle, fetchBriefs])

  // ── Derived values ─────────────────────────────────────────────────────────
  const visibleBriefs = useMemo(() =>
    briefs.filter(b => period === 'Overall' || !b.period || b.period === period),
    [briefs, period]
  )

  const totalItems  = briefs.reduce((s, b) => s + (b.items  || 0), 0)
  const totalIssues = briefs.reduce((s, b) => s + (b.issues || 0), 0)
  const exportStatus = exporting ? 'Exporting…' : generating ? 'Generating…'
    : lastExport ? `Done · ${fmtTime(lastExport.time)}` : 'Idle'
  const exportColor  = (exporting || generating) ? '#f59e0b' : lastExport ? '#22c55e' : 'var(--text-muted)'

  if (!targets.length) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: 300, gap: 12 }}>
        <FileText size={36} style={{ color: 'var(--text-muted)', opacity: 0.5 }} />
        <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)' }}>No Project Selected</div>
        <div style={{ fontSize: 13, color: 'var(--text-muted)' }}>Select a project to view its Tracker Briefs</div>
      </div>
    )
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>

      {/* ── Header ──────────────────────────────────────────────────────────── */}
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
        <div>
          <h2 style={{ fontSize: 22, fontWeight: 800, color: 'var(--text-primary)', margin: 0 }}>Tracker Briefs</h2>
          <p style={{ fontSize: 13, color: 'var(--text-muted)', marginTop: 4, marginBottom: 0 }}>
            Project snapshots and CSV exports — generated from live DB data · {visibleWindow} view
          </p>
        </div>
        <div style={{ display: 'flex', gap: 10, flexShrink: 0 }}>
          {/* Generate Snapshot button */}
          <button
            onClick={handleGenerateSnapshot}
            disabled={generating || !targets.length}
            style={{
              display: 'flex', alignItems: 'center', gap: 7,
              padding: '9px 16px', borderRadius: 9, fontSize: 13, fontWeight: 600, cursor: 'pointer',
              background: 'var(--bg-card)', border: '1px solid var(--border)',
              color: generating ? 'var(--text-muted)' : '#a78bfa',
              transition: 'all 0.15s', opacity: generating ? 0.7 : 1,
            }}
            onMouseEnter={e => { if (!generating) e.currentTarget.style.borderColor = '#a78bfa' }}
            onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--border)' }}
          >
            {generating
              ? <><RefreshCw size={14} style={{ animation: 'spin 1s linear infinite' }} /> Generating…</>
              : <><Sparkles size={14} /> Generate Snapshot</>
            }
          </button>
          {/* Export CSV button */}
          <button
            onClick={handleExportCSV}
            disabled={exporting || !targets.length}
            style={{
              display: 'flex', alignItems: 'center', gap: 7,
              padding: '9px 16px', borderRadius: 9, fontSize: 13, fontWeight: 600, cursor: 'pointer',
              background: '#0ea5e9', border: 'none', color: 'white',
              transition: 'all 0.15s', opacity: exporting ? 0.7 : 1,
            }}
            onMouseEnter={e => { if (!exporting) e.currentTarget.style.background = '#38bdf8' }}
            onMouseLeave={e => { e.currentTarget.style.background = '#0ea5e9' }}
          >
            {exporting
              ? <><RefreshCw size={14} style={{ animation: 'spin 1s linear infinite' }} /> Exporting…</>
              : <><Download size={14} /> Export CSV</>
            }
          </button>
        </div>
      </div>

      {/* ── KPI Cards ─────────────────────────────────────────────────────── */}
      <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap' }}>
        <KPICard
          label="Saved Reports"
          value={briefsLoading ? '…' : briefs.length}
          sub={`${visibleBriefs.length} visible in ${visibleWindow} mode`}
          color="#60a5fa"
          icon={FileText}
        />
        <KPICard
          label="Total Items Tracked"
          value={briefsLoading ? '…' : totalItems.toLocaleString()}
          sub="Checklists across all briefs"
          color="#22c55e"
          icon={Activity}
        />
        <KPICard
          label="Issues Logged"
          value={briefsLoading ? '…' : totalIssues.toLocaleString()}
          sub="Issues tracked in briefs"
          color="#f87171"
          icon={Activity}
        />
        <KPICard
          label="Export Status"
          value={exportStatus}
          sub={lastExport ? `${lastExport.count} rows exported` : 'Click Export CSV to download'}
          color={exportColor}
          icon={Clock}
        />
      </div>

      {/* ── How Briefs Work info banner ──────────────────────────────────── */}
      <div style={{ background: 'rgba(167,139,250,0.07)', border: '1px solid rgba(167,139,250,0.18)',
        borderRadius: 12, padding: '14px 18px', display: 'flex', gap: 12, alignItems: 'flex-start' }}>
        <Sparkles size={15} style={{ color: '#a78bfa', marginTop: 2, flexShrink: 0 }} />
        <div>
          <div style={{ fontSize: 12, fontWeight: 700, color: '#a78bfa', marginBottom: 4 }}>
            How Tracker Briefs work
          </div>
          <div style={{ fontSize: 12, color: 'var(--text-muted)', lineHeight: 1.6 }}>
            <strong style={{ color: 'var(--text-secondary)' }}>Generate Snapshot</strong> reads your already-synced checklist and issue data 
            from the local database and saves a timestamped summary — no CxAlloy call needed.{' '}
            <strong style={{ color: 'var(--text-secondary)' }}>Export CSV</strong> additionally downloads the raw data as CSV files to your device.
            Briefs persist across sessions and are scoped to the selected project and period.
          </div>
        </div>
      </div>

      {/* ── Briefs Table ──────────────────────────────────────────────────── */}
      <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 14, overflow: 'hidden' }}>

        {/* Table header */}
        <div style={{ padding: '16px 20px', display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          borderBottom: '1px solid var(--border)', background: 'var(--bg-card-light)' }}>
          <div>
            <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' }}>
              Recent Briefings
              {visibleBriefs.length > 0 && (
                <span style={{ fontSize: 12, fontWeight: 500, color: 'var(--text-muted)', marginLeft: 8 }}>
                  ({visibleBriefs.length} in {visibleWindow} · {briefs.length} total)
                </span>
              )}
            </div>
            <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 2 }}>
              Snapshots are generated from local DB — no CxAlloy sync required
            </div>
          </div>
          <button
            onClick={() => fetchBriefs()}
            disabled={briefsLoading}
            style={{ background: 'none', border: '1px solid var(--border)', borderRadius: 7,
              padding: '6px 10px', cursor: 'pointer', color: 'var(--text-muted)', display: 'flex', alignItems: 'center', gap: 5, fontSize: 12 }}
            title="Refresh list"
          >
            <RefreshCw size={12} style={{ animation: briefsLoading ? 'spin 1s linear infinite' : 'none' }} />
            Refresh
          </button>
        </div>

        {/* Column headers */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 120px 90px 90px 130px',
          padding: '8px 20px', borderBottom: '1px solid var(--border-subtle)',
          background: 'var(--bg-card-light)' }}>
          {['Title / Subtitle', 'Period', 'Items', 'Issues', 'Generated'].map(h => (
            <div key={h} style={{ fontSize: 10, fontWeight: 700, color: 'var(--text-muted)',
              textTransform: 'uppercase', letterSpacing: '0.07em' }}>{h}</div>
          ))}
        </div>

        {/* Rows */}
        {briefsLoading ? (
          <div style={{ padding: '52px', textAlign: 'center', color: 'var(--text-muted)', fontSize: 13 }}>
            <RefreshCw size={20} style={{ animation: 'spin 1s linear infinite', marginBottom: 10, color: '#60a5fa' }} />
            <div>Loading briefings…</div>
          </div>
        ) : visibleBriefs.length === 0 ? (
          <div style={{ padding: '52px', textAlign: 'center' }}>
            <FileText size={32} style={{ color: 'var(--text-muted)', opacity: 0.4, marginBottom: 12 }} />
            <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 }}>
              No briefings yet for {visibleWindow} mode
            </div>
            <div style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 18 }}>
              Click <strong>Generate Snapshot</strong> to create your first brief from synced data
            </div>
            <button
              onClick={handleGenerateSnapshot}
              disabled={generating}
              style={{ display: 'inline-flex', alignItems: 'center', gap: 7, padding: '10px 20px',
                background: '#a78bfa', color: 'white', border: 'none', borderRadius: 9, fontSize: 13,
                fontWeight: 600, cursor: 'pointer' }}
            >
              <Sparkles size={14} />
              {generating ? 'Generating…' : 'Generate Snapshot Now'}
            </button>
          </div>
        ) : (
          visibleBriefs.map((b, i) => (
            <div
              key={b.id || i}
              style={{
                display: 'grid', gridTemplateColumns: '1fr 120px 90px 90px 130px',
                padding: '13px 20px',
                borderTop: '1px solid var(--divider)',
                background: i % 2 === 1 ? 'var(--row-alt)' : 'transparent',
                transition: 'background 0.1s',
              }}
              onMouseEnter={e => e.currentTarget.style.background = 'var(--bg-card-light)'}
              onMouseLeave={e => e.currentTarget.style.background = i % 2 === 1 ? 'var(--row-alt)' : 'transparent'}
            >
              {/* Title */}
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-primary)',
                  overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {b.title || '—'}
                </div>
                {b.subtitle && (
                  <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 2 }}>
                    {b.subtitle}
                  </div>
                )}
              </div>
              {/* Period */}
              <div style={{ display: 'flex', alignItems: 'center' }}>
                <PeriodBadge period={b.period} />
              </div>
              {/* Items */}
              <div style={{ display: 'flex', alignItems: 'center', fontSize: 13,
                fontWeight: 700, color: '#22c55e' }}>
                {(b.items ?? 0).toLocaleString()}
              </div>
              {/* Issues */}
              <div style={{ display: 'flex', alignItems: 'center', fontSize: 13,
                fontWeight: 700, color: '#f87171' }}>
                {(b.issues ?? 0).toLocaleString()}
              </div>
              {/* Date */}
              <div style={{ display: 'flex', alignItems: 'center', fontSize: 12, color: 'var(--text-muted)' }}>
                {fmtDate(b.exportedAt)}
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  )
}
