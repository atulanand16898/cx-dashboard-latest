import React, { useEffect, useMemo, useState } from 'react'
import { ChevronDown, ChevronRight, Search } from 'lucide-react'

const C = {
  approved: '#22c55e',
  approvedBg: 'rgba(34,197,94,0.08)',
  approvedBorder: 'rgba(34,197,94,0.18)',
  progress: '#eab308',
  progressBg: 'rgba(234,179,8,0.08)',
  progressBorder: 'rgba(234,179,8,0.18)',
  muted: '#64748b',
  dim: '#334155',
  danger: '#f87171',
  dangerBg: 'rgba(248,113,113,0.08)',
  dangerBorder: 'rgba(248,113,113,0.18)',
  info: '#60a5fa',
  infoBg: 'rgba(96,165,250,0.08)',
  infoBorder: 'rgba(96,165,250,0.18)',
  text: 'var(--text-primary)',
  border: 'var(--border)',
  divider: 'var(--divider)',
  card: 'var(--bg-card)',
}

function tdS() {
  return { padding: 0, borderRight: `1px solid ${C.divider}`, verticalAlign: 'top' }
}

function badgeS(bg, border, color) {
  return {
    fontSize: 10,
    padding: '2px 8px',
    borderRadius: 999,
    background: bg,
    border: `1px solid ${border}`,
    color,
    fontWeight: 600,
  }
}

const TH = {
  padding: '11px 14px',
  fontSize: 10,
  fontWeight: 700,
  color: C.muted,
  textTransform: 'uppercase',
  letterSpacing: '0.07em',
  borderRight: `1px solid ${C.divider}`,
  whiteSpace: 'nowrap',
  background: 'var(--bg-card)',
  position: 'sticky',
  top: 0,
  zIndex: 2,
}

function Pill({ label }) {
  return (
    <span
      style={{
        fontSize: 11,
        fontWeight: 600,
        padding: '4px 11px',
        borderRadius: 999,
        background: 'rgba(255,255,255,0.06)',
        color: '#94a3b8',
        border: `1px solid ${C.border}`,
      }}
    >
      {label}
    </span>
  )
}

function selS() {
  return {
    background: 'rgba(255,255,255,0.05)',
    border: `1px solid ${C.border}`,
    borderRadius: 7,
    padding: '7px 10px',
    fontSize: 12,
    color: 'var(--text-primary)',
    cursor: 'pointer',
    outline: 'none',
  }
}

