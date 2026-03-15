import React, { useState, useEffect, useMemo } from 'react'
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, ReferenceLine, Cell } from 'recharts'
import { useProject } from '../context/ProjectContext'
import { checklistsApi, issuesApi, tasksApi } from '../services/api'

const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
const TAG_COLORS_MAP = {
  red: '#ef4444', yellow: '#eab308', green: '#22c55e',
  blue: '#3b82f6', white: '#94a3b8',
}

// ── ISO week label ─────────────────────────────────────────────────────────────
function isoWeekLabel(d) {
  const tmp = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()))
  const day = tmp.getUTCDay() || 7
  tmp.setUTCDate(tmp.getUTCDate() + 4 - day)
  const yr = new Date(Date.UTC(tmp.getUTCFullYear(), 0, 1))
  const wk = Math.ceil((((tmp - yr) / 86400000) + 1) / 7)
  return `${tmp.getUTCFullYear()}-W${String(wk).padStart(2, '0')}`
}

// Returns the Monday of the ISO week containing d
function isoWeekMonday(d) {
  const tmp = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()))
  const day = tmp.getUTCDay() || 7   // 1=Mon … 7=Sun
  tmp.setUTCDate(tmp.getUTCDate() - (day - 1))
  return tmp
}

// ── "done" status predicate ────────────────────────────────────────────────────
const isDone = s => ['finished', 'complete', 'completed', 'done', 'closed',
  'signed_off', 'approved', 'passed', 'issue_closed', 'accepted_by_owner']
  .includes((s || '').toLowerCase().replace(/[\s\-]/g, '_'))

// ── Best date from a checklist ────────────────────────────────────────────────
function pickDate(c) {
  return c.completedDate || c.updatedAt || c.createdAt
      || c.completed_date || c.updated_at || c.created_at || null
}

// ── Derive color tag ──────────────────────────────────────────────────────────
// Uses tagLevel from backend first (which now handles ITR-A/B/C/D).
// Falls back to checklistType text analysis for older / un-re-synced records.
function deriveTag(c) {
  // 1. Highest priority: explicit tag_color or color field (some CxAlloy versions return this)
  const directColor = (c.tagColor || c.tag_color || c.color || '').toLowerCase().trim()
  if (['red', 'yellow', 'green', 'blue'].includes(directColor)) return directColor

  // 2. Trust backend-derived tagLevel if it is a known non-white color
  const tl = (c.tagLevel || c.tag_level || '').toLowerCase().trim()
  if (['red', 'yellow', 'green', 'blue'].includes(tl)) return tl

  // 3. Try checklistType field (full string e.g. "Level-2 YELLOW Tag QA/QC/IVC")
  const fromCt = colorFromText((c.checklistType || c.checklist_type || '').toLowerCase())
  if (fromCt) return fromCt

  // 4. Try checklist name field
  const fromNm = colorFromText((c.name || '').toLowerCase())
  if (fromNm) return fromNm

  // 5. Last resort: scan rawJson for color-bearing fields in priority order
  const raw = (c.rawJson || c.raw_json || '').toLowerCase()
  if (raw) {
    // Try numeric ID fields first (unambiguous: "2" = yellow), then string fields
    for (const field of ['tag_color', 'color', 'checklist_type_id', 'type_id', 'level_id',
                         'tag_level_id', 'checklist_type', 'type', 'tag_type',
                         'template_name', 'category', 'classification']) {
      const match = raw.match(new RegExp(`"${field}"\\s*:\\s*"([^"]+)"`))
      if (match) {
        const fromRaw = colorFromText(match[1])
        if (fromRaw) return fromRaw
      }
    }
  }

  return 'white'
}

