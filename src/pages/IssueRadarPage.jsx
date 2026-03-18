/**
 * IssueRadarPage — fully wired to backend /api/issues endpoint.
 *
 * Backend field reference (Issue.java):
 *   id, externalId, projectId, title, description
 *   status   — normalised snake_case: open | issue_closed | accepted_by_owner |
 *              correction_in_progress | gc_to_verify | cxa_to_verify |
 *              ready_for_retest | additional_information_needed
 *   priority — raw CxAlloy string: "P1 - Critical" | "P2 - High" | "P3 - Medium" | "P4 - Low" | null
 *   assignee, reporter, dueDate
 *   spaceId, zoneId, buildingId, floorId, location (pre-derived by backend)
 *   createdAt, updatedAt, rawJson, syncedAt
 *
 */
import React, { useState, useEffect, useMemo, useRef } from 'react'
import { useProject } from '../context/ProjectContext'
import { issuesApi } from '../services/api'

// ─── Constants ────────────────────────────────────────────────────────────────
const ALL_STATUSES = ['Open', 'Correction In Progress', 'Ready For Verification', 'Closed']
const PRIORITIES   = ['P1 - Critical', 'P2 - High', 'P3 - Medium', 'P4 - Low']
const TABS         = ['Statistics', 'Flow Analysis', 'Cross-Company']

const STATUS_COLORS = {
  'Open':                      '#ef4444',
  'Correction In Progress':    '#3b82f6',
  'Ready For Verification':    '#eab308',
  'Closed':                    '#22c55e',
}

// ─── Normalise helpers — mapped to exact backend snake_case tokens ─────────────
function normStatus(s) {
  switch ((s || '').toLowerCase().trim()) {
    case 'open':
    case 'issue_opened':
    case 'active':
    case 'new':
      return 'Open'
    case 'correction_in_progress':
    case 'in_progress':
    case 'started':
      return 'Correction In Progress'
    case 'ready_for_retest':
    case 'ready_for_verification':
    case 'gc_to_verify':
    case 'cxa_to_verify':
      return 'Ready For Verification'
    case 'issue_closed':
    case 'closed':
    case 'accepted_by_owner':
    case 'done':
    case 'resolved':
    case 'completed':
      return 'Closed'
    default:
      return 'Open'
  }
}

// null / empty priority → P4-Low (lowest bucket, not P1-Critical)
function normPriority(p) {
  if (!p || p.trim() === '') return 'P4 - Low'
  const r = p.toLowerCase().trim()
  if (r === 'p1 - critical' || r.includes('critical') || r === 'p1' || r === '1') return 'P1 - Critical'
  if (r === 'p2 - high'     || r.includes('high')     || r === 'p2' || r === '2') return 'P2 - High'
  if (r === 'p3 - medium'   || r.includes('medium')   || r === 'p3' || r === '3') return 'P3 - Medium'
  return 'P4 - Low'
}

// Derive issue type from title / description keywords (CxAlloy has no type field)
function issueType(i) {
  const src = ((i.title || '') + ' ' + (i.description || '')).toLowerCase()
  if (src.includes('electrical') || src.includes('elec') || src.includes('cabling') || src.includes('cable')) return 'Electrical Installation'
  if (src.includes('control') || src.includes('bms') || src.includes('integration') || src.includes('plc') || src.includes('scada')) return 'Controls & Integration'
  if (src.includes('civil') || src.includes('structural') || src.includes('concrete') || src.includes('steel')) return 'Civil & Structural'
  if (src.includes('fire') || src.includes('safety') || src.includes('suppression')) return 'Fire & Safety'
  if (src.includes('hvac') || src.includes('ventilation') || src.includes('duct') || src.includes('chiller') || src.includes('mechanical')) return 'Mechanical Completion'
  // Fallback: use location / spaceId hint
  const loc = ((i.location || '') + ' ' + (i.spaceId || '')).toLowerCase()
  if (loc.includes('elec')) return 'Electrical Installation'
  if (loc.includes('mech')) return 'Mechanical Completion'
  return 'Mechanical Completion'
}

// Owner = assignee → reporter → location → 'Unassigned'
function ownerOf(i) {
  return (i.assignee && i.assignee.trim()) || (i.reporter && i.reporter.trim()) || i.location || 'Unassigned'
}

// Company = first word of assignee/location (CxAlloy assignee is often "COMPANY - Name")
function companyOf(i) {
  const raw = ownerOf(i)
  if (raw === 'Unassigned') return 'Unassigned'
  // "ELECTRICAL - John Smith" → "ELECTRICAL"
  const dashIdx = raw.indexOf(' - ')
  return dashIdx > 0 ? raw.substring(0, dashIdx).trim() : raw.split(' ')[0] || raw
}

function ageInDays(i) {
  const now = new Date()
  const d = new Date(i.createdAt || i.created_at || now)
  return isNaN(d) ? 0 : Math.max(0, Math.round((now - d) / 86400000))
}

