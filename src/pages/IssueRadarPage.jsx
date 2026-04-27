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
import { copilotApi, equipmentApi, issuesApi } from '../services/api'
import { ResponsiveContainer, BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, LabelList } from 'recharts'

// ─── Constants ────────────────────────────────────────────────────────────────
const ALL_STATUSES = ['Issue Opened', 'Correction in Progress', 'GC to Verify', 'CxA to verify', 'Issue Closed', 'Accepted by Owner', 'Recommendation']
const PRIORITIES   = ['P1 - Critical', 'P2 - High', 'P3 - Medium', 'P4 - Low']
const TABS         = ['Statistics', 'Flow Analysis', 'Cross-Company']
const CLOSED_ISSUE_STATUSES = new Set(['Issue Closed', 'Accepted by Owner'])

const STATUS_COLORS = {
  'Issue Opened': '#ef4444',
  'Correction in Progress': '#3b82f6',
  'GC to Verify': '#f59e0b',
  'CxA to verify': '#a855f7',
  'Issue Closed': '#22c55e',
  'Accepted by Owner': '#14b8a6',
  Recommendation: '#94a3b8',
}

// ─── Normalise helpers — mapped to exact backend snake_case tokens ─────────────
function normStatus(s) {
  switch ((s || '').toLowerCase().trim()) {
    case 'open':
    case 'issue_opened':
    case 'active':
    case 'new':
      return 'Issue Opened'
    case 'correction_in_progress':
    case 'in_progress':
    case 'started':
      return 'Correction in Progress'
    case 'gc_to_verify':
    case 'gc_verify':
      return 'GC to Verify'
    case 'ready_for_retest':
    case 'ready_for_verification':
    case 'cxa_to_verify':
    case 'cxa_verify':
      return 'CxA to verify'
    case 'issue_closed':
    case 'closed':
    case 'done':
    case 'resolved':
    case 'completed':
      return 'Issue Closed'
    case 'accepted_by_owner':
    case 'accepted':
      return 'Accepted by Owner'
    case 'recommendation':
    case 'additional_information_needed':
      return 'Recommendation'
    default:
      return 'Issue Opened'
  }
}

function isClosedStatus(status) {
  return CLOSED_ISSUE_STATUSES.has(status)
}

function normalizeText(value) {
  return String(value || '').trim().toLowerCase()
}

function getEquipmentLabel(equipment) {
  return equipment?.equipmentType || equipment?.systemName || equipment?.discipline || equipment?.name || 'Unclassified'
}

function buildEquipmentLookup(equipment) {
  const byId = new Map()
  const byAlias = new Map()

  equipment.forEach((item) => {
    const ids = [item.externalId, item.id, item.tag, item.name]
      .map(normalizeText)
      .filter(Boolean)

    ids.forEach((id) => {
      if (!byAlias.has(id)) byAlias.set(id, item)
    })

    const externalId = normalizeText(item.externalId)
    if (externalId) byId.set(externalId, item)
  })

  return { byId, byAlias }
}

function getIssueAssetKey(issue) {
  return normalizeText(issue.assetId || issue.assetExternalId || issue.equipmentId || issue.linkedAssetId || issue.assetName)
}

function getEquipmentTypeForIssue(issue, lookup) {
  const assetKey = getIssueAssetKey(issue)
  const equipment = (assetKey && lookup.byId.get(assetKey)) || (assetKey && lookup.byAlias.get(assetKey)) || null
  return getEquipmentLabel(equipment)
}

