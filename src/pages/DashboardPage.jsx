import React, { useState, useEffect, useMemo } from 'react'
import { AlertCircle, CheckCircle2, Activity, Target, Zap, TrendingUp } from 'lucide-react'
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import { useProject } from '../context/ProjectContext'
import { issuesApi, tasksApi, checklistsApi } from '../services/api'
import { DonutChart, StatCard, CardSkeleton } from '../components/ui'
import { CHECKLIST_TAG_COLORS, DASHBOARD_CHECKLIST_TAG_ORDER, checklistTagDisplayLabel, deriveChecklistTag } from '../utils/checklistTagUtils'
import { isChecklistDone } from '../utils/checklistStatusUtils'
import toast from 'react-hot-toast'

// ─── ISO week label (correct ISO-8601) ───────────────────────────────────────
function isoWeekLabel(d) {
  const tmp = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()))
  const day = tmp.getUTCDay() || 7
  tmp.setUTCDate(tmp.getUTCDate() + 4 - day)
  const yr = new Date(Date.UTC(tmp.getUTCFullYear(), 0, 1))
  const wk = Math.ceil((((tmp - yr) / 86400000) + 1) / 7)
  return `${tmp.getUTCFullYear()}-W${String(wk).padStart(2, '0')}`
}

function pickDate(item) {
  return item.createdAt || item.created_at || item.updatedAt || item.updated_at || null
}

// ─── Build real weekly issue chart data from actual issue records ─────────────
function buildWeeklyData(issues, period) {
  if (!issues.length) return []

  const CLOSED = new Set(['closed','completed','resolved','done','issue_closed','accepted_by_owner','finished'])
  const now = new Date()

  if (period === 'D') {
    // Last 7 days by day of week
    const dayNames = ['Sun','Mon','Tue','Wed','Thu','Fri','Sat']
    const buckets = Array.from({ length: 7 }, (_, i) => {
      const d = new Date(now)
      d.setDate(now.getDate() - (6 - i))
      return { label: dayNames[d.getDay()], dateStr: d.toISOString().slice(0, 10), opened: 0, closed: 0 }
    })
    issues.forEach(issue => {
      const raw = pickDate(issue)
      if (!raw) return
      const ds = new Date(raw).toISOString().slice(0, 10)
      const b = buckets.find(b => b.dateStr === ds)
      if (!b) return
      if (CLOSED.has((issue.status || '').toLowerCase().replace(/[ \-]/g, '_'))) b.closed++
      else b.opened++
    })
    return buckets.map(b => ({ week: b.label, opened: b.opened, closed: b.closed }))
  }

  if (period === 'M') {
    // Last 6 calendar months
    const months = []
    for (let i = 5; i >= 0; i--) {
      const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
      months.push({
        label: d.toLocaleString('en-US', { month: 'short' }),
        year: d.getFullYear(), month: d.getMonth(),
        opened: 0, closed: 0,
      })
    }
    issues.forEach(issue => {
      const raw = pickDate(issue)
      if (!raw) return
      const d = new Date(raw)
      const b = months.find(m => m.year === d.getFullYear() && m.month === d.getMonth())
      if (!b) return
      if (CLOSED.has((issue.status || '').toLowerCase().replace(/[ \-]/g, '_'))) b.closed++
      else b.opened++
    })
    return months.map(b => ({ week: b.label, opened: b.opened, closed: b.closed }))
  }

  // Weekly (default W) — last 8 ISO weeks
  const weekBuckets = new Map()
  for (let i = 7; i >= 0; i--) {
    const d = new Date(now)
    d.setDate(now.getDate() - i * 7)
    const wk = isoWeekLabel(d)
    if (!weekBuckets.has(wk)) weekBuckets.set(wk, { week: wk, opened: 0, closed: 0 })
  }
  issues.forEach(issue => {
    const raw = pickDate(issue)
    if (!raw) return
    const wk = isoWeekLabel(new Date(raw))
    if (!weekBuckets.has(wk)) return
    if (CLOSED.has((issue.status || '').toLowerCase().replace(/[ \-]/g, '_'))) weekBuckets.get(wk).closed++
    else weekBuckets.get(wk).opened++
  })
  return Array.from(weekBuckets.values())
    .map(b => ({ ...b, week: b.week.replace(/^\d{4}-/, '') })) // shorten to "W42"
}