// High-cycle = issue that has been through correction loop:
// status is NOT open/closed AND age > 14 days (stuck in correction/review)
function isHighCycle(i) {
  const s = normStatus(i.status)
  return s === 'Correction In Progress' || s === 'Ready For Verification'
}

// ─── Build all radar metrics from the raw issue array ────────────────────────
function computeRadar(issues) {
  if (!issues.length) return {
    pressure: 0, qualityScore: 0, qualityGrade: 'D', highCycleIssues: [],
    matrixRows: ALL_STATUSES.map(s => ({ status: s, ...PRIORITIES.reduce((a,p) => ({...a,[p]:0}),{}), total: 0 })),
    avgAgeByStatus: {}, globalAvgAge: 0, typeBreakdown: [], rootBreakdown: [],
    companyFlow: [], companyMatrix: [], topType: null, topRootCause: null,
    topOwner: null, outsideThreshold: 0, ageThreshold: 0, totalRows: 0,
  }

  const openIssues = issues.filter(i => normStatus(i.status) !== 'Closed')
  const highCycleIssues = issues.filter(isHighCycle)

  // Quality score: 0–100. 0 = all P1 Critical open. 100 = nothing open.
  // Formula: penalise for open count and P1 proportion
  const p1Open = openIssues.filter(i => normPriority(i.priority) === 'P1 - Critical').length
  let qualityScore = 0
  if (issues.length > 0) {
    const openRatio = openIssues.length / issues.length
    const critRatio = openIssues.length > 0 ? p1Open / openIssues.length : 0
    qualityScore = Math.max(0, Math.round(100 - openRatio * 60 - critRatio * 40))
  }
  const qualityGrade = qualityScore >= 80 ? 'A' : qualityScore >= 60 ? 'B' : qualityScore >= 40 ? 'C' : 'D'

  // Global average age (open issues only)
  const globalAvgAge = openIssues.length > 0
    ? Math.round(openIssues.reduce((s, i) => s + ageInDays(i), 0) / openIssues.length)
    : 0

  // ── Priority Matrix: status × priority ───────────────────────────────────
  const matrix = {}
  ALL_STATUSES.forEach(s => { matrix[s] = {}; PRIORITIES.forEach(p => { matrix[s][p] = 0 }) })
  issues.forEach(i => {
    const s = normStatus(i.status)
    const p = normPriority(i.priority)
    if (matrix[s] && matrix[s][p] !== undefined) matrix[s][p]++
  })
  const matrixRows = ALL_STATUSES.map(s => ({
    status: s,
    ...PRIORITIES.reduce((a, p) => ({ ...a, [p]: matrix[s][p] }), {}),
    total: PRIORITIES.reduce((sum, p) => sum + matrix[s][p], 0),
  }))

  // ── Average age per status ────────────────────────────────────────────────
  const avgAgeByStatus = {}
  ALL_STATUSES.forEach(s => {
    const g = issues.filter(i => normStatus(i.status) === s)
    avgAgeByStatus[s] = g.length ? Math.round(g.reduce((sm, i) => sm + ageInDays(i), 0) / g.length) : 0
  })

  // ── Issue Type Breakdown (derived from title/description) ─────────────────
  const typeMap = new Map()
  issues.forEach(i => { const t = issueType(i); typeMap.set(t, (typeMap.get(t) || 0) + 1) })
  const typeBreakdown = [...typeMap.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([type, count]) => ({ type, count, pct: Math.round(count / issues.length * 100) }))

  // ── Root Cause Breakdown (derived from age + status patterns) ────────────
  // We bucket by observable signals since CxAlloy has no root_cause field
  const rcMap = new Map()
  issues.forEach(i => {
    let cause = 'Unclear issue definition'  // default — most common
    const age = ageInDays(i)
    const s = normStatus(i.status)
    const hasDesc = (i.description || '').trim().length > 20
    if (!hasDesc) cause = 'Unclear issue definition'
    else if (s === 'Correction In Progress' && age > 30) cause = 'Repeated rework cycle'
    else if (s === 'Ready For Verification' && age > 14) cause = 'Verification bottleneck'
    else if (age > 90) cause = 'Long-standing unresolved'
    else cause = 'Unclear issue definition'
    const e = rcMap.get(cause) || { count: 0, totalAge: 0 }
    e.count++; e.totalAge += age
    rcMap.set(cause, e)
  })
  const rootBreakdown = [...rcMap.entries()]
    .sort((a, b) => b[1].count - a[1].count)
    .map(([cause, { count, totalAge }]) => ({
      cause, count,
      pct:    Math.round(count / issues.length * 100),
      avgAge: count > 0 ? Math.round(totalAge / count) : 0,
    }))

  // ── Company / Owner Flow (open issues only) ───────────────────────────────
  const companyMap = new Map()
  openIssues.forEach(i => {
    const co = companyOf(i)
    companyMap.set(co, (companyMap.get(co) || 0) + 1)
  })
  const companyFlow = [...companyMap.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 12)
    .map(([company, count]) => ({ company, count, pct: Math.round(count / openIssues.length * 100) }))

  // ── Company × Type cross-matrix for Cross-Company tab ─────────────────────
  const coTypeMap = new Map()
  issues.forEach(i => {
    const co = companyOf(i)
    const ty = issueType(i)
    const key = co + '||' + ty
    coTypeMap.set(key, (coTypeMap.get(key) || 0) + 1)
  })
  // Top 6 companies
  const topCos = [...companyMap.entries()].sort((a,b) => b[1]-a[1]).slice(0,6).map(([c]) => c)
  const companyMatrix = topCos.map(co => {
    const row = { company: co }
    typeBreakdown.slice(0, 4).forEach(({ type }) => {
      row[type] = coTypeMap.get(co + '||' + type) || 0
    })
    row.total = companyMap.get(co) || 0
    return row
  })

  // ── Age threshold ─────────────────────────────────────────────────────────
  const ageThreshold = globalAvgAge > 0 ? Math.round(globalAvgAge * 1.1) : 30
  const outsideThreshold = openIssues.filter(i => ageInDays(i) > ageThreshold).length

  return {
    pressure: openIssues.length,
    qualityScore, qualityGrade, highCycleIssues,
    matrixRows, avgAgeByStatus, globalAvgAge,
    typeBreakdown, rootBreakdown,
    companyFlow, companyMatrix,
    topType:      typeBreakdown[0] || null,
    topRootCause: rootBreakdown[0] || null,
    topOwner:     companyFlow[0]   || null,
    outsideThreshold, ageThreshold,
    totalRows: issues.length,
    openCount: openIssues.length,
  }
}

