/**
 * EquipmentChecklistMatrix — matches modum.me screenshots exactly.
 *
 * Layout: EQUIPMENT/SYSTEM | RED TAGS | YELLOW TAGS | GREEN TAGS | L2 VENDOR | L3 VENDOR | TOTAL
 *
 * Key visual rules from screenshots:
 *  - Checklist Approved cells: dark green background, bold "Checklist Approved" in green, green progress bar
 *  - In Progress cells: yellow tint, yellow bar
 *  - Not Started cells: muted grey, no bar fill
 *  - L2/L3 Vendor cells: show MECHANICAL/ELECTRICAL + "Ready" (no tinted background)
 *  - System group header: blue dot + name + "X units · Closed" + badge pills (Issues/Checklists/Tests)
 *    + "Issue Split: X open / Y closed" + full-width green progress bar at bottom
 *  - Equipment row: tag/name, status pill (Closed=green, Unassigned=grey), checklists/issues/tests badges
 *
 * Data source: GET /api/equipment/matrix?projectId=X → EquipmentMatrixDto
 *   systemName is now equipmentType (BMS Panels, Generator, FAHU System, etc.)
 */
import React, { useState, useMemo } from 'react'
import { ChevronDown, ChevronRight, Search } from 'lucide-react'

// ── Color palette ─────────────────────────────────────────────────────────────
const C = {
  approved:    '#22c55e',
  approvedBg:  'rgba(34,197,94,0.12)',
  approvedBdr: 'rgba(34,197,94,0.25)',
  inProgress:  '#eab308',
  inProgressBg:'rgba(234,179,8,0.10)',
  notStarted:  '#475569',
  red:   '#ef4444',
  yellow:'#eab308',
  green: '#22c55e',
  blue:  '#3b82f6',
  purple:'#a855f7',
  text:  'var(--text-primary)',
  border:'var(--border)',
  divider:'var(--divider)',
  card:  'var(--bg-card)',
  muted: '#64748b',
  dim:   '#334155',
}

const COL = { red: C.red, yellow: C.yellow, green: C.green, l2: C.blue, l3: C.purple }