// ─── Real checklist tag breakdown ────────────────────────────────────────────
function buildTagLevels(checklists) {
  const counts = { red: 0, yellow: 0, green: 0, blue: 0, non_critical: 0 }
  const done   = { red: 0, yellow: 0, green: 0, blue: 0, non_critical: 0 }
  checklists.forEach(c => {
    const tag = deriveChecklistTag(c)
    if (!DASHBOARD_CHECKLIST_TAG_ORDER.includes(tag)) return
    counts[tag]++
    if (isChecklistDone(c.status)) done[tag]++
  })

  return DASHBOARD_CHECKLIST_TAG_ORDER.map(tag => ({
    label: checklistTagDisplayLabel(tag),
    color: CHECKLIST_TAG_COLORS[tag],
    done: done[tag],
    total: counts[tag],
  }))

  return [
    { label: 'Level 1 — Red',    color: '#ef4444', done: done.red,    total: counts.red    },
    { label: 'Level 2 — Yellow', color: '#eab308', done: done.yellow, total: counts.yellow },
    { label: 'Level 3 — Green',  color: '#22c55e', done: done.green,  total: counts.green  },
    { label: 'Level 4 — Blue',   color: '#3b82f6', done: done.blue,   total: counts.blue   },
  ]
}

// ─── Tooltip ─────────────────────────────────────────────────────────────────
function ChartTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null
  return (
    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 9, padding: '9px 13px', fontSize: 12, boxShadow: 'var(--shadow-card)' }}>
      <div style={{ color: 'var(--text-muted)', fontWeight: 600, marginBottom: 6 }}>{label}</div>
      {payload.map((p, i) => (
        <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 2 }}>
          <span style={{ width: 8, height: 8, borderRadius: 2, background: p.fill, display: 'inline-block' }} />
          <span style={{ color: 'var(--text-secondary)' }}>{p.name}:</span>
          <span style={{ fontWeight: 700, color: 'var(--text-primary)' }}>{p.value}</span>
        </div>
      ))}
    </div>
  )
}