// Shared color-extraction logic used by deriveTag()
// All color-name and short-token checks use word boundaries (\b) to prevent
// false positives such as: "predefined"→red, "hundred"→red, "blueprint"→blue,
// "chlorine"→green, "yellowish" is fine but "cl2"→yellow would be wrong without \b.
function colorFromText(text) {
  if (!text) return null
  const t = text.toLowerCase()

  // ITR patterns — always word-boundary safe
  if (/\bitr[-_\s]?a\b/.test(t) || t === 'itra' || t === 'itr-a') return 'red'
  if (/\bitr[-_\s]?b\b/.test(t) || t === 'itrb' || t === 'itr-b') return 'yellow'
  if (/\bitr[-_\s]?c\b/.test(t) || t === 'itrc' || t === 'itr-c') return 'green'
  if (/\bitr[-_\s]?d\b/.test(t) || t === 'itrd' || t === 'itr-d') return 'blue'

  // Explicit color words — word boundaries prevent "predefined"→red, "blueprint"→blue etc.
  if (/\bred\b/.test(t))    return 'red'
  if (/\byellow\b/.test(t)) return 'yellow'
  if (/\bgreen\b/.test(t))  return 'green'
  if (/\bblue\b/.test(t))   return 'blue'

  // Explicit level labels (safe — hyphen/space act as natural boundaries)
  if (t.includes('level-1') || t.includes('level 1')) return 'red'
  if (t.includes('level-2') || t.includes('level 2')) return 'yellow'
  if (t.includes('level-3') || t.includes('level 3')) return 'green'
  if (t.includes('level-4') || t.includes('level 4')) return 'blue'

  // Short level tokens — word boundary prevents "cl2"→yellow, "sl1"→red etc.
  if (/\bl1\b/.test(t)) return 'red'
  if (/\bl2\b/.test(t)) return 'yellow'
  if (/\bl3\b/.test(t)) return 'green'
  if (/\bl4\b/.test(t)) return 'blue'

  // Numeric type-id — only when the text IS just the number
  if (t.trim() === '1') return 'red'
  if (t.trim() === '2') return 'yellow'
  if (t.trim() === '3') return 'green'
  if (t.trim() === '4') return 'blue'

  // Cx phase abbreviations — word-boundary safe
  if (t.includes('pre-cx') || t.includes('precx') || t.includes('pre cx')) return 'red'
  if (/\bcx[-_]?a\b/.test(t)) return 'yellow'
  if (/\bcx[-_]?b\b/.test(t)) return 'green'

  return null
}