function ChecklistCell({ stat, accent }) {
  if (!stat || stat.total === 0) {
    return (
      <td style={tdS()}>
        <div style={{ padding: '14px', minHeight: 88, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <span style={{ fontSize: 12, color: C.dim }}>-</span>
        </div>
      </td>
    )
  }

  const approved = stat.statusLabel === 'Checklist Approved'
  const inProgress = stat.statusLabel === 'In Progress'
  const labelColor = approved ? C.approved : inProgress ? C.progress : C.muted
  const borderColor = approved ? C.approvedBorder : inProgress ? C.progressBorder : 'rgba(255,255,255,0.08)'
  const bgColor = approved ? C.approvedBg : inProgress ? C.progressBg : 'rgba(255,255,255,0.03)'

  return (
    <td style={tdS()}>
      <div
        style={{
          padding: '10px 14px',
          minHeight: 88,
          margin: 3,
          borderRadius: 8,
          background: bgColor,
          border: `1px solid ${borderColor}`,
        }}
      >
        <div style={{ fontSize: 11, fontWeight: 700, color: labelColor, marginBottom: 4 }}>
          {stat.statusLabel}
        </div>
        <div style={{ fontSize: 10, color: C.muted, marginBottom: 8 }}>
          {stat.closed}/{stat.total} closed
          {stat.issueCount > 0 && <span style={{ color: C.danger, marginLeft: 5 }}>| {stat.issueCount} issues</span>}
        </div>
        <div style={{ height: 4, background: 'rgba(255,255,255,0.07)', borderRadius: 999, overflow: 'hidden' }}>
          <div
            style={{
              width: `${stat.completionPct || 0}%`,
              height: '100%',
              background: accent,
              borderRadius: 999,
              transition: 'width 0.3s ease',
            }}
          />
        </div>
      </div>
    </td>
  )
}

function MetricCell({ total, closed, kind }) {
  if (!total) {
    return (
      <td style={tdS()}>
        <div style={{ padding: '14px', minHeight: 88, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <span style={{ fontSize: 12, color: C.dim }}>-</span>
        </div>
      </td>
    )
  }

  const open = Math.max(0, total - closed)
  const isIssues = kind === 'issues'
  const accent = isIssues ? (open > 0 ? C.danger : C.approved) : C.info
  const bg = isIssues ? (open > 0 ? C.dangerBg : C.approvedBg) : C.infoBg
  const border = isIssues ? (open > 0 ? C.dangerBorder : C.approvedBorder) : C.infoBorder
  const headline = isIssues
    ? (open > 0 ? `${open} open` : `${closed} closed`)
    : `${closed}/${total} closed`

  return (
    <td style={tdS()}>
      <div
        style={{
          padding: '10px 14px',
          minHeight: 88,
          margin: 3,
          borderRadius: 8,
          background: bg,
          border: `1px solid ${border}`,
        }}
      >
        <div style={{ fontSize: 11, fontWeight: 700, color: accent, marginBottom: 4 }}>{headline}</div>
        <div style={{ fontSize: 10, color: C.muted }}>
          {isIssues ? `${closed}/${total} closed` : `${total} total`}
        </div>
      </div>
    </td>
  )
}

function EquipmentRow({ row, idx }) {
  const fullyClosed = row.checklistTotal > 0 && row.checklistTotal === row.checklistClosed
  const unassigned = !row.status || row.status === 'Not Assigned'
  const statusColor = fullyClosed ? C.approved : unassigned ? C.muted : C.progress
  const statusBg = fullyClosed ? 'rgba(34,197,94,0.12)' : unassigned ? 'rgba(100,116,139,0.18)' : 'rgba(234,179,8,0.12)'
  const detailParts = [row.systemName, row.spaceName, row.assignedTo]
    .map(value => String(value || '').trim())
    .filter(Boolean)
  const uniqueDetailParts = detailParts.filter((value, index) => detailParts.indexOf(value) === index)

  return (
    <tr
      style={{
        background: idx % 2 === 0 ? 'transparent' : 'rgba(255,255,255,0.012)',
        borderBottom: `1px solid ${C.divider}`,
      }}
    >
      <td style={{ ...tdS(), minWidth: 250 }}>
        <div style={{ padding: '11px 16px' }}>
          <div style={{ fontSize: 12, fontWeight: 700, color: C.text, marginBottom: 4 }}>
            {row.name || row.tag || row.externalId || 'Unnamed'}
          </div>
          <div style={{ fontSize: 10, color: C.muted, marginBottom: 6 }}>
            <span style={{ padding: '1px 7px', borderRadius: 999, background: statusBg, color: statusColor, fontWeight: 600 }}>
              {row.status || 'Not Assigned'}
            </span>
            {uniqueDetailParts.length > 0 && (
              <span style={{ marginLeft: 6 }}>{uniqueDetailParts.join(' • ')}</span>
            )}
          </div>
          <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap' }}>
            {row.checklistTotal > 0 && (
              <span style={badgeS('rgba(34,197,94,0.1)', 'rgba(34,197,94,0.25)', C.approved)}>
                {row.checklistClosed}/{row.checklistTotal} closed
              </span>
            )}
            {(row.issuesOpen + row.issuesClosed) > 0 && (
              <span style={badgeS('rgba(248,113,113,0.1)', 'rgba(248,113,113,0.25)', C.danger)}>
                Issues {row.issuesClosed}/{row.issuesOpen + row.issuesClosed} closed
              </span>
            )}
            {row.testsTotal > 0 && (
              <span style={badgeS('rgba(96,165,250,0.1)', 'rgba(96,165,250,0.25)', C.info)}>
                Tests {row.testsClosed}/{row.testsTotal}
              </span>
            )}
          </div>
        </div>
      </td>

      <ChecklistCell stat={row.l1Checklist} accent="#ef4444" />
      <ChecklistCell stat={row.l2Checklist} accent="#eab308" />
      <ChecklistCell stat={row.l3Checklist} accent="#22c55e" />
      <MetricCell total={row.issuesOpen + row.issuesClosed} closed={row.issuesClosed} kind="issues" />
      <MetricCell total={row.testsTotal} closed={row.testsClosed} kind="tests" />

      <td style={{ ...tdS(), textAlign: 'center', verticalAlign: 'middle', borderRight: 'none' }}>
        <span
          style={{
            fontSize: 14,
            fontWeight: 800,
            color: C.text,
            background: 'rgba(59,130,246,0.12)',
            border: '1px solid rgba(59,130,246,0.25)',
            padding: '5px 12px',
            borderRadius: 8,
            display: 'inline-block',
          }}
        >
          {row.total || 0}
        </span>
      </td>
    </tr>
  )
}

function TypeGroupRow({ typeName, rows, isExpanded, onToggle }) {
  const units = rows.length
  const closedUnits = rows.filter(row => row.checklistTotal > 0 && row.checklistTotal === row.checklistClosed).length
  const totalChecklists = rows.reduce((sum, row) => sum + (row.checklistTotal || 0), 0)
  const closedChecklists = rows.reduce((sum, row) => sum + (row.checklistClosed || 0), 0)
  const totalTests = rows.reduce((sum, row) => sum + (row.testsTotal || 0), 0)
  const closedTests = rows.reduce((sum, row) => sum + (row.testsClosed || 0), 0)
  const openIssues = rows.reduce((sum, row) => sum + (row.issuesOpen || 0), 0)
  const closedIssues = rows.reduce((sum, row) => sum + (row.issuesClosed || 0), 0)
  const completion = totalChecklists > 0 ? Math.round((closedChecklists / totalChecklists) * 100) : 0

  return (
    <>
      <tr
        style={{ background: 'rgba(255,255,255,0.03)', borderBottom: `1px solid ${C.divider}`, cursor: 'pointer' }}
        onClick={onToggle}
      >
        <td colSpan={7} style={{ padding: 0 }}>
          <div style={{ padding: '10px 16px 0 16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
              <div style={{ width: 18, height: 18, borderRadius: 4, background: 'rgba(255,255,255,0.07)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                {isExpanded ? <ChevronDown size={11} color="#94a3b8" /> : <ChevronRight size={11} color="#94a3b8" />}
              </div>
              <div style={{ width: 8, height: 8, borderRadius: '50%', background: '#60a5fa', flexShrink: 0 }} />
              <span style={{ fontSize: 13, fontWeight: 700, color: C.text }}>{typeName}</span>
              <span style={{ fontSize: 11, color: C.muted }}>{units} units | {closedUnits} fully closed</span>

              <div style={{ display: 'flex', gap: 5, marginLeft: 6, flexWrap: 'wrap' }}>
                {totalChecklists > 0 && (
                  <span style={badgeS('rgba(34,197,94,0.1)', 'rgba(34,197,94,0.25)', C.approved)}>
                    Checklists {closedChecklists}/{totalChecklists}
                  </span>
                )}
                {(openIssues + closedIssues) > 0 && (
                  <span style={badgeS('rgba(248,113,113,0.1)', 'rgba(248,113,113,0.25)', C.danger)}>
                    Issues {closedIssues}/{openIssues + closedIssues}
                  </span>
                )}
                {totalTests > 0 && (
                  <span style={badgeS('rgba(224,181,95,0.12)', 'rgba(224,181,95,0.25)', '#e0b55f')}>
                    Tests {closedTests}/{totalTests}
                  </span>
                )}
              </div>
            </div>

            <div style={{ fontSize: 10, color: C.muted, marginTop: 5, marginLeft: 26, marginBottom: 6 }}>
              Issue Split&nbsp;&nbsp;
              <span style={{ color: openIssues > 0 ? C.danger : C.muted }}>{openIssues} open</span>
              {' / '}
              <span style={{ color: closedIssues > 0 ? C.approved : C.muted }}>{closedIssues} closed</span>
            </div>
          </div>

          <div style={{ height: 3, background: 'rgba(255,255,255,0.05)', overflow: 'hidden' }}>
            <div style={{ width: `${completion}%`, height: '100%', background: completion === 100 ? C.approved : C.progress, transition: 'width 0.5s' }} />
          </div>
        </td>
      </tr>

      {isExpanded && rows.map((row, index) => (
        <EquipmentRow key={row.equipmentId || row.externalId || index} row={row} idx={index} />
      ))}
    </>
  )
}

export default function EquipmentChecklistMatrix({
  matrix,
  loading,
  searchValue,
  onSearchChange,
  statusFilterValue,
  onStatusFilterChange,
  focusLabel,
}) {
  const [internalSearch, setInternalSearch] = useState('')
  const [internalStatusFilter, setInternalStatusFilter] = useState('All Statuses')
  const [expanded, setExpanded] = useState({})

  const search = searchValue ?? internalSearch
  const statusFilter = statusFilterValue ?? internalStatusFilter

  const setSearch = onSearchChange ?? setInternalSearch
  const setStatusFilter = onStatusFilterChange ?? setInternalStatusFilter

  const statusOptions = useMemo(() => {
    if (!matrix?.rows) return ['All Statuses']
    return ['All Statuses', ...new Set(matrix.rows.map(row => row.status).filter(Boolean))]
  }, [matrix])

  const knownTypes = useMemo(() => {
    if (!matrix?.rows) return new Set()
    return new Set(matrix.rows.map(row => row.equipmentType).filter(Boolean))
  }, [matrix])

  const groupNameForRow = row => {
    if (row.equipmentType) return row.equipmentType

    const name = (row.name || '').toLowerCase()
    if (name.includes('crah')) return 'CRAH'
    if (name.includes('fwu') || name.includes('fan wall')) return 'Fan wall Unit'
    if (row.systemName && knownTypes.has(row.systemName)) return row.systemName
    return 'Non Critical Equipment'
  }

  const filtered = useMemo(() => {
    if (!matrix?.rows) return []
    const query = search.trim().toLowerCase()
    return matrix.rows.filter(row => {
      const haystack = [
        row.name,
        row.tag,
        row.externalId,
        groupNameForRow(row),
        row.systemName,
        row.spaceName,
        row.assignedTo,
      ].filter(Boolean).join(' ').toLowerCase()

      if (query && !haystack.includes(query)) return false
      if (statusFilter !== 'All Statuses' && row.status !== statusFilter) return false
      return true
    })
  }, [matrix, search, statusFilter])

  const grouped = useMemo(() => {
    const groups = new Map()
    filtered.forEach(row => {
      const key = groupNameForRow(row)
      if (!groups.has(key)) groups.set(key, [])
      groups.get(key).push(row)
    })
    return [...groups.entries()].sort(([a], [b]) => a.localeCompare(b))
  }, [filtered])

  const toggle = typeName => setExpanded(prev => ({ ...prev, [typeName]: !(prev[typeName] ?? false) }))

  useEffect(() => {
    if (!focusLabel) return
    setExpanded((prev) => ({ ...prev, [focusLabel]: true }))
  }, [focusLabel])

  if (loading) {
    return (
      <div style={{ background: C.card, border: `1px solid ${C.border}`, borderRadius: 14, padding: 36, textAlign: 'center', color: C.muted }}>
        <div style={{ width: 26, height: 26, border: '3px solid #1e293b', borderTopColor: '#60a5fa', borderRadius: '50%', animation: 'spin 0.8s linear infinite', margin: '0 auto 12px' }} />
        Loading equipment matrix...
        <style>{'@keyframes spin{to{transform:rotate(360deg)}}'}</style>
      </div>
    )
  }

  if (!matrix?.rows?.length) {
    return (
      <div style={{ background: C.card, border: `1px solid ${C.border}`, borderRadius: 14, padding: 40, textAlign: 'center', color: C.muted }}>
        No equipment checklist data available. Sync equipment, checklists, and issues for the selected project first.
      </div>
    )
  }

  return (
    <div style={{ background: C.card, border: `1px solid ${C.border}`, borderRadius: 14, overflow: 'hidden' }}>
      <div style={{ padding: '18px 20px 14px', borderBottom: `1px solid ${C.divider}` }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
          <div>
            <div style={{ fontSize: 15, fontWeight: 700, color: C.text, marginBottom: 4 }}>Equipment-Checklist Matrix</div>
            <div style={{ fontSize: 12, color: C.muted }}>
              Cross-reference of equipment and checklist status progression
              {focusLabel ? <span style={{ color: '#dbeafe' }}> • drilled into {focusLabel}</span> : null}
            </div>
          </div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
            <Pill label={`${matrix.totalUnits} units`} />
            <Pill label={`${matrix.totalSystems} systems`} />
            <Pill label={`${matrix.totalTypes} types`} />
          </div>
        </div>

        <div style={{ display: 'flex', gap: 10, marginTop: 14, flexWrap: 'wrap', alignItems: 'center', justifyContent: 'flex-end' }}>
          <div style={{ position: 'relative', minWidth: 180 }}>
            <Search size={13} style={{ position: 'absolute', left: 9, top: '50%', transform: 'translateY(-50%)', color: C.muted }} />
            <input
              value={search}
              onChange={event => setSearch(event.target.value)}
              placeholder="Search equipment..."
              style={{ width: '100%', boxSizing: 'border-box', background: 'rgba(255,255,255,0.04)', border: `1px solid ${C.border}`, borderRadius: 7, padding: '7px 10px 7px 28px', fontSize: 12, color: 'var(--text-primary)', outline: 'none' }}
            />
          </div>
          <select value={statusFilter} onChange={event => setStatusFilter(event.target.value)} style={selS()}>
            {statusOptions.map(option => <option key={option}>{option}</option>)}
          </select>
        </div>
      </div>

      <div style={{ overflowX: 'auto', maxHeight: 680, overflowY: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'fixed', minWidth: 1080 }}>
          <colgroup>
            <col style={{ width: '25%' }} />
            <col style={{ width: '13%' }} />
            <col style={{ width: '13%' }} />
            <col style={{ width: '13%' }} />
            <col style={{ width: '12%' }} />
            <col style={{ width: '12%' }} />
            <col style={{ width: '8%' }} />
          </colgroup>
          <thead>
            <tr>
              <th style={{ ...TH, textAlign: 'left' }}>Equipment / System</th>
              <th style={{ ...TH, color: '#ef4444' }}>Red Tags</th>
              <th style={{ ...TH, color: '#eab308' }}>Yellow Tags</th>
              <th style={{ ...TH, color: '#22c55e' }}>Green Tags</th>
              <th style={{ ...TH, color: C.danger }}>Issues</th>
              <th style={{ ...TH, color: '#e0b55f' }}>Tests</th>
              <th style={{ ...TH, textAlign: 'center', borderRight: 'none' }}>Total</th>
            </tr>
          </thead>
          <tbody>
            {grouped.map(([typeName, rows]) => (
              <TypeGroupRow
                key={typeName}
                typeName={typeName}
                rows={rows}
                isExpanded={expanded[typeName] === true}
                onToggle={() => toggle(typeName)}
              />
            ))}
          </tbody>
        </table>
        {grouped.length === 0 && (
          <div style={{ padding: 40, textAlign: 'center', color: C.muted, fontSize: 13 }}>
            No equipment matches the current filters.
          </div>
        )}
      </div>
    </div>
  )
}