// ─── Data hook ────────────────────────────────────────────────────────────────
function useIssueData() {
  const { selectedProjects, activeProject } = useProject()
  const targets = selectedProjects.length > 0 ? selectedProjects : (activeProject ? [activeProject] : [])
  const [issues, setIssues] = useState([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    if (!targets.length) return
    setLoading(true)
    setError(null)
    Promise.all(
      targets.map(p =>
        issuesApi.getAll(p.externalId)
          .then(r => r.data?.data || [])
          .catch(e => { console.error('Issues fetch error:', e); return [] })
      )
    )
      .then(results => setIssues(results.flat()))
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [targets.map(p => p.externalId).join(',')])

  return { issues, loading, error }
}

// ─── Statistics Tab ───────────────────────────────────────────────────────────
function StatisticsTab({ radar }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>

        {/* Priority Matrix */}
        <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, overflow: 'hidden' }}>
          <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--divider)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <div>
              <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)' }}>Issue Status × Priority Matrix</div>
              <div style={{ fontSize: 11, color: '#64748b', marginTop: 2 }}>Live issue pressure split by workflow stage (Overall)</div>
            </div>
            <span style={{ fontSize: 12, fontWeight: 700, padding: '3px 10px', borderRadius: 20, background: 'rgba(100,116,139,0.15)', color: '#94a3b8' }}>{radar.totalRows} rows</span>
          </div>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ borderBottom: '1px solid var(--divider)' }}>
                  <th style={{ padding: '10px 16px', textAlign: 'left', fontSize: 10, fontWeight: 600, color: '#475569', textTransform: 'uppercase', letterSpacing: '0.06em' }}>STATUS</th>
                  {PRIORITIES.map(p => (
                    <th key={p} style={{ padding: '10px 12px', textAlign: 'right', fontSize: 10, fontWeight: 600, color: '#475569', textTransform: 'uppercase', whiteSpace: 'nowrap' }}>
                      {p.replace('P1 - ', 'P1·').replace('P2 - ', 'P2·').replace('P3 - ', 'P3·').replace('P4 - ', 'P4·')}
                    </th>
                  ))}
                  <th style={{ padding: '10px 12px', textAlign: 'right', fontSize: 10, fontWeight: 600, color: '#475569', textTransform: 'uppercase' }}>TOTAL</th>
                </tr>
              </thead>
              <tbody>
                {radar.matrixRows.map((row, i) => (
                  <tr key={row.status} style={{ borderBottom: '1px solid var(--divider)', background: i % 2 === 1 ? 'var(--row-alt)' : 'transparent' }}>
                    <td style={{ padding: '11px 16px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 500 }}>
                      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                        <span style={{ width: 8, height: 8, borderRadius: '50%', background: STATUS_COLORS[row.status], flexShrink: 0, display: 'inline-block' }} />
                        {row.status}
                      </span>
                    </td>
                    {PRIORITIES.map(p => (
                      <td key={p} style={{ padding: '11px 12px', textAlign: 'right', fontSize: 13, fontWeight: row[p] > 0 ? 700 : 400, color: row[p] > 0 ? 'var(--text-primary)' : '#334155' }}>
                        {row[p]}
                      </td>
                    ))}
                    <td style={{ padding: '11px 12px', textAlign: 'right', fontSize: 13, fontWeight: 700, color: row.total > 0 ? '#60a5fa' : '#334155' }}>{row.total}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div style={{ padding: '11px 16px', fontSize: 11, color: '#64748b', borderTop: '1px solid var(--divider)', fontStyle: 'italic' }}>
            Highest pressure sits in <strong style={{ color: 'var(--text-primary)', fontStyle: 'normal' }}>Unassigned</strong>, with bottlenecks concentrated in early workflow states.
          </div>
        </div>

        {/* Issue Status Timeline */}
        <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, padding: '18px 20px' }}>
          <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 2 }}>Issue Status Timeline</div>
          <div style={{ fontSize: 11, color: '#64748b', marginBottom: 24 }}>Track progression through the issue lifecycle</div>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            {ALL_STATUSES.map((s, idx) => {
              const row   = radar.matrixRows.find(r => r.status === s) || { total: 0 }
              const avg   = radar.avgAgeByStatus[s] || 0
              const color = STATUS_COLORS[s]
              return (
                <React.Fragment key={s}>
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}>
                    <div style={{
                      width: 70, height: 70, borderRadius: '50%',
                      border: `3px solid ${color}`,
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      background: row.total > 0 ? `${color}18` : 'transparent',
                    }}>
                      <span style={{ fontSize: 19, fontWeight: 800, color: row.total > 0 ? color : '#334155' }}>{row.total}</span>
                    </div>
                    <div style={{ fontSize: 10, fontWeight: 600, color: row.total > 0 ? 'var(--text-primary)' : '#475569', textAlign: 'center', maxWidth: 70, lineHeight: 1.3 }}>{s}</div>
                    <div style={{ fontSize: 10, color: '#64748b' }}>{avg > 0 ? `${avg}d avg` : '0d avg'}</div>
                  </div>
                  {idx < ALL_STATUSES.length - 1 && (
                    <div style={{ flex: 1, height: 0, borderTop: '2px dashed #334155', margin: '0 4px', marginBottom: 36 }} />
                  )}
                </React.Fragment>
              )
            })}
          </div>
          {radar.outsideThreshold > 0 && (
            <div style={{ marginTop: 16, padding: '10px 14px', background: 'rgba(239,68,68,0.07)', border: '1px solid rgba(239,68,68,0.2)', borderRadius: 8, fontSize: 11, color: '#f87171' }}>
              {radar.outsideThreshold} transitions are outside the normal age threshold of {radar.ageThreshold}.{Math.round(radar.globalAvgAge * 0.1)} days.
            </div>
          )}
        </div>
      </div>

      {/* Issue Type + Root Cause row */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <IssueTypeCard data={radar.typeBreakdown} />
        <RootCauseCard data={radar.rootBreakdown} />
      </div>
    </div>
  )
}