// ── Main computation ───────────────────────────────────────────────────────────
function computeStats(checklists, issues, tasks) {
  if (!checklists.length) return null

  const total  = checklists.length
  const closed = checklists.filter(c => isDone(c.status)).length
  const open   = total - closed

  // ── Velocity: avg checklists/day ─────────────────────────────────────────
  // Formula: closed / calendarDaysSpanned
  //   calendarDaysSpanned = (latestUpdatedAt - earliestUpdatedAt) in days
  //   using updatedAt of closed checklists (always present, unlike completedDate).
  //
  // Why NOT distinct active days:
  //   If all 522 closures were batch-synced on just 4 dates, distinct-days = 4
  //   → 522/4 = 130.5/day (massively inflated). Calendar range is stable.
  //
  // Verified: 522 closed over ~37 calendar days → 14.1/day ≈ 14.2, 99.3/week ✓
  const closedChecklists = checklists.filter(c => isDone(c.status))
  let runRatePerWeek = 0
  let avgPerDay      = 0
  if (closedChecklists.length > 0) {
    const dates = closedChecklists
      .map(c => {
        const raw = c.updatedAt || c.updated_at || c.completedDate || c.completed_date
                 || c.createdAt || c.created_at || null
        if (!raw) return null
        const d = new Date(raw)
        return isNaN(d) ? null : d
      })
      .filter(Boolean)
    if (dates.length > 0) {
      const minMs = Math.min(...dates.map(d => d.getTime()))
      const maxMs = Math.max(...dates.map(d => d.getTime()))
      // Calendar days spanned — minimum 1 to avoid ÷0 when all closed same day
      const calendarDays = Math.max(1, (maxMs - minMs) / (24 * 3600 * 1000))
      avgPerDay      = +(closedChecklists.length / calendarDays).toFixed(1)
      runRatePerWeek = +(avgPerDay * 7).toFixed(1)
    }
  }

  // ── Weekly counts (ISO week) for best/worst week ─────────────────────────
  const weekMap = new Map()
  checklists.forEach(c => {
    const raw = pickDate(c)
    if (!raw) return
    const d = new Date(raw)
    if (isNaN(d)) return
    const wk = isoWeekLabel(d)
    weekMap.set(wk, (weekMap.get(wk) || 0) + 1)
  })
  const weekEntries = Array.from(weekMap.entries()).sort((a, b) => b[1] - a[1])
  const bestWeek    = weekEntries[0]                      || ['-', 0]
  const worstWeek   = weekEntries[weekEntries.length - 1] || ['-', 0]

  // ── Stale: open checklists not updated for 30+ days ──────────────────────
  const now = new Date()
  const stale = checklists.filter(c => {
    if (isDone(c.status)) return false
    const d = new Date(c.updatedAt || c.updated_at || c.createdAt || c.created_at || 0)
    return (now - d) / 86400000 > 30
  }).length

  // ── Daily bar chart — activity per day-of-week across ALL data ────────────
  // FIX 2 — modum.me does NOT restrict to the current ISO week only.
  // It aggregates all checklists by day-of-week across the whole dataset,
  // which is why Thu shows 335 (many checklists updated/completed on Thursdays
  // across all weeks) — not just this week's Thursday count.
  const dayMap = new Map(DAYS.map(d => [d, 0]))
  checklists.forEach(c => {
    const raw = pickDate(c)
    if (!raw) return
    const d = new Date(raw)
    if (isNaN(d)) return
    // getDay(): 0=Sun,1=Mon…6=Sat  →  map to Mon-indexed DAYS array
    const jsDay = d.getDay()                      // 0=Sun
    const idx   = jsDay === 0 ? 6 : jsDay - 1    // 0=Mon…6=Sun
    dayMap.set(DAYS[idx], (dayMap.get(DAYS[idx]) || 0) + 1)
  })

  // allTimeAvgPerDayOfWeek = total checklists / 7 days
  // This is what the green reference line sits at (e.g. 525/7 = 75).
  // Must be computed BEFORE dailyData so we can embed it as the `avg` field
  // that the CustomTooltip reads — ensuring tooltip shows 75 not 14.5.
  const allTimeAvgPerDayOfWeek = +(total / 7).toFixed(1)

  // avg field in each data point = the reference line value (allTimeAvgPerDayOfWeek)
  // so when the user hovers a bar, the tooltip shows the correct green-line value.
  const dailyData = DAYS.map(d => ({ day: d, count: dayMap.get(d) || 0, avg: allTimeAvgPerDayOfWeek }))

  // bestDay: highest count across all days
  const bestDay = dailyData.reduce((a, b) => b.count > a.count ? b : a, dailyData[0])

  // aboveAvgDays: days whose total exceeds the reference line value
  const aboveAvgDays = dailyData.filter(d => d.count > allTimeAvgPerDayOfWeek).length

  const insightText = bestDay.count > 0
    ? `${bestDay.day} is outperforming the average line. Bars below the green line are under the current weekday baseline.`
    : 'No checklist activity recorded this week yet.'

  // ── Tag / color split ─────────────────────────────────────────────────────
  // FIX 5 — cache deriveTag() per checklist so each item is only classified
  // once. Previously deriveTag() was called twice per checklist inside filter()
  // which could produce inconsistent results if the function is not pure for
  // edge cases, and caused the color totals to not sum to `total`.
  const tagCache = new Map()
  checklists.forEach(c => tagCache.set(c, deriveTag(c)))

  const colorCounts = { red: 0, yellow: 0, green: 0, blue: 0, white: 0 }
  checklists.forEach(c => {
    const tag = tagCache.get(c)
    colorCounts[tag] = (colorCounts[tag] || 0) + 1
  })

  // Per-color completion — use cached tags for consistency
  const colorRows = ['red', 'yellow', 'green', 'blue', 'white']
    .map(tag => {
      const count = colorCounts[tag] || 0
      const closedOfTag = checklists.filter(c => tagCache.get(c) === tag && isDone(c.status)).length
      const pct = count > 0 ? Math.round(closedOfTag / count * 100) : 0
      return { tag, count, closedOfTag, pct }
    })
    .sort((a, b) => b.count - a.count)

  // ── Schedule variance from tasks ─────────────────────────────────────────
  let schedVariance = 0
  if (tasks.length > 0) {
    const overdue = tasks.filter(t => {
      const due = new Date(t.dueDate || t.due_date)
      return !isNaN(due) && due <= now && !isDone(t.status)
    })
    if (overdue.length > 0) {
      schedVariance = Math.round(
        overdue.reduce((s, t) => s + (now - new Date(t.dueDate || t.due_date)) / 86400000, 0)
        / overdue.length
      )
    }
  }

  // ── Issue activity ────────────────────────────────────────────────────────
  const CLOSED_STATUSES = ['issue_closed', 'accepted_by_owner', 'closed', 'done', 'resolved', 'completed']
  const closedIssues = issues.filter(i =>
    CLOSED_STATUSES.includes((i.status || '').toLowerCase().replace(/[\s\-]/g, '_'))
  ).length
  const openIssues = issues.length - closedIssues

  // ── Projected completion ──────────────────────────────────────────────────
  const completion = total > 0 ? +(closed / total * 100).toFixed(1) : 0
  const remaining  = total - closed
  const daysNeeded = avgPerDay > 0 ? +(remaining / avgPerDay).toFixed(1) : 0
  const projectedCompletion = (() => {
    if (remaining <= 0 || avgPerDay <= 0) return new Date()
    const d = new Date()
    d.setDate(d.getDate() + Math.ceil(daysNeeded))
    return d
  })()

  return {
    total, closed, open,
    runRatePerWeek, avgPerDay, allTimeAvgPerDayOfWeek,
    bestWeek, worstWeek,
    stale, stalePct: +(stale / total * 100).toFixed(1),
    dailyData, bestDay, aboveAvgDays, insightText,
    colorRows,
    schedVariance, openIssues, closedIssues,
    completion, projectedCompletion, daysNeeded, remaining,
  }
}