function getEquipmentNameForIssue(issue, lookup) {
  const assetKey = getIssueAssetKey(issue)
  const equipment = (assetKey && lookup.byId.get(assetKey)) || (assetKey && lookup.byAlias.get(assetKey)) || null
  if (equipment) {
    return equipment.name || equipment.tag || equipment.externalId || 'Unnamed Equipment'
  }
  return issue.assetName || issue.assetTag || issue.equipmentName || issue.assetId || issue.assetExternalId || 'Unassigned Equipment'
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

function companyFromRaw(raw) {
  if (!raw || !raw.trim()) return 'Unassigned'
  const value = raw.trim()
  const dashIdx = value.indexOf(' - ')
  if (dashIdx > 0) return value.substring(0, dashIdx).trim()
  return value.split(' ')[0] || value
}

// Owner = assignee → reporter → location → 'Unassigned'
function ownerOf(i) {
  return (i.assignee && i.assignee.trim()) || (i.reporter && i.reporter.trim()) || i.location || 'Unassigned'
}

function assignedCompanyOf(i) {
  return companyFromRaw(i.assignee || i.assigned_to || i.location || 'Unassigned')
}

function reporterCompanyOf(i) {
  return companyFromRaw(i.createdBy || i.created_by || i.reporter || 'Unassigned')
}

function companyOf(i) {
  return assignedCompanyOf(i)
}

function ageInDays(i) {
  const now = new Date()
  const d = new Date(i.createdAt || i.created_at || now)
  return isNaN(d) ? 0 : Math.max(0, Math.round((now - d) / 86400000))
}

function finishDateOf(i) {
  const raw = i.actualFinishDate || i.actual_finish_date || i.completedDate || i.completed_date || i.updatedAt || i.updated_at || null
  if (!raw) return null
  const d = new Date(raw)
  return isNaN(d) ? null : d
}

function daysToClose(i) {
  const start = new Date(i.createdAt || i.created_at || 0)
  const finish = finishDateOf(i)
  if (isNaN(start) || !finish) return null
  return Math.max(0, (finish - start) / 86400000)
}

function exportCsv(filename, rows) {
  if (!rows?.length) return
  const headers = Object.keys(rows[0])
  const csvEscape = (value) => {
    if (value === null || value === undefined) return ''
    const text = String(value)
    return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
  }
  const csv = [headers.join(','), ...rows.map(row => headers.map(header => csvEscape(row[header])).join(','))].join('\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

// High-cycle = issue that has been through correction loop:
// status is NOT open/closed AND age > 14 days (stuck in correction/review)
function isHighCycle(i) {
  const s = normStatus(i.status)
  return s === 'Correction in Progress' || s === 'GC to Verify' || s === 'CxA to verify'
}

// ─── Build all radar metrics from the raw issue array ────────────────────────
function computeRadar(issues, equipment) {
  if (!issues.length) return {
    pressure: 0, qualityScore: 0, qualityGrade: 'D', highCycleIssues: [],
    matrixRows: ALL_STATUSES.map(s => ({ status: s, ...PRIORITIES.reduce((a,p) => ({...a,[p]:0}),{}), total: 0 })),
    avgAgeByStatus: {}, globalAvgAge: 0, typeBreakdown: [], rootBreakdown: [],
    companyFlow: [], companyMatrix: [], topType: null, topRootCause: null,
    avgCloseByCompany: [], equipmentTypeBreakdown: [], topEquipmentType: null, topCloseCompany: null,
    crossCompanyClosures: [], crossCompanySummary: [], avgClosureTime: 0,
    topOwner: null, outsideThreshold: 0, ageThreshold: 0, totalRows: 0, openCount: 0, closedCount: 0,
  }

  const openIssues = issues.filter(i => !isClosedStatus(normStatus(i.status)))
  const closedIssues = issues.filter(i => isClosedStatus(normStatus(i.status)))
  const highCycleIssues = issues.filter(isHighCycle)
  const equipmentLookup = buildEquipmentLookup(equipment || [])

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
    else if (s === 'Correction in Progress' && age > 30) cause = 'Repeated rework cycle'
    else if ((s === 'GC to Verify' || s === 'CxA to verify') && age > 14) cause = 'Verification bottleneck'
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

  const equipmentTypeMap = new Map()
  openIssues.forEach((issue) => {
    const equipmentType = getEquipmentTypeForIssue(issue, equipmentLookup)
    equipmentTypeMap.set(equipmentType, (equipmentTypeMap.get(equipmentType) || 0) + 1)
  })
  const equipmentTypeBreakdown = [...equipmentTypeMap.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8)
    .map(([type, count]) => ({ type, count }))

  const equipmentIssueTypeMap = new Map()
  issues.forEach((issue) => {
    const equipmentType = getEquipmentTypeForIssue(issue, equipmentLookup)
    const current = equipmentIssueTypeMap.get(equipmentType) || {
      type: equipmentType,
      open: 0,
      closed: 0,
      cxaToVerify: 0,
      total: 0,
    }
    const status = normStatus(issue.status)
    current.total += 1
    if (status === 'CxA to verify') current.cxaToVerify += 1
    if (isClosedStatus(status)) current.closed += 1
    else current.open += 1
    equipmentIssueTypeMap.set(equipmentType, current)
  })
  const equipmentIssueBreakdown = [...equipmentIssueTypeMap.values()]
    .sort((a, b) => b.total - a.total || b.open - a.open || a.type.localeCompare(b.type))
    .slice(0, 8)
  const topEquipmentIssues = [...equipmentIssueTypeMap.values()]
    .sort((a, b) => b.total - a.total || b.open - a.open || a.type.localeCompare(b.type))
    .slice(0, 5)

  const closeMap = new Map()
  closedIssues.forEach(i => {
    const company = assignedCompanyOf(i)
    const days = daysToClose(i)
    if (days === null) return
    const current = closeMap.get(company) || { totalDays: 0, count: 0 }
    current.totalDays += days
    current.count += 1
    closeMap.set(company, current)
  })
  const avgCloseByCompany = [...closeMap.entries()]
    .map(([company, value]) => ({
      company,
      avgDays: +(value.totalDays / value.count).toFixed(1),
      closedIssues: value.count,
    }))
    .sort((a, b) => b.avgDays - a.avgDays)
    .slice(0, 12)
  const closeDurations = closedIssues.map(daysToClose).filter(days => days !== null)
  const avgClosureTime = closeDurations.length
    ? +(closeDurations.reduce((sum, days) => sum + days, 0) / closeDurations.length).toFixed(1)
    : 0

  const closurePairMap = new Map()
  const summaryMap = new Map()
  closedIssues.forEach(i => {
    const raisedBy = reporterCompanyOf(i)
    const closedBy = assignedCompanyOf(i)
    if (!raisedBy || !closedBy || raisedBy === 'Unassigned' || closedBy === 'Unassigned' || raisedBy === closedBy) return

    const pairKey = `${raisedBy}||${closedBy}`
    closurePairMap.set(pairKey, (closurePairMap.get(pairKey) || 0) + 1)

    const raisedSummary = summaryMap.get(raisedBy) || { company: raisedBy, raisedClosedByOthers: 0, closedRaisedByOthers: 0 }
    raisedSummary.raisedClosedByOthers += 1
    summaryMap.set(raisedBy, raisedSummary)

    const closedSummary = summaryMap.get(closedBy) || { company: closedBy, raisedClosedByOthers: 0, closedRaisedByOthers: 0 }
    closedSummary.closedRaisedByOthers += 1
    summaryMap.set(closedBy, closedSummary)
  })
  const crossCompanyClosures = [...closurePairMap.entries()]
    .map(([key, count]) => {
      const [raisedBy, closedBy] = key.split('||')
      return { raisedBy, closedBy, count }
    })
    .sort((a, b) => b.count - a.count)
    .slice(0, 6)
  const crossCompanySummary = [...summaryMap.values()]
    .map(row => ({
      ...row,
      net: row.closedRaisedByOthers - row.raisedClosedByOthers,
    }))
    .sort((a, b) => Math.abs(b.net) - Math.abs(a.net))

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
    avgCloseByCompany,
    equipmentTypeBreakdown,
    equipmentIssueBreakdown,
    topEquipmentIssues,
    topCloseCompany: avgCloseByCompany[0] || null,
    crossCompanyClosures,
    crossCompanySummary,
    avgClosureTime,
    topType:      typeBreakdown[0] || null,
    topEquipmentType: equipmentTypeBreakdown[0] || null,
    topRootCause: rootBreakdown[0] || null,
    topOwner:     companyFlow[0]   || null,
    outsideThreshold, ageThreshold,
    totalRows: issues.length,
    openCount: openIssues.length,
    closedCount: closedIssues.length,
  }
}

// ─── Data hook ────────────────────────────────────────────────────────────────
function useIssueData() {
  const { selectedProjects, activeProject } = useProject()
  const targets = selectedProjects.length > 0 ? selectedProjects : (activeProject ? [activeProject] : [])
  const [issues, setIssues] = useState([])
  const [equipment, setEquipment] = useState([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  useEffect(() => {
    if (!targets.length) return
    setLoading(true)
    setError(null)
    Promise.all(
      targets.map(p => Promise.all([
        issuesApi.getAll(p.externalId)
          .then(r => r.data?.data || [])
          .catch(e => { console.error('Issues fetch error:', e); return [] }),
        equipmentApi.getAll(p.externalId)
          .then(r => r.data?.data || [])
          .catch(e => { console.error('Equipment fetch error:', e); return [] }),
      ]))
    )
      .then(results => {
        setIssues(results.flatMap(([projectIssues]) => projectIssues))
        setEquipment(results.flatMap(([, projectEquipment]) => projectEquipment))
      })
      .catch(e => setError(e.message))
      .finally(() => setLoading(false))
  }, [targets.map(p => p.externalId).join(',')])

  return { issues, equipment, loading, error }
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
            Workflow pressure is concentrated in the early issue states, with the matrix reflecting the live synced status mix.
          </div>
        </div>

        <IssuesByEquipmentCard data={radar.equipmentIssueBreakdown} />
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
        <AvgTimeToCloseCard data={radar.avgCloseByCompany} />
        <TopEquipmentIssuesCard data={radar.topEquipmentIssues} />
      </div>
    </div>
  )
}

// ─── Company charts ────────────────────────────────────────────────────────────
function AvgTimeToCloseCard({ data }) {
  const exportRows = (data || []).map(row => ({
    company: row.company,
    avg_days_to_close: row.avgDays,
    closed_issues: row.closedIssues,
  }))
  return (
    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, padding: '18px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
        <div>
          <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 2 }}>Avg Time to Close by Company</div>
          <div style={{ fontSize: 11, color: '#64748b' }}>Average days to resolve issues with closed issue counts by company</div>
        </div>
        <button onClick={() => exportCsv('avg-time-to-close-by-company.csv', exportRows)} style={{ padding: '7px 12px', borderRadius: 8, background: 'transparent', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>
          Export
        </button>
      </div>
      {data?.length ? (
        <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 132px', gap: 16, alignItems: 'stretch' }}>
          <ResponsiveContainer width="100%" height={330}>
            <BarChart data={data} layout="vertical" margin={{ top: 4, right: 12, left: 36, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--divider)" horizontal={false} />
              <XAxis type="number" tick={{ fill: '#94a3b8', fontSize: 11 }} axisLine={false} tickLine={false} />
              <YAxis type="category" dataKey="company" width={160} tick={{ fill: 'var(--text-primary)', fontSize: 11 }} axisLine={false} tickLine={false} />
              <Tooltip
                formatter={(value, name) => {
                  if (name === 'avgDays') return [`${value} days`, 'Avg close time']
                  return [`${value}`, 'Closed issues']
                }}
                labelFormatter={(label, payload) => {
                  const row = payload?.[0]?.payload
                  return row ? `${row.company} | ${row.closedIssues} issues` : label
                }}
                contentStyle={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 10 }}
              />
              <Bar dataKey="avgDays" fill="#4f8df7" radius={[0, 4, 4, 0]} />
            </BarChart>
          </ResponsiveContainer>
          <div style={{ height: 330, display: 'flex', flexDirection: 'column', justifyContent: 'space-evenly', paddingTop: 22, paddingBottom: 14 }}>
            {data.map((row) => (
              <div key={row.company} style={{ display: 'flex', alignItems: 'center', gap: 4, whiteSpace: 'nowrap' }}>
                <span style={{ fontSize: 11, fontWeight: 700, color: '#94a3b8' }}>{row.closedIssues}</span>
                <span style={{ fontSize: 11, color: '#64748b' }}>issues</span>
                <span style={{ fontSize: 11, fontWeight: 700, color: '#f59e0b' }}>({row.avgDays}d)</span>
              </div>
            ))}
          </div>
        </div>
      ) : (
        <div style={{ fontSize: 12, color: '#475569', padding: '20px 0' }}>No closed issue timing data</div>
      )}
    </div>
  )
}

function IssuesByEquipmentCard({ data }) {
  const exportRows = (data || []).map(row => ({
    equipment_type: row.type,
    open_issues: row.open,
    closed_issues: row.closed,
    cxa_to_verify: row.cxaToVerify,
    total_issues: row.total,
  }))
  return (
    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, padding: '18px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
        <div>
          <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 2 }}>Issues by Equipment Type</div>
          <div style={{ fontSize: 11, color: '#64748b' }}>Open, closed, and CxA-to-verify counts grouped by linked equipment type</div>
        </div>
        <button onClick={() => exportCsv('issues-by-equipment-type-status.csv', exportRows)} style={{ padding: '7px 12px', borderRadius: 8, background: 'transparent', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>
          Export
        </button>
      </div>
      {data?.length ? (
        <ResponsiveContainer width="100%" height={330}>
          <BarChart data={data} margin={{ top: 16, right: 12, left: 8, bottom: 64 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--divider)" vertical={false} />
            <XAxis dataKey="type" angle={-32} textAnchor="end" height={92} tick={{ fill: 'var(--text-primary)', fontSize: 11 }} axisLine={false} tickLine={false} interval={0} />
            <YAxis tick={{ fill: '#94a3b8', fontSize: 11 }} axisLine={false} tickLine={false} />
            <Tooltip
              formatter={(value, name) => {
                if (name === 'open') return [`${value}`, 'Open issues']
                if (name === 'closed') return [`${value}`, 'Closed issues']
                return [`${value}`, 'CxA to Verify']
              }}
              contentStyle={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 10 }}
            />
            <Bar dataKey="open" fill="#f87171" radius={[6, 6, 0, 0]}>
              <LabelList dataKey="open" position="top" formatter={(value) => (value ? value : '')} style={{ fill: '#fca5a5', fontSize: 10, fontWeight: 700 }} />
            </Bar>
            <Bar dataKey="closed" fill="#22c55e" radius={[6, 6, 0, 0]}>
              <LabelList dataKey="closed" position="top" formatter={(value) => (value ? value : '')} style={{ fill: '#86efac', fontSize: 10, fontWeight: 700 }} />
            </Bar>
            <Bar dataKey="cxaToVerify" fill="#a855f7" radius={[6, 6, 0, 0]}>
              <LabelList dataKey="cxaToVerify" position="top" formatter={(value) => (value ? value : '')} style={{ fill: '#d8b4fe', fontSize: 10, fontWeight: 700 }} />
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      ) : (
        <div style={{ fontSize: 12, color: '#475569', padding: '20px 0' }}>No linked equipment-type issue data yet</div>
      )}
    </div>
  )
}

function TopEquipmentIssuesCard({ data }) {
  const exportRows = (data || []).map((row) => ({
    equipment_type: row.type,
    total_issues: row.total,
    open_issues: row.open,
    closed_issues: row.closed,
    cxa_to_verify: row.cxaToVerify,
  }))
  const max = Math.max(...(data || []).map((row) => row.total), 1)

  return (
    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, padding: '18px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
        <div>
          <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 2 }}>Top 5 Equipment Types Most Issues</div>
          <div style={{ fontSize: 11, color: '#64748b' }}>Highest issue concentration by equipment type</div>
        </div>
        <button onClick={() => exportCsv('top-5-equipment-types-most-issues.csv', exportRows)} style={{ padding: '7px 12px', borderRadius: 8, background: 'transparent', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>
          Export
        </button>
      </div>
      {data?.length ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {data.map((row, index) => (
            <div key={`${row.type}-${index}`} style={{ padding: '12px 14px', borderRadius: 10, background: 'rgba(255,255,255,0.03)', border: '1px solid var(--border)' }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10, marginBottom: 8 }}>
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-primary)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{row.type}</div>
                  <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 6 }}>
                    <span style={{ fontSize: 11, color: '#f87171', fontWeight: 700 }}>{row.open} open</span>
                    <span style={{ fontSize: 11, color: '#22c55e', fontWeight: 700 }}>{row.closed} closed</span>
                    <span style={{ fontSize: 11, color: '#a855f7', fontWeight: 700 }}>{row.cxaToVerify} CxA to verify</span>
                  </div>
                </div>
                <div style={{ fontSize: 22, fontWeight: 800, color: '#60a5fa', flexShrink: 0 }}>{row.total}</div>
              </div>
              <div style={{ height: 6, background: 'var(--divider)', borderRadius: 999, overflow: 'hidden' }}>
                <div style={{ width: `${(row.total / max) * 100}%`, height: '100%', background: 'linear-gradient(90deg, #60a5fa, #3b82f6)', borderRadius: 999, transition: 'width 0.4s ease' }} />
              </div>
            </div>
          ))}
        </div>
      ) : (
        <div style={{ fontSize: 12, color: '#475569', padding: '20px 0' }}>No equipment-type issue groups to rank yet</div>
      )}
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
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, padding: '18px 20px' }}>
        <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 2 }}>Cross-Company Issue Closures</div>
        <div style={{ fontSize: 11, color: '#64748b', marginBottom: 16 }}>Issues raised by one company but closed by a different company · {radar.crossCompanyClosures.length} major routes shown</div>
        {radar.crossCompanyClosures?.length ? (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 16 }}>
            {radar.crossCompanyClosures.map((row, index) => (
              <div key={`${row.raisedBy}-${row.closedBy}-${index}`} style={{ background: 'rgba(245,158,11,0.06)', border: '1px solid rgba(245,158,11,0.25)', borderRadius: 12, padding: '16px 18px' }}>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ fontSize: 11, fontWeight: 700, color: '#60a5fa', background: 'rgba(96,165,250,0.15)', borderRadius: 999, padding: '4px 8px' }}>Raised</span>
                    <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)' }}>{row.raisedBy}</span>
                  </div>
                  <div style={{ fontSize: 11, color: '#fbbf24', fontWeight: 700 }}>Closed by</div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{ fontSize: 11, fontWeight: 700, color: '#34d399', background: 'rgba(52,211,153,0.15)', borderRadius: 999, padding: '4px 8px' }}>Closed</span>
                    <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)' }}>{row.closedBy}</span>
                  </div>
                  <div style={{ height: 1, background: 'rgba(245,158,11,0.2)' }} />
                  <div style={{ fontSize: 34, fontWeight: 800, color: '#fbbf24', lineHeight: 1 }}>{row.count}</div>
                  <div style={{ fontSize: 12, color: '#94a3b8' }}>issues</div>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div style={{ fontSize: 12, color: '#475569', padding: '12px 0' }}>No cross-company closures detected yet.</div>
        )}
      </div>

      {radar.crossCompanySummary?.length > 0 && (
        <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, overflow: 'hidden' }}>
          <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--divider)' }}>
            <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)' }}>Company Involvement Summary</div>
            <div style={{ fontSize: 11, color: '#64748b', marginTop: 2 }}>Issues raised by others vs issues closed for others</div>
          </div>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ borderBottom: '1px solid var(--divider)' }}>
                  <th style={{ padding: '10px 16px', textAlign: 'left', fontSize: 10, fontWeight: 600, color: '#475569', textTransform: 'uppercase' }}>COMPANY</th>
                  <th style={{ padding: '10px 12px', textAlign: 'right', fontSize: 10, fontWeight: 600, color: '#475569', textTransform: 'uppercase' }}>ISSUES RAISED (CLOSED BY OTHERS)</th>
                  <th style={{ padding: '10px 12px', textAlign: 'right', fontSize: 10, fontWeight: 600, color: '#475569', textTransform: 'uppercase' }}>ISSUES CLOSED (RAISED BY OTHERS)</th>
                  <th style={{ padding: '10px 12px', textAlign: 'right', fontSize: 10, fontWeight: 600, color: '#475569', textTransform: 'uppercase' }}>NET</th>
                </tr>
              </thead>
              <tbody>
                {radar.crossCompanySummary.map((row, i) => (
                  <tr key={i} style={{ borderBottom: '1px solid var(--divider)', background: i % 2 === 1 ? 'var(--row-alt)' : 'transparent' }}>
                    <td style={{ padding: '11px 16px', fontSize: 12, color: 'var(--text-primary)', fontWeight: 600 }}>{row.company}</td>
                    <td style={{ padding: '11px 12px', textAlign: 'right', fontSize: 13, fontWeight: 700, color: '#60a5fa' }}>{row.raisedClosedByOthers}</td>
                    <td style={{ padding: '11px 12px', textAlign: 'right', fontSize: 13, fontWeight: 700, color: '#34d399' }}>{row.closedRaisedByOthers}</td>
                    <td style={{ padding: '11px 12px', textAlign: 'right', fontSize: 13, fontWeight: 700, color: row.net >= 0 ? '#34d399' : '#f87171' }}>
                      {row.net > 0 ? '+' : ''}{row.net}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  )
}

// ─── AI Analysis Tab — backend-managed OpenAI ────────────────────────────────

function AICopilot({ radar, issues }) {
  const [messages, setMessages]   = useState([])
  const [input,    setInput]      = useState('')
  const [thinking, setThinking]   = useState(false)
  const [copilotConfig, setCopilotConfig] = useState(null)
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
- Status matrix: IssueOpened=${radar.matrixRows?.find(r=>r.status==='Issue Opened')?.total||0}, CorrectionInProgress=${radar.matrixRows?.find(r=>r.status==='Correction in Progress')?.total||0}, GCToVerify=${radar.matrixRows?.find(r=>r.status==='GC to Verify')?.total||0}, CxAToVerify=${radar.matrixRows?.find(r=>r.status==='CxA to verify')?.total||0}, IssueClosed=${radar.matrixRows?.find(r=>r.status==='Issue Closed')?.total||0}, AcceptedByOwner=${radar.matrixRows?.find(r=>r.status==='Accepted by Owner')?.total||0}, Recommendation=${radar.matrixRows?.find(r=>r.status==='Recommendation')?.total||0}

Be concise, actionable, and construction/commissioning focused. Keep answers under 4 sentences unless a step-by-step plan is requested.`

  useEffect(() => {
    setMessages([{
      role: 'assistant',
      text: `Issue Copilot online. I see ${radar.totalRows} total issues — ${radar.openCount} open, ${radar.highCycleIssues.length} high-cycle. Quality score: ${radar.qualityScore} (${radar.qualityGrade}). Primary root-cause signal is "${radar.topRootCause?.cause || 'N/A'}" and top open-load owner is ${radar.topOwner?.company || 'Unassigned'}.`,
    }])
  }, [radar.totalRows, radar.openCount])

  useEffect(() => {
    let cancelled = false
    copilotApi.getConfig()
      .then(({ data }) => {
        if (!cancelled) {
          setCopilotConfig(data.data || null)
        }
      })
      .catch(() => {
        if (!cancelled) {
          setCopilotConfig(null)
        }
      })
    return () => { cancelled = true }
  }, [])

  useEffect(() => { endRef.current?.scrollIntoView({ behavior: 'smooth' }) }, [messages])

  async function ask(q) {
    const text = (q || input).trim()
    if (!text) return
    if (!copilotConfig?.configured) { alert('OpenAI is not configured on the server yet.'); return }
    setInput('')
    setMessages(m => [...m, { role: 'user', text }])
    setThinking(true)
    try {
      const resp = await copilotApi.chat({
        payload: {
          instructions: systemPrompt,
          prompt: text,
          includeProjectFiles: false,
          conversation: messages,
        },
      })
      const reply = resp.data?.data?.answer || 'No response received.'
      setMessages(m => [...m, { role: 'assistant', text: reply }])
    } catch (err) {
      const message = err.response?.data?.message || err.message || 'Request failed.'
      setMessages(m => [...m, { role: 'assistant', text: `Error: ${message}` }])
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

      <div style={{ background: copilotConfig?.configured ? 'rgba(34,197,94,0.08)' : 'rgba(248,113,113,0.08)', border: copilotConfig?.configured ? '1px solid rgba(34,197,94,0.2)' : '1px solid rgba(248,113,113,0.2)', borderRadius: 10, padding: '14px 18px', display: 'flex', justifyContent: 'space-between', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
          <div>
            <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-primary)' }}>
              {copilotConfig?.configured ? 'AI is configured on the backend' : 'AI backend configuration is missing'}
            </div>
            <div style={{ fontSize: 11, color: '#94a3b8', marginTop: 4 }}>
              {copilotConfig?.configured
                ? `Using ${copilotConfig.defaultModel || 'the configured model'} without exposing any browser-side API key.`
                : 'Set OPENAI_API_KEY in the backend environment and restart the backend to enable AI analysis here.'}
            </div>
          </div>
          <div style={{ padding: '6px 10px', borderRadius: 999, border: '1px solid var(--border)', background: 'var(--bg-base)', color: '#cbd5e1', fontSize: 11, fontWeight: 700 }}>
            Model: {copilotConfig?.defaultModel || 'Unavailable'}
          </div>
        </div>
      {/* Chat window */}
      <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, overflow: 'hidden' }}>
        <div style={{ padding: '14px 18px', borderBottom: '1px solid var(--divider)', display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div>
            <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' }}>AI Issue Copilot</div>
            <div style={{ fontSize: 11, color: '#64748b', marginTop: 2 }}>Powered by backend OpenAI - Chat-style root cause and action analysis</div>
          </div>
          <div style={{ fontSize: 11, color: '#94a3b8' }}>{copilotConfig?.defaultModel || 'No model configured'}</div>
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
          <button onClick={() => ask()} disabled={thinking || !input.trim() || !copilotConfig?.configured}
            style={{ padding: '9px 18px', borderRadius: 8, background: '#3b82f6', border: 'none', color: '#fff', fontSize: 12, fontWeight: 700, cursor: 'pointer', opacity: (!input.trim() || !copilotConfig?.configured) ? 0.5 : 1 }}>
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
  const { issues, equipment, loading, error } = useIssueData()
  const radar = useMemo(() => computeRadar(issues, equipment), [issues, equipment])
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

      {radar.totalRows > 0 && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 14 }}>
          {[
            { label: 'Total Issues', value: radar.totalRows, sub: 'All synced issues', color: '#60a5fa' },
            { label: 'Closed Issues', value: radar.closedCount, sub: `${radar.totalRows ? Math.round((radar.closedCount / radar.totalRows) * 100) : 0}% of total`, color: '#22c55e' },
            { label: 'Open Issues', value: radar.openCount, sub: `${radar.totalRows ? Math.round((radar.openCount / radar.totalRows) * 100) : 0}% of total`, color: '#f87171' },
            { label: 'Avg Issue Closure Time', value: `${radar.avgClosureTime.toFixed(2)}d`, sub: 'Based on assigned_to closures', color: '#f59e0b' },
          ].map(card => (
            <div key={card.label} style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, padding: '16px 18px' }}>
              <div style={{ fontSize: 10, color: '#475569', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 6 }}>{card.label}</div>
              <div style={{ fontSize: 28, fontWeight: 800, color: card.color, lineHeight: 1 }}>{card.value}</div>
              <div style={{ fontSize: 11, color: '#64748b', marginTop: 4 }}>{card.sub}</div>
            </div>
          ))}
        </div>
      )}

      {/* Summary insight bar */}
      {radar.totalRows > 0 && (
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
          {[
            { label: 'Avg Close Leader', value: radar.topCloseCompany ? `${radar.topCloseCompany.company} (${radar.topCloseCompany.avgDays}d)` : 'N/A' },
            { label: 'Top Equipment Type', value: radar.topEquipmentType ? `${radar.topEquipmentType.type} (${radar.topEquipmentType.count} open)` : 'N/A' },
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