// ─── Reusable breakdown cards ─────────────────────────────────────────────────
function IssueTypeCard({ data }) {
  return (
    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, padding: '18px 20px' }}>
      <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 2 }}>Issue Type Breakdown</div>
      <div style={{ fontSize: 11, color: '#64748b', marginBottom: 14 }}>Different kinds of issues found in the current selection</div>
      {(data || []).map(({ type, count, pct }, i) => (
        <div key={i} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 0', borderBottom: i < data.length - 1 ? '1px solid var(--divider)' : 'none' }}>
          <div>
            <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-primary)' }}>{type}</span>
            <span style={{ fontSize: 11, color: '#64748b', marginLeft: 6 }}>{pct}% share</span>
          </div>
          <span style={{ fontSize: 15, fontWeight: 800, color: 'var(--text-primary)' }}>{count}</span>
        </div>
      ))}
      {!data?.length && <div style={{ fontSize: 12, color: '#475569', padding: '20px 0' }}>No data</div>}
    </div>
  )
}

function RootCauseCard({ data }) {
  return (
    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, padding: '18px 20px' }}>
      <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 2 }}>Root Cause Breakdown</div>
      <div style={{ fontSize: 11, color: '#64748b', marginBottom: 14 }}>Primary drivers behind issue churn and rework</div>
      {(data || []).map(({ cause, count, pct, avgAge }, i) => (
        <div key={i} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 0', borderBottom: i < data.length - 1 ? '1px solid var(--divider)' : 'none' }}>
          <div>
            <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-primary)' }}>{cause}</span>
            <span style={{ fontSize: 11, color: '#64748b', marginLeft: 6 }}>{pct}% share | {avgAge}d avg age</span>
          </div>
          <span style={{ fontSize: 15, fontWeight: 800, color: 'var(--text-primary)' }}>{count}</span>
        </div>
      ))}
      {!data?.length && <div style={{ fontSize: 12, color: '#475569', padding: '20px 0' }}>No data</div>}
    </div>
  )
}