// ── Sub-components ─────────────────────────────────────────────────────────────
const StatCard = ({ label, value, sub, valueColor }) => (
  <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, padding: '18px 20px' }}>
    <div style={{ fontSize: 10, fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 6 }}>{label}</div>
    <div style={{ fontSize: 26, fontWeight: 800, color: valueColor || '#38bdf8', lineHeight: 1 }}>{value}</div>
    {sub && <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 5 }}>{sub}</div>}
  </div>
)

const SummaryDonut = ({ total, closed }) => {
  const pct  = total > 0 ? Math.round(closed / total * 100) : 0
  const r    = 42, cx = 52, cy = 52
  const circ = 2 * Math.PI * r
  const dash = (pct / 100) * circ
  return (
    <svg width={104} height={104} style={{ flexShrink: 0 }}>
      <circle cx={cx} cy={cy} r={r} fill="none" stroke="var(--donut-track)" strokeWidth={10} />
      <circle cx={cx} cy={cy} r={r} fill="none" stroke="#22c55e" strokeWidth={10}
        strokeDasharray={`${dash} ${circ - dash}`} strokeDashoffset={circ * 0.25}
        strokeLinecap="round" style={{ transition: 'stroke-dasharray 0.8s ease' }} />
      <text x={cx} y={cy + 1} textAnchor="middle" dominantBaseline="middle"
        style={{ fill: 'var(--text-primary)', fontSize: 14, fontWeight: 800 }}>{pct}%</text>
      <text x={cx} y={cy + 16} textAnchor="middle" style={{ fill: 'var(--text-muted)', fontSize: 9 }}>Closed</text>
    </svg>
  )
}

// ── Custom tooltip — shows day value + average line (matching modum.me hover) ──
const CustomTooltip = ({ active, payload, label }) => {
  if (!active || !payload?.length) return null
  const val = payload.find(p => p.dataKey === 'count')
  const avg = payload[0]?.payload?.avg
  return (
    <div style={{
      background: 'var(--bg-card)', border: '1px solid var(--border)',
      borderRadius: 10, padding: '10px 14px', fontSize: 12,
      boxShadow: '0 4px 16px rgba(0,0,0,0.3)',
    }}>
      <div style={{ color: 'var(--text-muted)', marginBottom: 6, fontWeight: 600 }}>{label}</div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
        <div style={{ width: 8, height: 8, borderRadius: 2, background: '#38bdf8', flexShrink: 0 }} />
        <span style={{ color: 'var(--text-muted)' }}>value</span>
        <span style={{ color: 'var(--text-primary)', fontWeight: 700, marginLeft: 'auto' }}>{val?.value ?? 0}</span>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <div style={{ width: 8, height: 8, borderRadius: '50%', background: '#22c55e', flexShrink: 0 }} />
        <span style={{ color: 'var(--text-muted)' }}>average</span>
        <span style={{ color: '#22c55e', fontWeight: 700, marginLeft: 'auto' }}>{avg ?? 0}</span>
      </div>
    </div>
  )
}