// ── ChecklistCell — matches the green "Checklist Approved" cards in screenshot ──
function ChecklistCell({ stat, colColor }) {
  // Empty cell — no checklists at this tag level for this equipment
  if (!stat || stat.total === 0) {
    return (
      <td style={tdS()}>
        <div style={{ padding: '14px', minHeight: 80, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <span style={{ fontSize: 12, color: C.dim }}>—</span>
        </div>
      </td>
    )
  }

  const isApproved   = stat.statusLabel === 'Checklist Approved'
  const isInProgress = stat.statusLabel === 'In Progress'
  const pct = stat.total > 0 ? Math.round((stat.closed / stat.total) * 100) : 0

  const labelColor = isApproved ? C.approved : isInProgress ? C.inProgress : C.notStarted
  const barColor   = isApproved ? C.approved : isInProgress ? C.inProgress : 'rgba(255,255,255,0.08)'
  const cellBg     = isApproved
    ? 'rgba(34,197,94,0.07)'
    : isInProgress
    ? 'rgba(234,179,8,0.06)'
    : 'transparent'
  const cellBorder = isApproved
    ? '1px solid rgba(34,197,94,0.15)'
    : isInProgress
    ? '1px solid rgba(234,179,8,0.12)'
    : '1px solid transparent'

  return (
    <td style={tdS()}>
      <div style={{
        padding: '10px 14px', minHeight: 80,
        background: cellBg,
        border: cellBorder,
        borderRadius: 6, margin: 3,
      }}>
        {/* Status label */}
        <div style={{ fontSize: 11, fontWeight: 700, color: labelColor, marginBottom: 3 }}>
          {stat.statusLabel}
        </div>
        {/* X/Y closed | Z issues closed */}
        <div style={{ fontSize: 10, color: C.muted, marginBottom: 7 }}>
          {stat.closed}/{stat.total} closed
          {stat.issueCount > 0 && (
            <span style={{ color: '#f87171', marginLeft: 4 }}>| {stat.issueCount} issues</span>
          )}
          {stat.issuesClosed > 0 && (
            <span style={{ color: C.muted, marginLeft: 4 }}>| {stat.issuesClosed} issues closed</span>
          )}
        </div>
        {/* Progress bar */}
        <div style={{ height: 4, background: 'rgba(255,255,255,0.07)', borderRadius: 99, overflow: 'hidden' }}>
          <div style={{ width: `${pct}%`, height: '100%', background: barColor, borderRadius: 99, transition: 'width 0.4s' }} />
        </div>
        {/* Fraction */}
        <div style={{ fontSize: 10, color: C.muted, marginTop: 5 }}>
          {stat.closed}/{stat.total}
        </div>
      </div>
    </td>
  )
}

// ── VendorCell — shows MECHANICAL/ELECTRICAL + Ready, no tint ──────────────────
function VendorCell({ stat }) {
  // Always show vendor info from discipline (screenshot shows MECHANICAL/ELECTRICAL even without checklists)
  const vendorName   = stat?.vendorName   || null
  const vendorStatus = stat?.vendorStatus || 'Pending'
  const statusColor  = vendorStatus === 'Ready' ? C.approved : vendorStatus === 'In Progress' ? C.inProgress : C.muted

  if (!vendorName) {
    return <td style={tdS()}><div style={{ padding: '10px 14px', minHeight: 80 }} /></td>
  }

  return (
    <td style={tdS()}>
      <div style={{ padding: '10px 14px', minHeight: 80 }}>
        <div style={{ fontSize: 12, fontWeight: 700, color: C.text, marginBottom: 4 }}>
          {vendorName}
        </div>
        <div style={{ fontSize: 11, fontWeight: 600, color: statusColor }}>
          {vendorStatus}
        </div>
      </div>
    </td>
  )
}

// ── EquipmentRow ──────────────────────────────────────────────────────────────
function EquipmentRow({ row, idx }) {
  const isClosed     = row.status === 'Closed'
  const isUnassigned = row.status === 'Unassigned' || !row.status
  const statusColor  = isClosed ? C.approved : isUnassigned ? C.notStarted : C.inProgress
  const statusBg     = isClosed ? 'rgba(34,197,94,0.12)' : isUnassigned ? 'rgba(71,85,105,0.2)' : 'rgba(234,179,8,0.12)'

  const assignedLabel = row.assignedTo ? row.assignedTo : 'Unassigned'

  return (
    <tr style={{ background: idx % 2 === 0 ? 'transparent' : 'rgba(255,255,255,0.012)', borderBottom: `1px solid ${C.divider}` }}>
      {/* Equipment/System cell */}
      <td style={{ ...tdS(), minWidth: 230 }}>
        <div style={{ padding: '11px 16px' }}>
          {/* Tag / name */}
          <div style={{ fontSize: 12, fontWeight: 700, color: C.text, marginBottom: 4 }}>
            {row.tag || row.name || row.externalId || 'Unnamed'}
          </div>
          {/* Status + assigned */}
          <div style={{ fontSize: 10, color: C.muted, marginBottom: 6 }}>
            <span style={{ padding: '1px 7px', borderRadius: 99, background: statusBg, color: statusColor, fontWeight: 600 }}>
              {row.status || 'Unassigned'}
            </span>
            <span style={{ marginLeft: 6, color: C.muted }}>| {assignedLabel}</span>
          </div>
          {/* Badges: checklists / issues / tests */}
          <div style={{ display: 'flex', gap: 5, flexWrap: 'wrap' }}>
            {row.checklistTotal > 0 && (
              <span style={badgeS('rgba(34,197,94,0.1)', 'rgba(34,197,94,0.25)', C.approved)}>
                {row.checklistClosed}/{row.checklistTotal} closed
              </span>
            )}
            {row.issuesOpen > 0 && (
              <span style={badgeS('rgba(248,113,113,0.1)', 'rgba(248,113,113,0.25)', '#f87171')}>
                {row.issuesOpen} open
              </span>
            )}
            {(row.issuesClosed || 0) > 0 && (
              <span style={badgeS('rgba(100,116,139,0.1)', 'rgba(100,116,139,0.2)', C.muted)}>
                {row.issuesClosed} closed
              </span>
            )}
            {row.testsTotal > 0 && (
              <span style={badgeS('rgba(168,85,247,0.1)', 'rgba(168,85,247,0.25)', '#c084fc')}>
                Tests {row.testsClosed}/{row.testsTotal}
              </span>
            )}
          </div>
        </div>
      </td>

      <ChecklistCell stat={row.redTags}    colColor={COL.red}    />
      <ChecklistCell stat={row.yellowTags} colColor={COL.yellow} />
      <ChecklistCell stat={row.greenTags}  colColor={COL.green}  />
      <VendorCell    stat={row.l2Vendor} />
      <VendorCell    stat={row.l3Vendor} />

      {/* Total */}
      <td style={{ ...tdS(), textAlign: 'center', verticalAlign: 'middle', borderRight: 'none' }}>
        <span style={{
          fontSize: 14, fontWeight: 800, color: C.text,
          background: 'rgba(59,130,246,0.12)',
          border: '1px solid rgba(59,130,246,0.25)',
          padding: '5px 12px', borderRadius: 8, display: 'inline-block',
        }}>
          {row.total || row.checklistTotal || 0}
        </span>
      </td>
    </tr>
  )
}

// ── SystemGroupRow — matches BMS Panels / Generator / FAHU System headers ─────
function SystemGroupRow({ systemName, rows, isExpanded, onToggle }) {
  const units          = rows.length
  const closedUnits    = rows.filter(r => r.status === 'Closed').length
  const totalCL        = rows.reduce((s, r) => s + (r.checklistTotal || 0), 0)
  const closedCL       = rows.reduce((s, r) => s + (r.checklistClosed || 0), 0)
  const totalTests     = rows.reduce((s, r) => s + (r.testsTotal || 0), 0)
  const closedTests    = rows.reduce((s, r) => s + (r.testsClosed || 0), 0)
  const openIssues     = rows.reduce((s, r) => s + (r.issuesOpen || 0), 0)
  const closedIssues   = rows.reduce((s, r) => s + (r.issuesClosed || 0), 0)
  const clPct          = totalCL > 0 ? Math.round(closedCL / totalCL * 100) : 0

  return (
    <>
      <tr style={{ background: 'rgba(255,255,255,0.03)', borderBottom: `1px solid ${C.divider}`, cursor: 'pointer' }} onClick={onToggle}>
        <td colSpan={7} style={{ padding: 0 }}>
          <div style={{ padding: '10px 16px 0 16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
              {/* Expand toggle */}
              <div style={{ width: 18, height: 18, borderRadius: 4, background: 'rgba(255,255,255,0.07)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                {isExpanded ? <ChevronDown size={11} color="#94a3b8" /> : <ChevronRight size={11} color="#94a3b8" />}
              </div>
              {/* Blue dot */}
              <div style={{ width: 8, height: 8, borderRadius: '50%', background: '#60a5fa', flexShrink: 0 }} />
              {/* System name */}
              <span style={{ fontSize: 13, fontWeight: 700, color: C.text }}>{systemName}</span>
              {/* Units + closed count */}
              <span style={{ fontSize: 11, color: C.muted }}>{units} units Closed</span>

              {/* Badge pills — Issues | Checklists | Tests */}
              <div style={{ display: 'flex', gap: 5, marginLeft: 6, flexWrap: 'wrap' }}>
                {openIssues > 0 && (
                  <span style={badgeS('rgba(248,113,113,0.1)', 'rgba(248,113,113,0.25)', '#f87171')}>Issues {openIssues}</span>
                )}
                {totalCL > 0 && (
                  <span style={badgeS('rgba(34,197,94,0.1)', 'rgba(34,197,94,0.25)', C.approved)}>Checklists {closedCL}/{totalCL}</span>
                )}
                {totalTests > 0 && (
                  <span style={badgeS('rgba(168,85,247,0.1)', 'rgba(168,85,247,0.25)', '#c084fc')}>Tests {closedTests}/{totalTests}</span>
                )}
              </div>
            </div>

            {/* Issue Split row */}
            <div style={{ fontSize: 10, color: C.muted, marginTop: 5, marginLeft: 26, marginBottom: 6 }}>
              Issue Split&nbsp;&nbsp;
              <span style={{ color: openIssues > 0 ? '#f87171' : C.muted }}>{openIssues} open</span>
              {' / '}
              <span style={{ color: closedIssues > 0 ? C.approved : C.muted }}>{closedCL} closed</span>
            </div>
          </div>

          {/* Full-width completion bar */}
          <div style={{ height: 3, background: 'rgba(255,255,255,0.05)', overflow: 'hidden' }}>
            <div style={{ width: `${clPct}%`, height: '100%', background: clPct === 100 ? C.approved : C.inProgress, transition: 'width 0.5s' }} />
          </div>
        </td>
      </tr>

      {isExpanded && rows.map((row, i) => (
        <EquipmentRow key={row.equipmentId || row.externalId || i} row={row} idx={i} />
      ))}
    </>
  )
}

// ── Helpers ───────────────────────────────────────────────────────────────────
function tdS() { return { padding: 0, borderRight: `1px solid ${C.divider}`, verticalAlign: 'top' } }
function badgeS(bg, border, color) {
  return { fontSize: 10, padding: '2px 8px', borderRadius: 99, background: bg, border: `1px solid ${border}`, color, fontWeight: 600 }
}

const TH = {
  padding: '11px 14px', fontSize: 10, fontWeight: 700, color: C.muted,
  textTransform: 'uppercase', letterSpacing: '0.07em',
  borderRight: `1px solid ${C.divider}`, whiteSpace: 'nowrap',
  background: 'var(--bg-card)', position: 'sticky', top: 0, zIndex: 2,
}

function Pill({ label }) {
  return (
    <span style={{ fontSize: 11, fontWeight: 600, padding: '4px 11px', borderRadius: 99, background: 'rgba(255,255,255,0.06)', color: '#94a3b8', border: `1px solid ${C.border}` }}>
      {label}
    </span>
  )
}

function selS() {
  return { background: 'rgba(255,255,255,0.05)', border: `1px solid ${C.border}`, borderRadius: 7, padding: '7px 10px', fontSize: 12, color: 'var(--text-primary)', cursor: 'pointer', outline: 'none' }
}

// ── Main Component ─────────────────────────────────────────────────────────────
export default function EquipmentChecklistMatrix({ matrix, loading }) {
  const [search,       setSearch]       = useState('')
  const [typeFilter,   setTypeFilter]   = useState('All Types')
  const [statusFilter, setStatusFilter] = useState('All Statuses')
  const [expanded,     setExpanded]     = useState({})

  const typeOptions = useMemo(() => {
    if (!matrix?.rows) return ['All Types']
    return ['All Types', ...[...new Set(matrix.rows.map(r => r.equipmentType).filter(Boolean))].sort()]
  }, [matrix])

  const statusOptions = useMemo(() => {
    if (!matrix?.rows) return ['All Statuses']
    return ['All Statuses', ...[...new Set(matrix.rows.map(r => r.status).filter(Boolean))].sort()]
  }, [matrix])

  const filtered = useMemo(() => {
    if (!matrix?.rows) return []
    return matrix.rows.filter(r => {
      if (search) {
        const q = search.toLowerCase()
        if (!(r.name||'').toLowerCase().includes(q) && !(r.tag||'').toLowerCase().includes(q) && !(r.externalId||'').toLowerCase().includes(q)) return false
      }
      if (typeFilter   !== 'All Types'    && r.equipmentType !== typeFilter)   return false
      if (statusFilter !== 'All Statuses' && r.status        !== statusFilter) return false
      return true
    })
  }, [matrix, search, typeFilter, statusFilter])

  const grouped = useMemo(() => {
    const map = new Map()
    filtered.forEach(r => {
      const sys = r.systemName || 'General'
      if (!map.has(sys)) map.set(sys, [])
      map.get(sys).push(r)
    })
    return map
  }, [filtered])

  const toggle = name => setExpanded(p => ({ ...p, [name]: !(p[name] ?? false) }))

  if (loading) return (
    <div style={{ background: C.card, border: `1px solid ${C.border}`, borderRadius: 14, padding: 36, textAlign: 'center', color: C.muted }}>
      <div style={{ width: 26, height: 26, border: '3px solid #1e293b', borderTopColor: '#60a5fa', borderRadius: '50%', animation: 'spin 0.8s linear infinite', margin: '0 auto 12px' }} />
      Loading equipment matrix…
      <style>{`@keyframes spin{to{transform:rotate(360deg)}}`}</style>
    </div>
  )

  if (!matrix?.rows?.length) return (
    <div style={{ background: C.card, border: `1px solid ${C.border}`, borderRadius: 14, padding: 40, textAlign: 'center', color: C.muted }}>
      No equipment checklist data available. Sync equipment and checklists for the selected project first.
    </div>
  )

  return (
    <div style={{ background: C.card, border: `1px solid ${C.border}`, borderRadius: 14, overflow: 'hidden' }}>
      {/* Header */}
      <div style={{ padding: '18px 20px 14px', borderBottom: `1px solid ${C.divider}` }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
          <div>
            <div style={{ fontSize: 15, fontWeight: 700, color: C.text, marginBottom: 4 }}>Equipment-Checklist Matrix</div>
            <div style={{ fontSize: 12, color: C.muted }}>Cross-reference of equipment and checklist status progression</div>
          </div>
          {/* Stats pills + filters on right */}
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
            <Pill label={`${matrix.totalUnits} units`} />
            <Pill label={`${matrix.totalSystems} systems`} />
            <Pill label={`${matrix.totalTypes} types`} />
          </div>
        </div>

        {/* Filters */}
        <div style={{ display: 'flex', gap: 10, marginTop: 14, flexWrap: 'wrap', alignItems: 'center', justifyContent: 'flex-end' }}>
          <div style={{ position: 'relative', minWidth: 180 }}>
            <Search size={13} style={{ position: 'absolute', left: 9, top: '50%', transform: 'translateY(-50%)', color: C.muted }} />
            <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search equipment..."
              style={{ width: '100%', boxSizing: 'border-box', background: 'rgba(255,255,255,0.04)', border: `1px solid ${C.border}`, borderRadius: 7, padding: '7px 10px 7px 28px', fontSize: 12, color: 'var(--text-primary)', outline: 'none' }} />
          </div>
          <select value={typeFilter}   onChange={e => setTypeFilter(e.target.value)}   style={selS()}>
            {typeOptions.map(t => <option key={t}>{t}</option>)}
          </select>
          <select value={statusFilter} onChange={e => setStatusFilter(e.target.value)} style={selS()}>
            {statusOptions.map(s => <option key={s}>{s}</option>)}
          </select>
        </div>
      </div>

      {/* Table */}
      <div style={{ overflowX: 'auto', maxHeight: 680, overflowY: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'fixed', minWidth: 960 }}>
          <colgroup>
            <col style={{ width: '21%' }} />
            <col style={{ width: '13%' }} />
            <col style={{ width: '13%' }} />
            <col style={{ width: '13%' }} />
            <col style={{ width: '12%' }} />
            <col style={{ width: '12%' }} />
            <col style={{ width: '7%'  }} />
          </colgroup>
          <thead>
            <tr>
              <th style={{ ...TH, textAlign: 'left' }}>Equipment / System</th>
              <th style={{ ...TH, color: COL.red    }}>Red Tags</th>
              <th style={{ ...TH, color: COL.yellow }}>Yellow Tags</th>
              <th style={{ ...TH, color: COL.green  }}>Green Tags</th>
              <th style={{ ...TH, color: COL.l2     }}>L2 Vendor</th>
              <th style={{ ...TH, color: COL.l3     }}>L3 Vendor</th>
              <th style={{ ...TH, textAlign: 'center', borderRight: 'none' }}>Total</th>
            </tr>
          </thead>
          <tbody>
            {[...grouped.entries()].map(([sysName, sysRows]) => (
              <SystemGroupRow
                key={sysName}
                systemName={sysName}
                rows={sysRows}
                isExpanded={expanded[sysName] === true}
                onToggle={() => toggle(sysName)}
              />
            ))}
          </tbody>
        </table>
        {filtered.length === 0 && (
          <div style={{ padding: '40px', textAlign: 'center', color: C.muted, fontSize: 13 }}>No equipment matches the current filters.</div>
        )}
      </div>
    </div>
  )
}