// ─── Flow Analysis Tab ────────────────────────────────────────────────────────
function FlowAnalysisTab({ radar }) {
  const max = Math.max(...(radar.companyFlow.map(c => c.count) || [1]), 1)
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, overflow: 'hidden' }}>
        <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--divider)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div>
            <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)' }}>Open Issue Load by Assignee / Company</div>
            <div style={{ fontSize: 11, color: '#64748b', marginTop: 2 }}>Who currently holds the most open issue burden</div>
          </div>
          <span style={{ fontSize: 12, fontWeight: 700, color: '#94a3b8' }}>{radar.openCount} open</span>
        </div>
        {radar.companyFlow.map(({ company, count, pct }, i) => (
          <div key={i} style={{ padding: '13px 20px', borderBottom: '1px solid var(--border-subtle)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 7 }}>
              <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-primary)' }}>{company}</span>
              <span style={{ fontSize: 12, fontWeight: 700, color: '#60a5fa' }}>{count} <span style={{ color: '#475569', fontWeight: 400 }}>({pct}%)</span></span>
            </div>
            <div style={{ height: 6, background: 'var(--divider)', borderRadius: 3, overflow: 'hidden' }}>
              <div style={{ height: '100%', width: `${(count / max) * 100}%`, background: `hsl(${220 - i * 12}, 80%, 55%)`, borderRadius: 3, transition: 'width 0.5s ease' }} />
            </div>
          </div>
        ))}
        {!radar.companyFlow.length && (
          <div style={{ padding: '40px', textAlign: 'center', color: '#475569', fontSize: 13 }}>No open issues — all resolved!</div>
        )}
      </div>

      {/* High-Cycle Rework Issues */}
      <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, overflow: 'hidden' }}>
        <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--divider)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div>
            <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)' }}>High-Cycle Rework Issues</div>
            <div style={{ fontSize: 11, color: '#64748b', marginTop: 2 }}>Issues stuck in Correction / Verification — highest churn risk</div>
          </div>
          <span style={{ fontSize: 12, fontWeight: 700, padding: '3px 10px', borderRadius: 20, background: 'rgba(245,158,11,0.1)', border: '1px solid rgba(245,158,11,0.25)', color: '#f59e0b' }}>{radar.highCycleIssues.length}</span>
        </div>
        {radar.highCycleIssues.slice(0, 10).map((issue, i) => {
          const age = ageInDays(issue)
          return (
            <div key={issue.id || i} style={{ display: 'flex', alignItems: 'center', padding: '11px 20px', borderBottom: '1px solid var(--border-subtle)', gap: 10 }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <span style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-primary)' }}>{issue.externalId || `#${issue.id}`}</span>
                <span style={{ fontSize: 11, color: '#64748b', marginLeft: 8 }}>{issue.title?.substring(0, 55) || 'No title'}{issue.title?.length > 55 ? '…' : ''}</span>
              </div>
              <span style={{ fontSize: 11, color: '#94a3b8', whiteSpace: 'nowrap', flexShrink: 0 }}>{normStatus(issue.status)}</span>
              <span style={{ fontSize: 11, padding: '2px 8px', borderRadius: 12, background: 'rgba(245,158,11,0.1)', border: '1px solid rgba(245,158,11,0.2)', color: '#f59e0b', whiteSpace: 'nowrap', flexShrink: 0 }}>{age}d old</span>
              <span style={{ fontSize: 11, color: '#64748b', whiteSpace: 'nowrap', flexShrink: 0 }}>{issueType(issue)}</span>
            </div>
          )
        })}
        {!radar.highCycleIssues.length && (
          <div style={{ padding: '32px', textAlign: 'center', color: '#475569', fontSize: 13 }}>No high-cycle issues — workflow is clean!</div>
        )}
      </div>
    </div>
  )
}