// ─── Main ─────────────────────────────────────────────────────────────────────
export default function DashboardPage() {
  const { activeProject, period } = useProject()
  const [loading, setLoading] = useState(true)
  const [stats,   setStats]   = useState(null)

  useEffect(() => {
    if (!activeProject) return
    setLoading(true)
    const projectId = activeProject.externalId
    Promise.all([
      issuesApi.getAll(projectId),
      tasksApi.getAll({ projectId }),
      checklistsApi.getAll(projectId),
    ])
    .then(([issuesRes, tasksRes, checklistsRes]) => {
      const issueList    = issuesRes.data?.data    || []
      const taskList     = tasksRes.data?.data     || []
      const checkList    = checklistsRes.data?.data || []

      const CLOSED_STATUSES = new Set(['closed','completed','resolved','done','issue_closed','accepted_by_owner','finished'])
      const openIssues   = issueList.filter(i => !CLOSED_STATUSES.has((i.status || '').toLowerCase().replace(/[ \-]/g, '_')))
      const closedIssues = issueList.filter(i =>  CLOSED_STATUSES.has((i.status || '').toLowerCase().replace(/[ \-]/g, '_')))

      const finishedChecklists = checkList.filter(c => isChecklistDone(c.status))
      const checklistCompletionRate = checkList.length > 0
        ? Math.round((finishedChecklists.length / checkList.length) * 1000) / 10
        : 0

      setStats({
        totalIssues:           issueList.length,
        openIssues:            openIssues.length,
        closedIssues:          closedIssues.length,
        totalTasks:            taskList.length,
        totalChecklists:       checkList.length,
        finishedChecklists:    finishedChecklists.length,
        checklistCompletionRate,
        issues:                issueList,
        checklists:            checkList,
      })
    })
    .catch(() => toast.error('Failed to load dashboard data'))
    .finally(() => setLoading(false))
  }, [activeProject])

  const weeklyData = useMemo(
    () => buildWeeklyData(stats?.issues || [], period),
    [stats, period]
  )

  const tagLevels = useMemo(
    () => buildTagLevels(stats?.checklists || []),
    [stats]
  )

  if (!activeProject) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: 300, gap: 12 }}>
        <div style={{ width: 56, height: 56, borderRadius: 16, background: 'var(--bg-card-light)', border: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Activity size={24} color="var(--text-muted)" />
        </div>
        <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)' }}>No Project Selected</div>
        <div style={{ fontSize: 13, color: 'var(--text-muted)' }}>Select a project to view its dashboard</div>
      </div>
    )
  }

  if (loading) {
    return (
      <div className="space-y-6 animate-fade-in">
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">{[...Array(4)].map((_, i) => <CardSkeleton key={i} />)}</div>
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">{[...Array(3)].map((_, i) => <CardSkeleton key={i} />)}</div>
      </div>
    )
  }

  const completionRate = stats?.checklistCompletionRate ?? 0

  // Chart Y-axis max
  const chartMax = weeklyData.length
    ? Math.max(...weeklyData.flatMap(d => [d.opened || 0, d.closed || 0]), 1)
    : 10

  return (
    <div className="space-y-6 animate-fade-in">

      {/* ── Project Header ─────────────────────────────────────────────────── */}
      <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 16, padding: '18px 22px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', boxShadow: 'var(--shadow-card)' }}>
        <div>
          <div style={{ fontSize: 10, fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.09em', marginBottom: 4 }}>Active Project</div>
          <div style={{ fontSize: 18, fontWeight: 800, color: 'var(--text-primary)', marginBottom: 6 }}>{activeProject.name}</div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <span style={{ fontSize: 11, fontWeight: 600, color: '#0ea5e9', background: 'rgba(14,165,233,0.10)', padding: '2px 9px', borderRadius: 6, fontFamily: 'monospace' }}>
              {activeProject.externalId}
            </span>
            {activeProject.status && <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>{activeProject.status}</span>}
            {activeProject.location && <span style={{ fontSize: 12, color: 'var(--text-muted)' }}>📍 {activeProject.location}</span>}
          </div>
        </div>
        <div style={{ textAlign: 'right' }}>
          <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 4 }}>Last Synced</div>
          <div style={{ fontSize: 12, color: 'var(--text-secondary)', fontFamily: 'monospace' }}>
            {activeProject.syncedAt ? new Date(activeProject.syncedAt).toLocaleString() : 'Not yet synced'}
          </div>
        </div>
      </div>

      {/* ── Overall Completion + Tag Level Donuts ──────────────────────────── */}
      <div style={{ display: 'grid', gridTemplateColumns: '2fr 3fr', gap: 16 }}>

        {/* Overall completion donut */}
        <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 16, padding: '24px 20px', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', boxShadow: 'var(--shadow-card)' }}>
          <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.09em', marginBottom: 20 }}>
            Overall Completion
          </div>
          <DonutChart
            value={stats?.finishedChecklists || 0}
            total={stats?.totalChecklists  || 1}
            color="#0ea5e9"
            size={128}
          />
          <div style={{ marginTop: 16, textAlign: 'center' }}>
            <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' }}>
              {(stats?.totalChecklists || 0) - (stats?.finishedChecklists || 0)} remaining
            </div>
            <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 2 }}>
              out of {stats?.totalChecklists || 0} checklists ·{' '}
              <span style={{ color: '#22c55e', fontWeight: 700 }}>{completionRate}%</span>
            </div>
          </div>
        </div>

        {/* Tag level donuts — real data from checklist tagLevel field */}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: 12 }}>
          {tagLevels.map(level => (
            <div key={level.label} style={{ background: 'var(--bg-card-light)', border: '1px solid var(--border)', borderRadius: 14, padding: '18px 12px', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10, boxShadow: 'var(--shadow-card)' }}>
              <DonutChart
                value={level.done}
                total={Math.max(level.total, 1)}
                color={level.color}
                size={84}
              />
              <div style={{ fontSize: 11, color: 'var(--text-muted)', textAlign: 'center', fontWeight: 500, lineHeight: 1.3 }}>
                {level.label}
              </div>
              <div style={{ fontSize: 11, color: 'var(--text-secondary)', fontWeight: 600 }}>
                {level.done} / {level.total}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* ── Summary Matrix + Bar Chart ─────────────────────────────────────── */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>

        {/* Summary Matrix */}
        <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 16, padding: '20px', boxShadow: 'var(--shadow-card)' }}>
          <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 2 }}>Summary Matrix</div>
          <div style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 18 }}>Issue tags — closed vs open</div>
          <div style={{ display: 'flex', gap: 20, alignItems: 'center' }}>
            <div style={{ flexShrink: 0 }}>
              <DonutChart
                value={stats?.closedIssues ?? 0}
                total={Math.max(stats?.totalIssues ?? 1, 1)}
                color="#22c55e"
                size={104}
                label="Closed"
              />
            </div>
            <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 8 }}>
              {[
                { label: 'TOTAL TAGS',  val: stats?.totalIssues  ?? 0, color: 'var(--text-primary)' },
                { label: 'TAGS CLOSED', val: stats?.closedIssues ?? 0, color: '#22c55e' },
                { label: 'TAGS OPEN',   val: stats?.openIssues   ?? 0, color: '#f59e0b' },
              ].map(s => (
                <div key={s.label} style={{ background: 'var(--bg-card-light)', border: '1px solid var(--border)', borderRadius: 10, padding: '10px 14px' }}>
                  <div style={{ fontSize: 9, fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.09em', marginBottom: 3 }}>{s.label}</div>
                  <div style={{ fontSize: 22, fontWeight: 800, color: s.color }}>{s.val}</div>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Weekly / Daily / Monthly Progress — real data */}
        <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 16, padding: '20px', boxShadow: 'var(--shadow-card)' }}>
          <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 2 }}>
            {period === 'D' ? 'Daily' : period === 'M' ? 'Monthly' : 'Weekly'} Issue Breakdown
          </div>
          <div style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 6 }}>
            Opened vs closed — actual data from DB
          </div>
          <div style={{ fontSize: 34, fontWeight: 800, color: 'var(--text-primary)', lineHeight: 1, marginBottom: 2 }}>
            {weeklyData.reduce((s, d) => s + (d.closed || 0), 0)}
          </div>
          <div style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 14 }}>
            Issues closed in this period
          </div>
          <ResponsiveContainer width="100%" height={110}>
            <BarChart data={weeklyData} barSize={14} barGap={3}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--divider)" vertical={false} />
              <XAxis dataKey="week" tick={{ fill: 'var(--text-muted)', fontSize: 10 }} axisLine={false} tickLine={false} />
              <YAxis tick={{ fill: 'var(--text-muted)', fontSize: 10 }} axisLine={false} tickLine={false} domain={[0, Math.ceil(chartMax * 1.2)]} width={24} />
              <Tooltip content={<ChartTooltip />} cursor={{ fill: 'var(--divider)' }} />
              <Bar dataKey="closed" name="Closed" fill="#22c55e" radius={[3, 3, 0, 0]} />
              <Bar dataKey="opened" name="Opened" fill="#f97316" radius={[3, 3, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>

      {/* ── Key Metrics ─────────────────────────────────────────────────────── */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 14 }}>
        <StatCard label="Total Issues"   value={stats?.totalIssues   ?? 0} sub="in this project" icon={AlertCircle}  color="sky"    />
        <StatCard label="Open Issues"    value={stats?.openIssues    ?? 0} sub="need attention"  icon={Activity}     color="yellow" />
        <StatCard label="Closed Issues"  value={stats?.closedIssues  ?? 0} sub="resolved"        icon={CheckCircle2} color="green"  />
        <StatCard label="Tasks"          value={stats?.totalTasks    ?? 0} sub="total tasks"     icon={Target}       color="purple" />
      </div>

      {/* ── Project Health Insight ──────────────────────────────────────────── */}
      <div style={{ background: 'var(--bg-card)', border: '1px solid rgba(234,179,8,0.22)', borderRadius: 16, padding: '18px 20px', boxShadow: 'var(--shadow-card)' }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 14 }}>
          <div style={{ width: 34, height: 34, borderRadius: 10, background: 'rgba(234,179,8,0.12)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, marginTop: 2 }}>
            <Zap size={16} color="#eab308" />
          </div>
          <div>
            <div style={{ fontSize: 10, fontWeight: 700, color: '#d97706', textTransform: 'uppercase', letterSpacing: '0.09em', marginBottom: 6 }}>Project Health Insight</div>
            <p style={{ fontSize: 14, color: 'var(--text-secondary)', lineHeight: 1.6, margin: 0 }}>
              {completionRate >= 90
                ? `Outstanding execution — ${completionRate}% checklist completion (${stats?.finishedChecklists} of ${stats?.totalChecklists}). ${stats?.openIssues} open issues remaining.`
                : completionRate >= 70
                ? `Good progress at ${completionRate}% checklist completion. Monitor open issues to stay on track.`
                : `Caution: ${stats?.openIssues} open issues require attention. Checklist completion is at ${completionRate}%.`
              }
            </p>
            <div style={{ marginTop: 10, display: 'flex', gap: 20, fontSize: 12, color: 'var(--text-muted)', flexWrap: 'wrap' }}>
              <span>Completion: <strong style={{ color: '#22c55e' }}>{completionRate}%</strong></span>
              <span>Checklists: <strong style={{ color: 'var(--text-primary)' }}>{stats?.finishedChecklists}/{stats?.totalChecklists}</strong></span>
              <span>Open Issues: <strong style={{ color: '#f59e0b' }}>{stats?.openIssues}</strong></span>
            </div>
          </div>
        </div>
      </div>

      {/* ── Issues Opened vs Closed — full-width trend ─────────────────────── */}
      <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 16, padding: '20px', boxShadow: 'var(--shadow-card)' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 18 }}>
          <div>
            <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 2 }}>Issues Opened vs Closed</div>
            <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>
              {period === 'D' ? 'Daily' : period === 'M' ? 'Monthly' : 'Weekly'} trend — real data
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 14, fontSize: 12, color: 'var(--text-muted)' }}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
              <span style={{ width: 10, height: 10, borderRadius: 2, background: '#22c55e', display: 'inline-block' }} />Closed
            </span>
            <span style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
              <span style={{ width: 10, height: 10, borderRadius: 2, background: '#f97316', display: 'inline-block' }} />Opened
            </span>
          </div>
        </div>
        <ResponsiveContainer width="100%" height={200}>
          <BarChart data={weeklyData} barGap={4} barSize={18}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--divider)" />
            <XAxis dataKey="week" tick={{ fill: 'var(--text-muted)', fontSize: 11 }} axisLine={false} tickLine={false} />
            <YAxis tick={{ fill: 'var(--text-muted)', fontSize: 11 }} axisLine={false} tickLine={false} width={28} />
            <Tooltip content={<ChartTooltip />} cursor={{ fill: 'var(--divider)' }} />
            <Bar dataKey="closed" name="Closed" fill="#22c55e" radius={[4, 4, 0, 0]} />
            <Bar dataKey="opened" name="Opened" fill="#f97316" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>

    </div>
  )
}