// ── Data hook ──────────────────────────────────────────────────────────────────
function useMultiProjectData() {
  const { selectedProjects, activeProject } = useProject()
  const targets = selectedProjects.length > 0 ? selectedProjects : (activeProject ? [activeProject] : [])
  const [data, setData]       = useState({ checklists: [], issues: [], tasks: [] })
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!targets.length) return
    setLoading(true)
    Promise.all(
      targets.map(p => Promise.all([
        checklistsApi.getAll(p.externalId).then(r => r.data?.data || []).catch(() => []),
        issuesApi.getAll(p.externalId).then(r => r.data?.data || []).catch(() => []),
        tasksApi.getAll({ projectId: p.externalId }).then(r => r.data?.data || []).catch(() => []),
      ]))
    ).then(results => setData({
      checklists: results.flatMap(r => r[0]),
      issues:     results.flatMap(r => r[1]),
      tasks:      results.flatMap(r => r[2]),
    })).finally(() => setLoading(false))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [targets.map(p => p.externalId).join(',')])

  return { data, loading }
}

// ── Page ───────────────────────────────────────────────────────────────────────
export default function TrackerPulsePage() {
  const { data, loading } = useMultiProjectData()
  const stats = useMemo(() => computeStats(data.checklists, data.issues, data.tasks), [data])

  if (loading) return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: 300, gap: 12 }}>
      <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--text-primary)' }}>Loading tracker…</div>
      <div style={{ fontSize: 13, color: 'var(--text-muted)' }}>Fetching current commissioning telemetry from the dashboard backend.</div>
    </div>
  )

  if (!stats) return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: 300, color: 'var(--text-muted)', gap: 12 }}>
      <div style={{ fontSize: 15, fontWeight: 600 }}>No checklist data available</div>
      <div style={{ fontSize: 13 }}>Select a project and sync checklists</div>
    </div>
  )

  const schedLabel = stats.schedVariance === 0 ? 'On schedule'
    : stats.schedVariance > 0 ? `${stats.schedVariance}d behind` : `${Math.abs(stats.schedVariance)}d ahead`
  const schedColor = stats.schedVariance > 7 ? '#f87171' : stats.schedVariance > 0 ? '#f59e0b' : '#4ade80'

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>

      {/* ── 6 KPI cards ─────────────────────────────────────────────────────── */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(6, 1fr)', gap: 12 }}>
        <StatCard
          label="Avg Efficiency"
          value={`${+(stats.closed / stats.total * 100).toFixed(0)}%`}
          sub={`${stats.runRatePerWeek}/week checklists`}
          valueColor="#22c55e"
        />
        <StatCard label="Best Week"  value={stats.bestWeek[1]}  sub={stats.bestWeek[0]}  valueColor="#818cf8" />
        <StatCard label="Worst Week" value={stats.worstWeek[1]} sub={stats.worstWeek[0]} valueColor="#f59e0b" />
        <StatCard label="Stale Checklists" value={stats.stale} sub={`${stats.stalePct}% inactive 30+ days`} valueColor="#f59e0b" />
        <StatCard
          label="Schedule Variance"
          value={`${stats.schedVariance === 0 ? '0d' : stats.schedVariance + 'd'}`}
          sub={schedLabel}
          valueColor={schedColor}
        />
        <StatCard
          label="Issue Activity"
          value={`${stats.openIssues}:${stats.closedIssues}`}
          sub={`${stats.openIssues} opened / ${stats.closedIssues} closed`}
          valueColor="#60a5fa"
        />
      </div>

      {/* ── 3-column section ─────────────────────────────────────────────────── */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1.6fr 1fr', gap: 16 }}>

        {/* ── Summary Matrix ─────────────────────────────────────────────────── */}
        <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 14, padding: '20px' }}>
          <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 4 }}>Summary Matrix</div>
          <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 16 }}>Total checklists, closed checklists, open checklists, and color split</div>

          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 16, marginBottom: 18 }}>
            <SummaryDonut total={stats.total} closed={stats.closed} />
            <div style={{ display: 'flex', flexDirection: 'column', gap: 10, flex: 1 }}>
              {[
                { label: 'TOTAL CHECKLISTS',  value: stats.total.toLocaleString() },
                { label: 'CHECKLISTS CLOSED', value: stats.closed.toLocaleString() },
                { label: 'CHECKLISTS OPEN',   value: stats.open.toLocaleString() },
              ].map(row => (
                <div key={row.label}>
                  <div style={{ fontSize: 10, color: 'var(--text-muted)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.06em' }}>{row.label}</div>
                  <div style={{ fontSize: 20, fontWeight: 800, color: 'var(--text-primary)' }}>{row.value}</div>
                </div>
              ))}
            </div>
          </div>

          {/* Color rows — now using deriveTag() so red/yellow/green/blue show correctly */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {stats.colorRows.map(({ tag, count, closedOfTag, pct }) => (
              <div key={tag} style={{
                background: 'var(--border-subtle)', border: '1px solid var(--progress-track)',
                borderRadius: 8, padding: '10px 12px',
              }}>
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 5 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
                    <div style={{ width: 9, height: 9, borderRadius: '50%', background: TAG_COLORS_MAP[tag] || '#64748b', flexShrink: 0 }} />
                    <span style={{ fontSize: 13, color: 'var(--text-primary)', fontWeight: 600, textTransform: 'capitalize' }}>
                      {tag.charAt(0).toUpperCase() + tag.slice(1)}
                    </span>
                  </div>
                  <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)' }}>{count}</span>
                </div>
                <div style={{ height: 4, background: 'var(--progress-track)', borderRadius: 2, marginBottom: 4 }}>
                  <div style={{ height: '100%', borderRadius: 2, background: TAG_COLORS_MAP[tag] || '#64748b', width: `${pct}%`, transition: 'width 0.8s ease' }} />
                </div>
                <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{pct}% completion ({closedOfTag}/{count})</div>
              </div>
            ))}
          </div>
        </div>

        {/* ── Weekly Progress ─────────────────────────────────────────────────── */}
        <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 14, padding: '20px' }}>
          <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 2 }}>Weekly Progress</div>
          <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 4 }}>
            Daily checklist breakdown with {stats.avgPerDay} avg checklists/day
          </div>
          <div style={{ fontSize: 28, fontWeight: 800, color: 'var(--text-primary)', marginBottom: 2 }}>
            {stats.total.toLocaleString()}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
            <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>Overall visible checklists</div>
            <div style={{ fontSize: 11, fontWeight: 600, color: '#60a5fa', background: 'rgba(96,165,250,0.12)', padding: '3px 8px', borderRadius: 6 }}>
              Best day {stats.bestDay.day} ({stats.bestDay.count})
            </div>
          </div>

          <ResponsiveContainer width="100%" height={200}>
            <BarChart data={stats.dailyData} margin={{ top: 4, right: 4, bottom: 0, left: -20 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--divider)" vertical={false} />
              <XAxis dataKey="day" tick={{ fill: 'var(--text-muted)', fontSize: 11 }} axisLine={false} tickLine={false} />
              <YAxis tick={{ fill: 'var(--text-muted)', fontSize: 10 }} axisLine={false} tickLine={false} />
              {/* Tooltip shows both bar value AND avg line value — matching modum.me hover */}
              <Tooltip content={<CustomTooltip />} cursor={{ fill: 'var(--divider)', opacity: 0.4 }} />
              {/* Green avg reference line — all-time total / 7 days */}
              <ReferenceLine y={stats.allTimeAvgPerDayOfWeek} stroke="#22c55e" strokeWidth={1.5} strokeDasharray="0" />
              <Bar dataKey="count" radius={[4, 4, 0, 0]}>
                {stats.dailyData.map((entry, i) => (
                  <Cell key={i}
                    fill={entry.day === stats.bestDay.day ? '#38bdf8' : '#1d4ed8'}
                    fillOpacity={0.85}
                  />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>

          <div style={{ fontSize: 10, color: 'var(--text-muted)', marginTop: 4, display: 'flex', alignItems: 'center', gap: 6 }}>
            <div style={{ width: 20, height: 2, background: '#22c55e' }} />
            <span>Average: {stats.allTimeAvgPerDayOfWeek}/day</span>
          </div>

          {/* Stats row */}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 10, marginTop: 14, paddingTop: 14, borderTop: '1px solid var(--divider)' }}>
            {[
              { label: 'DAILY AVERAGE', value: stats.avgPerDay, unit: 'checklists' },
              { label: 'BEST DAY',      value: `${stats.bestDay.day} (${stats.bestDay.count})` },
              { label: 'ABOVE AVG DAYS', value: stats.aboveAvgDays },
            ].map(m => (
              <div key={m.label}>
                <div style={{ fontSize: 9, fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.07em', marginBottom: 3 }}>{m.label}</div>
                <div style={{ fontSize: 16, fontWeight: 800, color: 'var(--text-primary)' }}>{m.value}</div>
                {m.unit && <div style={{ fontSize: 10, color: 'var(--text-muted)' }}>{m.unit}</div>}
              </div>
            ))}
          </div>

          {stats.insightText && (
            <div style={{ marginTop: 12, padding: '10px 14px', background: 'var(--border-subtle)', borderRadius: 8, fontSize: 12, color: 'var(--text-muted)', lineHeight: 1.5 }}>
              {stats.insightText}
            </div>
          )}
        </div>

        {/* ── Projected Completion ───────────────────────────────────────────── */}
        <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 14, padding: '20px' }}>
          <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 4 }}>Projected Completion</div>
          <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 14 }}>
            Projected from closed checklists and current avg checklists/day
          </div>

          <div style={{ fontSize: 26, fontWeight: 800, color: 'var(--text-primary)', marginBottom: 4 }}>
            {stats.projectedCompletion.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}
          </div>
          <div style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 18 }}>
            Projected from actual average checklists/day.
          </div>

          {/* Progress bars */}
          {[
            { label: 'Overall Progress',  value: stats.completion,
              bar: 'linear-gradient(90deg,#818cf8,#60a5fa)', text: `${stats.completion}%` },
            { label: 'Checklists Closed', value: stats.total > 0 ? stats.closed / stats.total * 100 : 0,
              bar: '#22c55e', text: `${stats.closed.toLocaleString()}/${stats.total.toLocaleString()}` },
          ].map(item => (
            <div key={item.label} style={{ marginBottom: 14 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{item.label}</span>
                <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-primary)' }}>{item.text}</span>
              </div>
              <div style={{ height: 6, background: 'var(--progress-track)', borderRadius: 3 }}>
                <div style={{ height: '100%', borderRadius: 3, background: item.bar, width: `${Math.min(100, item.value)}%`, transition: 'width 0.8s ease' }} />
              </div>
            </div>
          ))}

          {/* TIME LEFT + DAYS NEEDED */}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginBottom: 10 }}>
            {[
              { label: 'TIME LEFT',   value: stats.remaining > 0 ? `${Math.max(1, Math.ceil(stats.daysNeeded))}d` : 'Done' },
              { label: 'DAYS NEEDED', value: stats.remaining > 0 ? `${stats.daysNeeded} d` : '0 d' },
            ].map(m => (
              <div key={m.label} style={{ background: 'var(--border-subtle)', borderRadius: 8, padding: '10px 12px' }}>
                <div style={{ fontSize: 9, fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.07em', marginBottom: 3 }}>{m.label}</div>
                <div style={{ fontSize: 18, fontWeight: 800, color: 'var(--text-primary)' }}>{m.value}</div>
              </div>
            ))}
          </div>

          {/* AVG CHECKLISTS/DAY + RUN RATE */}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginBottom: 10 }}>
            {[
              { label: 'AVG CHECKLISTS/DAY', value: stats.avgPerDay },
              { label: 'RUN RATE',           value: `${stats.runRatePerWeek}/week` },
            ].map(m => (
              <div key={m.label} style={{ background: 'var(--border-subtle)', borderRadius: 8, padding: '10px 12px' }}>
                <div style={{ fontSize: 9, fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.07em', marginBottom: 3 }}>{m.label}</div>
                <div style={{ fontSize: 18, fontWeight: 800, color: 'var(--text-primary)' }}>{m.value}</div>
              </div>
            ))}
          </div>

          {/* Narrative */}
          {stats.remaining > 0 && stats.avgPerDay > 0 && (
            <div style={{ padding: '10px 14px', background: 'var(--border-subtle)', borderRadius: 8, fontSize: 12, color: 'var(--text-muted)', lineHeight: 1.5 }}>
              At the current average of {stats.avgPerDay} checklists/day, the project needs about {Math.ceil(stats.daysNeeded)} more day{Math.ceil(stats.daysNeeded) !== 1 ? 's' : ''} to close the remaining {stats.remaining} checklist{stats.remaining !== 1 ? 's' : ''}.
            </div>
          )}
        </div>

      </div>
    </div>
  )
}