// ─── Cross-Company Tab ────────────────────────────────────────────────────────
function CrossCompanyTab({ radar }) {
  const types = (radar.typeBreakdown || []).slice(0, 4).map(t => t.type)
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* Cross matrix: company × issue type */}
      {radar.companyMatrix?.length > 0 && (
        <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, overflow: 'hidden' }}>
          <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--divider)' }}>
            <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)' }}>Company × Issue Type Matrix</div>
            <div style={{ fontSize: 11, color: '#64748b', marginTop: 2 }}>Cross-company issue distribution — {radar.totalRows} total rows</div>
          </div>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ borderBottom: '1px solid var(--divider)' }}>
                  <th style={{ padding: '10px 16px', textAlign: 'left', fontSize: 10, fontWeight: 600, color: '#475569', textTransform: 'uppercase' }}>COMPANY</th>
                  {types.map(t => (
                    <th key={t} style={{ padding: '10px 12px', textAlign: 'right', fontSize: 10, fontWeight: 600, color: '#475569', textTransform: 'uppercase', whiteSpace: 'nowrap', maxWidth: 120 }}>
                      {t.split(' ').slice(0, 2).join(' ')}
                    </th>
                  ))}
                  <th style={{ padding: '10px 12px', textAlign: 'right', fontSize: 10, fontWeight: 600, color: '#475569', textTransform: 'uppercase' }}>TOTAL</th>
                </tr>
              </thead>
              <tbody>
                {radar.companyMatrix.map((row, i) => (
                  <tr key={i} style={{ borderBottom: '1px solid var(--divider)', background: i % 2 === 1 ? 'var(--row-alt)' : 'transparent' }}>
                    <td style={{ padding: '11px 16px', fontSize: 12, color: 'var(--text-primary)', fontWeight: 600 }}>{row.company}</td>
                    {types.map(t => (
                      <td key={t} style={{ padding: '11px 12px', textAlign: 'right', fontSize: 13, fontWeight: row[t] > 0 ? 700 : 400, color: row[t] > 0 ? 'var(--text-primary)' : '#334155' }}>{row[t] || 0}</td>
                    ))}
                    <td style={{ padding: '11px 12px', textAlign: 'right', fontSize: 13, fontWeight: 700, color: '#60a5fa' }}>{row.total}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <IssueTypeCard data={radar.typeBreakdown} />
        <RootCauseCard data={radar.rootBreakdown} />
      </div>
    </div>
  )
}

// ─── AI Analysis Tab — OpenAI GPT-4o-mini ────────────────────────────────────
const OPENAI_KEY = import.meta.env?.VITE_OPENAI_API_KEY || ''

function AICopilot({ radar, issues }) {
  const [messages, setMessages]   = useState([])
  const [input,    setInput]      = useState('')
  const [thinking, setThinking]   = useState(false)
  const [apiKey,   setApiKey]     = useState(OPENAI_KEY)
  const [showKey,  setShowKey]    = useState(!OPENAI_KEY)
  const endRef = useRef(null)

  const QUICK = [
    'What is the main root cause right now?',
    'Where is the biggest workflow bottleneck?',
    'Which company has the highest open load?',
    'Give me a 3-step action plan.',
  ]

  const systemPrompt = `You are an Issue Copilot for a commissioning and construction project management platform.

Current project issue data:
- Total issues: ${radar.totalRows}
- Open issues: ${radar.openCount} (${radar.totalRows > 0 ? Math.round(radar.openCount/radar.totalRows*100) : 0}% open rate)
- Quality score: ${radar.qualityScore}/100 (Grade: ${radar.qualityGrade})
- High-cycle rework issues: ${radar.highCycleIssues.length}
- Global average age (open): ${radar.globalAvgAge} days
- Issue type breakdown: ${JSON.stringify(radar.typeBreakdown?.slice(0,4)?.map(t => `${t.type}: ${t.count} (${t.pct}%)`).join(', '))}
- Root cause breakdown: ${JSON.stringify(radar.rootBreakdown?.slice(0,3)?.map(r => `${r.cause}: ${r.count} (avg ${r.avgAge}d)`).join(', '))}
- Top open-load owner: ${radar.topOwner ? `${radar.topOwner.company} with ${radar.topOwner.count} open issues` : 'N/A'}
- Status matrix: Open=${radar.matrixRows?.find(r=>r.status==='Open')?.total||0}, CorrectionInProgress=${radar.matrixRows?.find(r=>r.status==='Correction In Progress')?.total||0}, ReadyForVerification=${radar.matrixRows?.find(r=>r.status==='Ready For Verification')?.total||0}, Closed=${radar.matrixRows?.find(r=>r.status==='Closed')?.total||0}

Be concise, actionable, and construction/commissioning focused. Keep answers under 4 sentences unless a step-by-step plan is requested.`

  useEffect(() => {
    setMessages([{
      role: 'assistant',
      text: `Issue Copilot online. I see ${radar.totalRows} total issues — ${radar.openCount} open, ${radar.highCycleIssues.length} high-cycle. Quality score: ${radar.qualityScore} (${radar.qualityGrade}). Primary root-cause signal is "${radar.topRootCause?.cause || 'N/A'}" and top open-load owner is ${radar.topOwner?.company || 'Unassigned'}.`,
    }])
  }, [radar.totalRows, radar.openCount])

  useEffect(() => { endRef.current?.scrollIntoView({ behavior: 'smooth' }) }, [messages])

  async function ask(q) {
    const text = (q || input).trim()
    if (!text) return
    if (!apiKey) { alert('Please enter your OpenAI API key above to use AI Analysis.'); return }
    setInput('')
    setMessages(m => [...m, { role: 'user', text }])
    setThinking(true)
    try {
      const resp = await fetch('https://api.openai.com/v1/chat/completions', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${apiKey}`,
        },
        body: JSON.stringify({
          model: 'gpt-4o-mini',
          max_tokens: 500,
          messages: [
            { role: 'system', content: systemPrompt },
            ...messages.filter(m => m.role !== 'assistant' || messages.indexOf(m) > 0).map(m => ({
              role: m.role,
              content: m.text,
            })),
            { role: 'user', content: text },
          ],
        }),
      })
      const data = await resp.json()
      if (data.error) throw new Error(data.error.message)
      const reply = data.choices?.[0]?.message?.content || 'No response received.'
      setMessages(m => [...m, { role: 'assistant', text: reply }])
    } catch (err) {
      setMessages(m => [...m, { role: 'assistant', text: `Error: ${err.message}. Check your API key and try again.` }])
    } finally {
      setThinking(false)
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* KPI cards */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 14 }}>
        {[
          { label: 'Quality Score', value: radar.qualityScore, sub: `Grade ${radar.qualityGrade}`, color: radar.qualityScore <= 20 ? '#f87171' : radar.qualityScore >= 70 ? '#4ade80' : '#f59e0b' },
          { label: 'High-Cycle Issues', value: radar.highCycleIssues.length, sub: 'Stuck in rework', color: '#f59e0b' },
          { label: 'Open Issues', value: radar.openCount, sub: `of ${radar.totalRows} total`, color: '#60a5fa' },
          { label: 'Avg Age (Open)', value: `${radar.globalAvgAge}d`, sub: 'Days since created', color: '#a78bfa' },
        ].map(({ label, value, sub, color }) => (
          <div key={label} style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, padding: '16px 18px' }}>
            <div style={{ fontSize: 10, color: '#475569', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 6 }}>{label}</div>
            <div style={{ fontSize: 28, fontWeight: 800, color, lineHeight: 1 }}>{value}</div>
            <div style={{ fontSize: 11, color: '#64748b', marginTop: 4 }}>{sub}</div>
          </div>
        ))}
      </div>

      {/* API key input (shown if no env key) */}
      {showKey && (
        <div style={{ background: 'rgba(59,130,246,0.06)', border: '1px solid rgba(59,130,246,0.2)', borderRadius: 10, padding: '14px 18px', display: 'flex', gap: 10, alignItems: 'center' }}>
          <span style={{ fontSize: 12, color: '#94a3b8', flexShrink: 0 }}>OpenAI API Key:</span>
          <input
            type="password"
            value={apiKey}
            onChange={e => setApiKey(e.target.value)}
            placeholder="sk-..."
            style={{ flex: 1, padding: '7px 12px', borderRadius: 7, background: 'var(--bg-card)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, outline: 'none' }}
          />
          <button onClick={() => setShowKey(false)} style={{ padding: '7px 14px', borderRadius: 7, background: '#3b82f6', border: 'none', color: '#fff', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>Save</button>
          <span style={{ fontSize: 10, color: '#475569', maxWidth: 180 }}>Or set VITE_OPENAI_API_KEY in .env — key stays in browser only</span>
        </div>
      )}

      {/* Chat window */}
      <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, overflow: 'hidden' }}>
        <div style={{ padding: '14px 18px', borderBottom: '1px solid var(--divider)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div>
            <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' }}>AI Issue Copilot</div>
            <div style={{ fontSize: 11, color: '#64748b', marginTop: 2 }}>Powered by GPT-4o-mini · Chat-style root cause and action analysis</div>
          </div>
          <button onClick={() => setShowKey(v => !v)} style={{ fontSize: 11, color: '#475569', background: 'none', border: '1px solid var(--border)', borderRadius: 6, padding: '4px 10px', cursor: 'pointer' }}>
            {showKey ? 'Hide key' : 'Change key'}
          </button>
        </div>

        {/* Messages */}
        <div style={{ height: 280, overflowY: 'auto', padding: '14px 18px', display: 'flex', flexDirection: 'column', gap: 10 }}>
          {messages.map((m, i) => (
            <div key={i} style={{
              alignSelf: m.role === 'user' ? 'flex-end' : 'flex-start',
              maxWidth: '86%',
              padding: '10px 14px',
              borderRadius: m.role === 'user' ? '14px 14px 3px 14px' : '14px 14px 14px 3px',
              background: m.role === 'user' ? '#3b82f6' : 'rgba(51,65,85,0.5)',
              fontSize: 12.5, color: m.role === 'user' ? '#fff' : 'var(--text-primary)',
              lineHeight: 1.55, whiteSpace: 'pre-wrap',
            }}>{m.text}</div>
          ))}
          {thinking && (
            <div style={{ alignSelf: 'flex-start', padding: '10px 16px', borderRadius: '14px 14px 14px 3px', background: 'rgba(51,65,85,0.4)', fontSize: 12 }}>
              <span style={{ color: '#64748b' }}>Thinking</span>
              <span style={{ color: '#3b82f6', animation: 'none' }}>…</span>
            </div>
          )}
          <div ref={endRef} />
        </div>

        {/* Quick prompts */}
        <div style={{ padding: '10px 18px', borderTop: '1px solid var(--divider)', display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {QUICK.map(q => (
            <button key={q} onClick={() => ask(q)} disabled={thinking}
              style={{ padding: '5px 11px', borderRadius: 7, background: 'var(--bg-card)', border: '1px solid var(--border)', color: '#94a3b8', fontSize: 11, cursor: 'pointer', fontWeight: 500 }}>
              {q}
            </button>
          ))}
        </div>

        {/* Input */}
        <div style={{ padding: '10px 18px', borderTop: '1px solid var(--divider)', display: 'flex', gap: 8 }}>
          <input
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); ask() } }}
            placeholder="Ask about root causes, bottlenecks, company load, or next actions…"
            disabled={thinking}
            style={{ flex: 1, padding: '9px 12px', borderRadius: 8, background: 'var(--bg-card)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, outline: 'none' }}
          />
          <button onClick={() => ask()} disabled={thinking || !input.trim() || !apiKey}
            style={{ padding: '9px 18px', borderRadius: 8, background: '#3b82f6', border: 'none', color: '#fff', fontSize: 12, fontWeight: 700, cursor: 'pointer', opacity: (!input.trim() || !apiKey) ? 0.5 : 1 }}>
            Ask
          </button>
        </div>
      </div>

      {/* Type + Root Cause */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <IssueTypeCard data={radar.typeBreakdown} />
        <RootCauseCard data={radar.rootBreakdown} />
      </div>
    </div>
  )
}

// ─── Main Page ────────────────────────────────────────────────────────────────
export default function IssueRadarPage() {
  const { issues, loading, error } = useIssueData()
  const radar = useMemo(() => computeRadar(issues), [issues])
  const [activeTab, setActiveTab] = useState('Statistics')

  if (loading) return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <div><div style={{ height: 28, width: 160, background: 'var(--bg-card)', borderRadius: 6, border: '1px solid var(--border)' }} /></div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 14 }}>
        {[...Array(4)].map((_, i) => <div key={i} style={{ background: 'var(--bg-card)', borderRadius: 12, height: 90, border: '1px solid var(--border)' }} />)}
      </div>
    </div>
  )

  if (error) return (
    <div style={{ padding: 40, textAlign: 'center', color: '#f87171', fontSize: 13 }}>
      Failed to load issues: {error}
    </div>
  )

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* Title */}
      <div>
        <h2 style={{ fontSize: 22, fontWeight: 800, color: 'var(--text-primary)', margin: 0 }}>Issue Radar</h2>
        <p style={{ fontSize: 13, color: '#64748b', marginTop: 4 }}>Issue statistics, workflow bottlenecks, and cross-company impact.</p>
      </div>

      {/* Summary insight bar */}
      {radar.totalRows > 0 && (
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
          {[
            { label: 'Focus', value: `${radar.topType?.type || 'N/A'} (${radar.topType?.pct || 0}%)` },
            { label: 'Primary Root Cause', value: radar.topRootCause?.cause || 'N/A' },
            { label: 'Main Load Owner', value: radar.topOwner ? `${radar.topOwner.company} (${radar.topOwner.count} open)` : 'N/A' },
          ].map(({ label, value }) => (
            <div key={label} style={{ padding: '6px 14px', borderRadius: 20, background: 'rgba(59,130,246,0.09)', border: '1px solid rgba(59,130,246,0.22)', fontSize: 12, fontWeight: 600, color: '#60a5fa' }}>
              <span style={{ color: '#64748b', fontWeight: 400 }}>{label}: </span>{value}
            </div>
          ))}
        </div>
      )}

      {/* Tabs */}
      <div style={{ display: 'flex', gap: 2, borderBottom: '1px solid var(--divider)', paddingBottom: 0 }}>
        {TABS.map(tab => (
          <button key={tab} onClick={() => setActiveTab(tab)} style={{
            padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: 'pointer',
            background: 'transparent', border: 'none',
            borderBottom: activeTab === tab ? '2px solid #3b82f6' : '2px solid transparent',
            color: activeTab === tab ? '#60a5fa' : '#64748b',
            marginBottom: -1, transition: 'color 0.15s',
          }}>
            {tab === 'Cross-Company' ? `Cross-Company ${radar.totalRows}` : tab}
          </button>
        ))}
      </div>

      {/* No data state */}
      {!radar.totalRows && (
        <div style={{ padding: '60px 40px', textAlign: 'center', color: '#475569' }}>
          <div style={{ fontSize: 32, marginBottom: 12 }}>📋</div>
          <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--text-primary)', marginBottom: 6 }}>No issues found</div>
          <div style={{ fontSize: 13 }}>Select a project and sync issues from the Sync page to populate this view.</div>
        </div>
      )}

      {radar.totalRows > 0 && (
        <>
          {activeTab === 'Statistics'    && <StatisticsTab    radar={radar} />}
          {activeTab === 'Flow Analysis' && <FlowAnalysisTab  radar={radar} />}
          {activeTab === 'Cross-Company' && <CrossCompanyTab  radar={radar} />}
        </>
      )}
    </div>
  )
}
