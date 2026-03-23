import React, { useEffect, useMemo, useState } from 'react'
import { AlertTriangle, ChevronDown, ChevronRight, Download, ExternalLink, Grid2X2, LayoutGrid, Layers3, Siren, TriangleAlert, Workflow, Wrench } from 'lucide-react'
import { useProject } from '../context/ProjectContext'
import { checklistsApi, equipmentApi, issuesApi } from '../services/api'
import EquipmentChecklistMatrix from '../components/ui/EquipmentChecklistMatrix'

const LEVELS = [
  { key: 'L1', label: 'L1', color: '#ef4444', headerBackground: 'rgba(239, 68, 68, 0.18)' },
  { key: 'L2', label: 'L2', color: '#facc15', headerBackground: 'rgba(250, 204, 21, 0.16)' },
  { key: 'L3', label: 'L3', color: '#22c55e', headerBackground: 'rgba(34, 197, 94, 0.16)' },
  { key: 'L4', label: 'L4', color: '#3b82f6', headerBackground: 'rgba(59, 130, 246, 0.16)' },
]

const SUB_TABS = [
  { key: 'tracker', label: 'Equipment Tracker', icon: LayoutGrid },
  { key: 'matrix', label: 'Equipment Matrix', icon: Grid2X2 },
]

const COMPLETE_STATUSES = new Set(['finished', 'complete', 'completed', 'done', 'closed', 'signed_off', 'approved', 'passed'])
const CLOSED_ISSUE_STATUSES = new Set(['issue_closed', 'closed', 'accepted_by_owner', 'done', 'resolved', 'completed'])

function normalizeText(value) {
  return String(value || '').trim().toLowerCase()
}

function normalizeDiscipline(value) {
  const raw = normalizeText(value)
  if (!raw) return 'Other'
  if (raw.includes('elec') || raw.includes('power') || raw.includes('control') || raw.includes('epms') || raw.includes('busway')) return 'Electrical'
  if (raw.includes('mech') || raw.includes('hvac') || raw.includes('pipe') || raw.includes('water') || raw.includes('fuel') || raw.includes('cool')) return 'Mechanical'
  return 'Other'
}

function getEquipmentLabel(equipment) {
  return equipment.equipmentType || equipment.systemName || equipment.discipline || 'Unclassified'
}

function getChecklistLevel(checklist) {
  const source = [checklist.tagLevel, checklist.checklistType, checklist.name].map(normalizeText).join(' ')
  if (source.includes('red') || source.includes('l1') || source.includes('level-1') || source.includes('level 1')) return 'L1'
  if (source.includes('yellow') || source.includes('l2') || source.includes('level-2') || source.includes('level 2')) return 'L2'
  if (source.includes('green') || source.includes('l3') || source.includes('level-3') || source.includes('level 3')) return 'L3'
  if (source.includes('blue') || source.includes('l4') || source.includes('level-4') || source.includes('level 4')) return 'L4'
  return null
}

function isChecklistClosed(checklist) {
  return COMPLETE_STATUSES.has(normalizeText(checklist.status).replace(/\s+/g, '_'))
}

function createEmptyLevelMap() {
  return LEVELS.reduce((acc, level) => {
    acc[level.key] = { total: 0, closed: 0 }
    return acc
  }, {})
}

function buildEquipmentLookup(equipment) {
  const byId = new Map()
  const byAlias = new Map()

  equipment.forEach((item) => {
    const ids = [item.externalId, item.tag, item.name]
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

function matchChecklistToEquipment(checklist, lookup) {
  const assetId = normalizeText(checklist.assetId)
  if (assetId && lookup.byId.has(assetId)) return lookup.byId.get(assetId)
  if (assetId && lookup.byAlias.has(assetId)) return lookup.byAlias.get(assetId)

  const checklistName = normalizeText(checklist.name)
  if (!checklistName) return null

  const prefix = checklistName.split(' - ')[0]
  if (prefix && lookup.byAlias.has(prefix)) return lookup.byAlias.get(prefix)
  if (lookup.byAlias.has(checklistName)) return lookup.byAlias.get(checklistName)
  return null
}

function computeTracker(equipment, checklists) {
  const lookup = buildEquipmentLookup(equipment)
  const grouped = new Map()

  equipment.forEach((item) => {
    const discipline = normalizeDiscipline(item.discipline)
    const type = getEquipmentLabel(item)
    const groupKey = `${discipline}::${type}`

    if (!grouped.has(groupKey)) {
      grouped.set(groupKey, {
        discipline,
        type,
        units: new Set(),
        levels: createEmptyLevelMap(),
      })
    }

    grouped.get(groupKey).units.add(item.externalId || item.tag || item.name || `${discipline}-${type}`)
  })

  checklists.forEach((checklist) => {
    const level = getChecklistLevel(checklist)
    if (!level) return

    const equipmentItem = matchChecklistToEquipment(checklist, lookup)
    const discipline = normalizeDiscipline(equipmentItem?.discipline)
    const type = equipmentItem ? getEquipmentLabel(equipmentItem) : 'Unmatched'
    const groupKey = `${discipline}::${type}`

    if (!grouped.has(groupKey)) {
      grouped.set(groupKey, {
        discipline,
        type,
        units: new Set(),
        levels: createEmptyLevelMap(),
      })
    }

    const target = grouped.get(groupKey)
    target.levels[level].total += 1
    if (isChecklistClosed(checklist)) target.levels[level].closed += 1
    if (equipmentItem) target.units.add(equipmentItem.externalId || equipmentItem.tag || equipmentItem.name || `${discipline}-${type}`)
  })

  const rows = Array.from(grouped.values())
    .map((item) => ({
      ...item,
      units: item.units.size,
    }))
    .sort((a, b) => {
      const disciplineOrder = ['Electrical', 'Mechanical', 'Other']
      const disciplineDiff = disciplineOrder.indexOf(a.discipline) - disciplineOrder.indexOf(b.discipline)
      if (disciplineDiff !== 0) return disciplineDiff
      return a.type.localeCompare(b.type)
    })

  const sections = ['Electrical', 'Mechanical', 'Other']
    .map((discipline) => {
      const sectionRows = rows.filter((row) => row.discipline === discipline)
      if (!sectionRows.length) return null

      const totals = createEmptyLevelMap()
      let totalUnits = 0

      sectionRows.forEach((row) => {
        totalUnits += row.units
        LEVELS.forEach((level) => {
          totals[level.key].total += row.levels[level.key].total
          totals[level.key].closed += row.levels[level.key].closed
        })
      })

      return {
        discipline,
        rows: sectionRows,
        totals,
        totalUnits,
      }
    })
    .filter(Boolean)

  const trackedTypes = rows.filter((row) => row.type !== 'Unmatched').length
  const matchedChecklistTotal = rows.reduce((sum, row) => (
    sum + LEVELS.reduce((levelSum, level) => levelSum + row.levels[level.key].total, 0)
  ), 0)
  const matchedChecklistClosed = rows.reduce((sum, row) => (
    sum + LEVELS.reduce((levelSum, level) => levelSum + row.levels[level.key].closed, 0)
  ), 0)

  const overallCompletion = matchedChecklistTotal > 0
    ? Math.round((matchedChecklistClosed / matchedChecklistTotal) * 100)
    : 0

  const highLevel = sections.reduce((acc, section) => {
    acc[section.discipline] = {
      units: section.totalUnits,
      types: section.rows.length,
      completion: getLevelPercent(
        LEVELS.reduce((sum, level) => sum + section.totals[level.key].closed, 0),
        LEVELS.reduce((sum, level) => sum + section.totals[level.key].total, 0),
      ),
    }
    return acc
  }, {})

  return {
    sections,
    trackedUnits: equipment.length,
    trackedTypes,
    matchedChecklistTotal,
    overallCompletion,
    unmatchedRows: rows.filter((row) => row.type === 'Unmatched').length,
    highLevel,
  }
}

function getLevelPercent(closed, total) {
  if (!total) return 0
  return Math.round((closed / total) * 100)
}

function getCellStyle(percent, level) {
  if (percent >= 100) {
    return {
      background: 'rgba(34, 197, 94, 0.18)',
      color: '#bbf7d0',
      border: 'rgba(34, 197, 94, 0.22)',
    }
  }

  if (percent > 0) {
    return {
      background: 'rgba(250, 204, 21, 0.16)',
      color: level.key === 'L4' ? '#dbeafe' : '#fde68a',
      border: 'rgba(250, 204, 21, 0.2)',
    }
  }

  return {
    background: 'rgba(239, 68, 68, 0.16)',
    color: '#fca5a5',
    border: 'rgba(239, 68, 68, 0.2)',
  }
}

function SummaryCard({ icon: Icon, label, value, subValue, tone = '#38bdf8' }) {
  return (
    <div
      style={{
        background: 'linear-gradient(180deg, rgba(17, 24, 39, 0.92), rgba(15, 23, 42, 0.94))',
        border: '1px solid rgba(255,255,255,0.07)',
        borderRadius: 18,
        padding: '16px 18px',
        display: 'flex',
        alignItems: 'center',
        gap: 14,
      }}
    >
      <div
        style={{
          width: 42,
          height: 42,
          borderRadius: 14,
          background: `${tone}1f`,
          color: tone,
          border: `1px solid ${tone}33`,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          flexShrink: 0,
        }}
      >
        <Icon size={18} />
      </div>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.1em', color: '#64748b', fontWeight: 700 }}>{label}</div>
        <div style={{ fontSize: 24, lineHeight: 1.05, color: '#f8fafc', fontWeight: 800, marginTop: 4 }}>{value}</div>
        <div style={{ fontSize: 11, color: '#8ea4c8', marginTop: 4 }}>{subValue}</div>
      </div>
    </div>
  )
}

function SectionTable({ section, onOpenMatrix }) {
  const hasOverflow = section.rows.length > 12

  return (
    <div
      style={{
        background: 'linear-gradient(180deg, rgba(17,24,39,0.94), rgba(15,23,42,0.94))',
        border: '1px solid rgba(255,255,255,0.08)',
        borderRadius: 18,
        overflow: 'hidden',
        minWidth: 0,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 12,
          padding: '14px 16px',
          borderBottom: '1px solid rgba(255,255,255,0.06)',
          background: 'linear-gradient(90deg, rgba(37,99,235,0.22), rgba(29,78,216,0.06))',
        }}
      >
        <div>
          <div style={{ fontSize: 14, fontWeight: 800, color: '#f8fafc' }}>{section.discipline} Equipment Tracker</div>
          <div style={{ fontSize: 12, color: '#8ea4c8', marginTop: 4 }}>
            {section.totalUnits} units • {section.rows.length} equipment types
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
          {LEVELS.map((level) => {
            const percent = getLevelPercent(section.totals[level.key].closed, section.totals[level.key].total)
            return (
              <span
                key={level.key}
                style={{
                  padding: '6px 10px',
                  borderRadius: 999,
                  background: level.headerBackground,
                  border: `1px solid ${level.color}33`,
                  color: level.color,
                  fontSize: 11,
                  fontWeight: 800,
                }}
              >
                {level.label} {percent}%
              </span>
            )
          })}
        </div>
      </div>

      <div style={{ overflowX: 'auto', maxHeight: hasOverflow ? 760 : 'none', overflowY: hasOverflow ? 'auto' : 'visible' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 620 }}>
          <thead>
            <tr style={{ background: 'rgba(255,255,255,0.02)' }}>
              <th style={tableHeadStyle('left', 220)}>Equipment Type</th>
              <th style={tableHeadStyle('left', 72)}>Units</th>
              {LEVELS.map((level) => (
                <th
                  key={level.key}
                  style={{
                    ...tableHeadStyle('left', 94),
                    background: level.headerBackground,
                    color: level.color,
                    borderLeft: '1px solid rgba(255,255,255,0.06)',
                  }}
                >
                  {level.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {section.rows.map((row, index) => (
              <tr key={`${section.discipline}-${row.type}`} style={{ background: index % 2 === 0 ? 'transparent' : 'rgba(255,255,255,0.018)' }}>
                <td style={tableCellStyle(220, true)}>
                  <button
                    onClick={() => onOpenMatrix(row.type)}
                    style={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      gap: 8,
                      padding: 0,
                      border: 'none',
                      background: 'transparent',
                      color: '#f8fafc',
                      fontSize: 14,
                      fontWeight: 800,
                      fontFamily: 'inherit',
                      cursor: 'pointer',
                    }}
                  >
                    <span style={{ textAlign: 'left' }}>{row.type}</span>
                    <ChevronRight size={15} color="#60a5fa" />
                  </button>
                </td>
                <td style={tableCellStyle(72)}>{row.units}</td>
                {LEVELS.map((level) => {
                  const percent = getLevelPercent(row.levels[level.key].closed, row.levels[level.key].total)
                  const cellStyle = getCellStyle(percent, level)
                  return (
                    <td key={level.key} style={{ ...tableCellStyle(94), padding: 0 }}>
                      <div
                        style={{
                          display: 'flex',
                          flexDirection: 'column',
                          alignItems: 'flex-start',
                          justifyContent: 'center',
                          gap: 3,
                          minHeight: 62,
                          padding: '10px 12px',
                          background: cellStyle.background,
                          borderLeft: `1px solid ${cellStyle.border}`,
                        }}
                      >
                        <div style={{ fontSize: 15, fontWeight: 800, color: cellStyle.color }}>{percent}%</div>
                        <div style={{ fontSize: 10, color: '#d7e4f7' }}>
                          {row.levels[level.key].closed}/{row.levels[level.key].total} closed
                        </div>
                      </div>
                    </td>
                  )
                })}
              </tr>
            ))}
            <tr style={{ background: 'rgba(37,99,235,0.08)' }}>
              <td style={tableCellStyle(220, true, true)}>{section.discipline.toUpperCase()} AVG</td>
              <td style={tableCellStyle(72, false, true)}>{section.totalUnits}</td>
              {LEVELS.map((level) => {
                const percent = getLevelPercent(section.totals[level.key].closed, section.totals[level.key].total)
                return (
                  <td key={level.key} style={{ ...tableCellStyle(94, false, true), color: percent >= 100 ? '#bbf7d0' : percent > 0 ? '#fde68a' : '#fca5a5' }}>
                    {percent}%
                  </td>
                )
              })}
            </tr>
          </tbody>
        </table>
      </div>
      {hasOverflow && (
        <div
          style={{
            padding: '10px 16px',
            borderTop: '1px solid rgba(255,255,255,0.06)',
            background: 'rgba(255,255,255,0.02)',
            color: '#8ea4c8',
            fontSize: 12,
            fontWeight: 700,
          }}
        >
          Showing all equipment types. Scroll inside this table after the first 12 rows.
        </div>
      )}
    </div>
  )
}

function tableHeadStyle(textAlign, minWidth) {
  return {
    padding: '12px 14px',
    textAlign,
    minWidth,
    fontSize: 11,
    fontWeight: 800,
    letterSpacing: '0.08em',
    textTransform: 'uppercase',
    color: '#dbe7fb',
    borderBottom: '1px solid rgba(255,255,255,0.06)',
  }
}

function tableCellStyle(minWidth, strong = false, footer = false) {
  return {
    minWidth,
    padding: '12px 14px',
    borderBottom: footer ? 'none' : '1px solid rgba(255,255,255,0.05)',
    color: strong ? '#f8fafc' : '#dbe7fb',
    fontSize: strong ? 13 : 12,
    fontWeight: strong ? 800 : footer ? 800 : 600,
    verticalAlign: 'middle',
  }
}

function isChecklistInactive(checklist) {
  const normalized = normalizeText(checklist.status).replace(/\s+/g, '_').replace(/-/g, '_')
  if (COMPLETE_STATUSES.has(normalized) || normalized === 'cancelled' || normalized === 'canceled') return false
  const updated = new Date(checklist.updatedAt || checklist.createdAt || 0)
  if (isNaN(updated)) return false
  return ((Date.now() - updated.getTime()) / 86400000) >= 14
}

function formatDateLabel(value) {
  if (!value) return '—'
  const parsed = new Date(value)
  if (isNaN(parsed)) return '—'
  return parsed.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' })
}

function getChecklistAgeDays(checklist) {
  const start = new Date(checklist.updatedAt || checklist.createdAt || 0)
  if (isNaN(start)) return 0
  return Math.max(0, Math.round((Date.now() - start.getTime()) / 86400000))
}

function getChecklistDurationDays(checklist) {
  const start = new Date(checklist.createdAt || 0)
  const end = new Date(checklist.actualFinishDate || checklist.updatedAt || 0)
  if (isNaN(start) || isNaN(end)) return 0
  return Math.max(0, Math.round((end.getTime() - start.getTime()) / 86400000))
}

function getIssueAssetKey(issue) {
  return normalizeText(issue.assetId || issue.assetExternalId || issue.equipmentId || issue.linkedAssetId || issue.assetName)
}

function getIssueAssetLabel(issue) {
  return issue.assetName || issue.assetTag || issue.assetId || issue.assetExternalId || issue.equipmentName || 'Unassigned Asset'
}

function buildChecklistUrl(projectId, checklistExternalId) {
  if (!projectId || !checklistExternalId) return null
  return `https://tq.cxalloy.com/project/${projectId}/checklists/${checklistExternalId}`
}

function buildEquipmentUrl(projectId, equipmentExternalId) {
  if (!projectId || !equipmentExternalId) return null
  return `https://tq.cxalloy.com/project/${projectId}/equipment/${equipmentExternalId}`
}

function buildIssueLookup(issues) {
  const byAsset = new Map()

  issues.forEach((issue) => {
    const key = getIssueAssetKey(issue)
    if (!key) return

    const current = byAsset.get(key) || {
      label: getIssueAssetLabel(issue),
      total: 0,
      open: 0,
      closed: 0,
    }

    current.total += 1
    if (CLOSED_ISSUE_STATUSES.has(normalizeText(issue.status).replace(/\s+/g, '_').replace(/-/g, '_'))) {
      current.closed += 1
    } else {
      current.open += 1
    }

    if (!current.label || current.label === 'Unassigned Asset') {
      current.label = getIssueAssetLabel(issue)
    }

    byAsset.set(key, current)
  })

  return byAsset
}

function InsightShell({ icon: Icon, title, subtitle, accent, children }) {
  return (
    <section
      style={{
        background: 'linear-gradient(180deg, rgba(17,24,39,0.94), rgba(15,23,42,0.96))',
        border: '1px solid rgba(255,255,255,0.08)',
        borderRadius: 18,
        overflow: 'hidden',
        minWidth: 0,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          padding: '16px 18px 12px',
          borderBottom: '1px solid rgba(255,255,255,0.06)',
        }}
      >
        <div
          style={{
            width: 38,
            height: 38,
            borderRadius: 14,
            background: `${accent}18`,
            border: `1px solid ${accent}33`,
            color: accent,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            flexShrink: 0,
          }}
        >
          <Icon size={17} />
        </div>
        <div style={{ minWidth: 0 }}>
          <div style={{ color: '#f8fafc', fontSize: 16, fontWeight: 800 }}>{title}</div>
          <div style={{ color: '#8ea4c8', fontSize: 12, marginTop: 3 }}>{subtitle}</div>
        </div>
      </div>
      <div style={{ padding: 14 }}>{children}</div>
    </section>
  )
}

function EmptyInsight({ text }) {
  return (
    <div
      style={{
        padding: '18px 14px',
        borderRadius: 14,
        border: '1px dashed rgba(255,255,255,0.1)',
        color: '#8ea4c8',
        fontSize: 12,
      }}
    >
      {text}
    </div>
  )
}

function MetricPill({ label, value, tone = '#60a5fa' }) {
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 6,
        padding: '5px 9px',
        borderRadius: 999,
        background: `${tone}12`,
        border: `1px solid ${tone}2b`,
        color: '#dbe7fb',
        fontSize: 11,
        fontWeight: 700,
      }}
    >
      <span style={{ color: '#8ea4c8' }}>{label}</span>
      <span style={{ color: tone }}>{value}</span>
    </span>
  )
}

function downloadCsv(filename, headers, rows) {
  const escape = (value) => `"${String(value ?? '').replace(/"/g, '""')}"`
  const csv = [
    headers.map(escape).join(','),
    ...rows.map((row) => row.map(escape).join(',')),
  ].join('\n')

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

function StaleChecklistPanel({ items, expandedId, onToggle, projectId }) {
  return (
    <InsightShell
      icon={AlertTriangle}
      title="Stale Checklists"
      subtitle="Open checklists untouched for 14+ days. Click a row to expand."
      accent="#f59e0b"
    >
      {!items.length ? (
        <EmptyInsight text="No stale checklist items in the current selection." />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {items.map((item) => {
            const expanded = expandedId === item.externalId
            const link = buildChecklistUrl(projectId, item.externalId)
            return (
              <div
                key={item.externalId || item.name}
                style={{
                  borderRadius: 14,
                  border: '1px solid rgba(245,158,11,0.18)',
                  background: expanded ? 'rgba(245,158,11,0.08)' : 'rgba(255,255,255,0.03)',
                  overflow: 'hidden',
                }}
              >
                <button
                  onClick={() => onToggle(expanded ? null : item.externalId)}
                  style={{
                    width: '100%',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 10,
                    padding: '12px 14px',
                    background: 'transparent',
                    border: 'none',
                    cursor: 'pointer',
                    color: 'inherit',
                    fontFamily: 'inherit',
                  }}
                >
                  <div style={{ minWidth: 0, textAlign: 'left' }}>
                    <div style={{ fontSize: 13, fontWeight: 800, color: '#f8fafc', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {item.name || item.externalId || 'Unnamed checklist'}
                    </div>
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 8 }}>
                      <MetricPill label="Age" value={`${item.ageDays}d`} tone="#f59e0b" />
                      <MetricPill label="Issues" value={item.issueCount} tone="#f87171" />
                      <MetricPill label="Status" value={item.status || 'Unknown'} tone="#60a5fa" />
                    </div>
                  </div>
                  <ChevronDown
                    size={16}
                    color="#fbbf24"
                    style={{ transform: expanded ? 'rotate(180deg)' : 'rotate(0deg)', transition: 'transform 0.16s ease' }}
                  />
                </button>

                {expanded && (
                  <div style={{ padding: '0 14px 14px', display: 'flex', flexDirection: 'column', gap: 10 }}>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 10 }}>
                      <div style={detailTileStyle}>
                        <div style={detailLabelStyle}>Last Updated</div>
                        <div style={detailValueStyle}>{formatDateLabel(item.updatedAt || item.createdAt)}</div>
                      </div>
                      <div style={detailTileStyle}>
                        <div style={detailLabelStyle}>Equipment</div>
                        <div style={detailValueStyle}>{item.assetLabel || 'Unlinked'}</div>
                      </div>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10, flexWrap: 'wrap' }}>
                      <div style={{ fontSize: 12, color: '#8ea4c8' }}>
                        Type: <span style={{ color: '#dbe7fb', fontWeight: 700 }}>{item.checklistType || item.tagLevel || 'Unspecified'}</span>
                      </div>
                      {link && (
                        <a
                          href={link}
                          target="_blank"
                          rel="noopener noreferrer"
                          style={{
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 8,
                            padding: '8px 12px',
                            borderRadius: 12,
                            textDecoration: 'none',
                            background: 'rgba(59,130,246,0.14)',
                            border: '1px solid rgba(59,130,246,0.28)',
                            color: '#93c5fd',
                            fontSize: 12,
                            fontWeight: 800,
                          }}
                        >
                          <ExternalLink size={14} />
                          Open in CxAlloy
                        </a>
                      )}
                    </div>
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </InsightShell>
  )
}

function ExpandableInsightList({ icon, title, subtitle, accent, items, emptyText, expandedKey, onToggle, getKey, renderCollapsedPills, renderExpandedMeta, getLink }) {
  return (
    <InsightShell icon={icon} title={title} subtitle={subtitle} accent={accent}>
      {!items.length ? (
        <EmptyInsight text={emptyText} />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {items.map((item, index) => {
            const key = getKey(item, index)
            const expanded = expandedKey === key
            const link = getLink ? getLink(item) : null

            return (
              <div
                key={`${title}-${key}`}
                style={{
                  borderRadius: 14,
                  border: `1px solid ${accent}20`,
                  background: expanded ? `${accent}10` : 'rgba(255,255,255,0.03)',
                  overflow: 'hidden',
                }}
              >
                <button
                  onClick={() => onToggle(expanded ? null : key)}
                  style={{
                    width: '100%',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 10,
                    padding: '12px 14px',
                    background: 'transparent',
                    border: 'none',
                    cursor: 'pointer',
                    color: 'inherit',
                    fontFamily: 'inherit',
                  }}
                >
                  <div style={{ minWidth: 0, textAlign: 'left' }}>
                    <div style={{ color: '#f8fafc', fontSize: 13, fontWeight: 800, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {item.label}
                    </div>
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 8 }}>
                      {renderCollapsedPills(item)}
                    </div>
                  </div>
                  <ChevronDown
                    size={16}
                    color={accent}
                    style={{ transform: expanded ? 'rotate(180deg)' : 'rotate(0deg)', transition: 'transform 0.16s ease' }}
                  />
                </button>

                {expanded && (
                  <div style={{ padding: '0 14px 14px', display: 'flex', flexDirection: 'column', gap: 10 }}>
                    {renderExpandedMeta(item)}
                    {link && (
                      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                        <a
                          href={link}
                          target="_blank"
                          rel="noopener noreferrer"
                          style={{
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 8,
                            padding: '8px 12px',
                            borderRadius: 12,
                            textDecoration: 'none',
                            background: 'rgba(59,130,246,0.14)',
                            border: '1px solid rgba(59,130,246,0.28)',
                            color: '#93c5fd',
                            fontSize: 12,
                            fontWeight: 800,
                          }}
                        >
                          <ExternalLink size={14} />
                          Open in CxAlloy
                        </a>
                      </div>
                    )}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      )}
    </InsightShell>
  )
}

const detailTileStyle = {
  padding: '10px 12px',
  borderRadius: 12,
  background: 'rgba(255,255,255,0.03)',
  border: '1px solid rgba(255,255,255,0.06)',
}

const detailLabelStyle = {
  fontSize: 10,
  color: '#64748b',
  textTransform: 'uppercase',
  letterSpacing: '0.08em',
  fontWeight: 800,
}

const detailValueStyle = {
  fontSize: 12,
  color: '#f8fafc',
  fontWeight: 700,
  marginTop: 5,
}

export default function AssetReadinessPage() {
  const { selectedProjects, activeProject } = useProject()
  const targets = selectedProjects.length > 0 ? selectedProjects : (activeProject ? [activeProject] : [])
  const primaryProjectId = targets[0]?.externalId
  const targetKey = targets.map((project) => project.externalId || project.id).join(',')
  const [equipment, setEquipment] = useState([])
  const [checklists, setChecklists] = useState([])
  const [issues, setIssues] = useState([])
  const [matrix, setMatrix] = useState(null)
  const [loading, setLoading] = useState(false)
  const [matrixLoading, setMatrixLoading] = useState(false)
  const [error, setError] = useState('')
  const [activeTab, setActiveTab] = useState('tracker')
  const [matrixSearch, setMatrixSearch] = useState('')
  const [expandedChecklistId, setExpandedChecklistId] = useState(null)
  const [expandedOutlierId, setExpandedOutlierId] = useState(null)
  const [expandedIssueEquipmentId, setExpandedIssueEquipmentId] = useState(null)

  useEffect(() => {
    let cancelled = false

    async function load() {
      if (!targets.length) {
        setEquipment([])
        setChecklists([])
        setIssues([])
        return
      }

      setLoading(true)
      setError('')

      try {
        const [equipmentResults, checklistResults, issueResults] = await Promise.all([
          Promise.all(targets.map((project) => equipmentApi.getAll(project.externalId).then((response) => response.data?.data || []))),
          Promise.all(targets.map((project) => checklistsApi.getAll(project.externalId).then((response) => response.data?.data || []))),
          Promise.all(targets.map((project) => issuesApi.getAll(project.externalId).then((response) => response.data?.data || []))),
        ])

        if (cancelled) return

        setEquipment(equipmentResults.flat())
        setChecklists(checklistResults.flat())
        setIssues(issueResults.flat())
      } catch (loadError) {
        if (cancelled) return
        setError(loadError.response?.data?.message || loadError.message || 'Failed to load checklist flow tracker.')
      } finally {
        if (!cancelled) setLoading(false)
      }
    }

    load()
    return () => {
      cancelled = true
    }
  }, [targetKey])

  useEffect(() => {
    let cancelled = false

    async function loadMatrix() {
      if (!targets.length) {
        setMatrix(null)
        return
      }

      setMatrixLoading(true)
      try {
        const response = await equipmentApi.getMatrix(targets[0].externalId)
        if (!cancelled) setMatrix(response.data?.data || null)
      } catch {
        if (!cancelled) setMatrix(null)
      } finally {
        if (!cancelled) setMatrixLoading(false)
      }
    }

    loadMatrix()
    return () => {
      cancelled = true
    }
  }, [targetKey])

  const tracker = useMemo(() => computeTracker(equipment, checklists), [equipment, checklists])
  const issueLookup = useMemo(() => buildIssueLookup(issues), [issues])
  const highLevel = tracker?.highLevel || {}
  const electrical = highLevel.Electrical || { units: 0, types: 0, completion: 0 }
  const mechanical = highLevel.Mechanical || { units: 0, types: 0, completion: 0 }
  const flowInsights = useMemo(() => {
    const equipmentLookup = buildEquipmentLookup(equipment)
    const checklistCountsByAsset = new Map()

    checklists.forEach((checklist) => {
      const matchedEquipment = matchChecklistToEquipment(checklist, equipmentLookup)
      const key = normalizeText(
        checklist.assetId
          || matchedEquipment?.externalId
          || matchedEquipment?.tag
          || matchedEquipment?.name
      )
      if (!key) return
      checklistCountsByAsset.set(key, (checklistCountsByAsset.get(key) || 0) + 1)
    })

    const staleChecklists = checklists
      .filter(isChecklistInactive)
      .map((checklist) => {
        const matchedEquipment = matchChecklistToEquipment(checklist, equipmentLookup)
        const assetKey = normalizeText(
          checklist.assetId
            || matchedEquipment?.externalId
            || matchedEquipment?.tag
            || matchedEquipment?.name
        )
        const issueStats = assetKey ? issueLookup.get(assetKey) : null
        return {
          ...checklist,
          ageDays: getChecklistAgeDays(checklist),
          issueCount: issueStats?.total || 0,
          assetLabel: matchedEquipment?.name || matchedEquipment?.tag || getEquipmentLabel(matchedEquipment || {}) || checklist.assetId || null,
        }
      })
      .sort((a, b) => b.ageDays - a.ageDays)
      .slice(0, 6)

    const checklistOutliers = checklists
      .map((checklist) => {
        const matchedEquipment = matchChecklistToEquipment(checklist, equipmentLookup)
        const durationDays = isChecklistClosed(checklist)
          ? getChecklistDurationDays(checklist)
          : getChecklistAgeDays(checklist)
        return {
          externalId: checklist.externalId,
          label: checklist.name || checklist.externalId || 'Unnamed checklist',
          value: `${durationDays}d`,
          durationDays,
          meta: isChecklistClosed(checklist)
            ? `Closed ${formatDateLabel(checklist.actualFinishDate || checklist.updatedAt)}`
            : `Open for ${durationDays} days`,
          assetLabel: matchedEquipment?.name || matchedEquipment?.tag || checklist.assetId || 'Unlinked',
        }
      })
      .filter((item) => item.durationDays > 0)
      .sort((a, b) => parseInt(b.value, 10) - parseInt(a.value, 10))
      .slice(0, 5)

    const topIssueEquipment = Array.from(issueLookup.entries())
      .map(([assetKey, stats]) => {
        const equipmentItem = equipmentLookup.byId.get(assetKey) || equipmentLookup.byAlias.get(assetKey)
        return {
          externalId: equipmentItem?.externalId || equipmentItem?.tag || null,
          label: equipmentItem?.name || equipmentItem?.tag || stats.label,
          value: stats.total,
          open: stats.open,
          closed: stats.closed,
          checklistCount: checklistCountsByAsset.get(assetKey) || 0,
          type: equipmentItem?.equipmentType || equipmentItem?.discipline || 'Unclassified',
        }
      })
      .sort((a, b) => b.value - a.value)
      .slice(0, 5)

    return { staleChecklists, checklistOutliers, topIssueEquipment }
  }, [checklists, equipment, issueLookup])

  const openMatrixForType = (typeName) => {
    setMatrixSearch(typeName)
    setActiveTab('matrix')
  }

  const exportTracker = () => {
    const rows = (tracker?.sections || []).flatMap((section) =>
      section.rows.map((row) => [
        section.discipline,
        row.type,
        row.units,
        `${getLevelPercent(row.levels.L1.closed, row.levels.L1.total)}%`,
        `${row.levels.L1.closed}/${row.levels.L1.total}`,
        `${getLevelPercent(row.levels.L2.closed, row.levels.L2.total)}%`,
        `${row.levels.L2.closed}/${row.levels.L2.total}`,
        `${getLevelPercent(row.levels.L3.closed, row.levels.L3.total)}%`,
        `${row.levels.L3.closed}/${row.levels.L3.total}`,
        `${getLevelPercent(row.levels.L4.closed, row.levels.L4.total)}%`,
        `${row.levels.L4.closed}/${row.levels.L4.total}`,
      ])
    )

    downloadCsv(
      `checklists-flow-${primaryProjectId || 'selection'}.csv`,
      ['Discipline', 'Equipment Type', 'Units', 'L1 %', 'L1 Closed', 'L2 %', 'L2 Closed', 'L3 %', 'L3 Closed', 'L4 %', 'L4 Closed'],
      rows
    )
  }

  if (!targets.length) {
    return (
      <div
        style={{
          background: 'linear-gradient(180deg, rgba(17,24,39,0.92), rgba(15,23,42,0.94))',
          border: '1px solid rgba(255,255,255,0.08)',
          borderRadius: 20,
          padding: 42,
          textAlign: 'center',
          color: '#8ea4c8',
        }}
      >
        Select a project to open the checklist flow equipment tracker.
      </div>
    )
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <div>
        <h2 style={{ fontSize: 24, fontWeight: 800, color: 'var(--text-primary)', margin: 0 }}>Checklists Flow</h2>
        <p style={{ fontSize: 13, color: '#64748b', marginTop: 6 }}>
          Simple equipment tracker with Cx-level completion by type, grouped into high-level disciplines.
        </p>
      </div>

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {SUB_TABS.map((tab) => {
          const Icon = tab.icon
          const active = activeTab === tab.key
          return (
            <button
              key={tab.key}
              onClick={() => setActiveTab(tab.key)}
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 8,
                padding: '10px 16px',
                borderRadius: 12,
                border: active ? '1px solid rgba(59,130,246,0.45)' : '1px solid rgba(255,255,255,0.08)',
                background: active ? 'linear-gradient(180deg, rgba(59,130,246,0.28), rgba(59,130,246,0.14))' : 'rgba(255,255,255,0.03)',
                color: active ? '#f8fafc' : '#9fb1cd',
                fontSize: 13,
                fontWeight: 800,
                cursor: 'pointer',
              }}
            >
              <Icon size={15} />
              <span>{tab.label}</span>
            </button>
          )
        })}
      </div>

      {error && (
        <div
          style={{
            background: 'rgba(239,68,68,0.08)',
            border: '1px solid rgba(239,68,68,0.18)',
            borderRadius: 14,
            padding: '14px 16px',
            fontSize: 13,
            color: '#fca5a5',
          }}
        >
          {error}
        </div>
      )}

      {loading ? (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 16 }}>
          {Array.from({ length: 3 }).map((_, index) => (
            <div
              key={index}
              style={{
                height: 220,
                borderRadius: 18,
                background: 'linear-gradient(180deg, rgba(17,24,39,0.92), rgba(15,23,42,0.94))',
                border: '1px solid rgba(255,255,255,0.07)',
              }}
            />
          ))}
        </div>
      ) : activeTab === 'tracker' ? (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', gap: 14 }}>
            <SummaryCard icon={Wrench} label="Tracked Units" value={tracker?.trackedUnits || 0} subValue={`${tracker?.trackedTypes || 0} equipment types`} tone="#38bdf8" />
            <SummaryCard icon={Layers3} label="Electrical" value={`${electrical.completion}%`} subValue={`${electrical.units} units • ${electrical.types} types`} tone="#22c55e" />
            <SummaryCard icon={Workflow} label="Mechanical" value={`${mechanical.completion}%`} subValue={`${mechanical.units} units • ${mechanical.types} types`} tone="#f59e0b" />
            <SummaryCard icon={AlertTriangle} label="Overall CX" value={`${tracker?.overallCompletion || 0}%`} subValue={`${tracker?.matchedChecklistTotal || 0} mapped checklists • ${tracker?.unmatchedRows || 0} unmatched groups`} tone="#a78bfa" />
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1.25fr 1fr 1fr', gap: 16, alignItems: 'start' }}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 12,
                flexWrap: 'wrap',
                padding: '12px 14px',
                borderRadius: 16,
                border: '1px solid rgba(255,255,255,0.08)',
                background: 'rgba(255,255,255,0.03)',
                gridColumn: '1 / -1',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                <MetricPill label="Mapped Checklists" value={tracker?.matchedChecklistTotal || 0} tone="#22c55e" />
                <MetricPill label="Unmatched Groups" value={tracker?.unmatchedRows || 0} tone="#f59e0b" />
                <MetricPill label="Stale Items" value={flowInsights.staleChecklists.length} tone="#f59e0b" />
                <MetricPill label="Heavy-Issue Equipment" value={flowInsights.topIssueEquipment.length} tone="#60a5fa" />
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
                <div style={{ fontSize: 12, color: '#8ea4c8', fontWeight: 700 }}>
                  L1 / L2 / L3 / L4 values are based on closed vs total mapped checklists by equipment type.
                </div>
                <button
                  onClick={exportTracker}
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 8,
                    padding: '9px 12px',
                    borderRadius: 12,
                    background: 'rgba(59,130,246,0.14)',
                    border: '1px solid rgba(59,130,246,0.3)',
                    color: '#dbeafe',
                    fontSize: 12,
                    fontWeight: 800,
                    cursor: 'pointer',
                  }}
                >
                  <Download size={14} />
                  Export CSV
                </button>
              </div>
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr)', gap: 16, alignItems: 'start' }}>
            {(tracker?.sections || [])
              .filter((section) => section.discipline === 'Electrical' || section.discipline === 'Mechanical')
              .map((section) => (
                <SectionTable key={section.discipline} section={section} onOpenMatrix={openMatrixForType} />
              ))}
          </div>

          {(tracker?.sections || []).some((section) => section.discipline === 'Other') && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
              {(tracker?.sections || [])
                .filter((section) => section.discipline === 'Other')
                .map((section) => (
                  <SectionTable key={section.discipline} section={section} onOpenMatrix={openMatrixForType} />
                ))}
            </div>
          )}

          {(tracker?.sections || []).length === 0 && (
            <div
              style={{
                background: 'linear-gradient(180deg, rgba(17,24,39,0.94), rgba(15,23,42,0.94))',
                border: '1px solid rgba(255,255,255,0.08)',
                borderRadius: 18,
                padding: 36,
                textAlign: 'center',
                color: '#8ea4c8',
              }}
            >
              No grouped equipment tracker data is available for this selection.
            </div>
          )}

          <div style={{ display: 'grid', gridTemplateColumns: '1.25fr 1fr 1fr', gap: 16, alignItems: 'start' }}>
            <StaleChecklistPanel
              items={flowInsights.staleChecklists}
              expandedId={expandedChecklistId}
              onToggle={setExpandedChecklistId}
              projectId={primaryProjectId}
            />
            <ExpandableInsightList
              icon={TriangleAlert}
              title="Checklist Outliers"
              subtitle="Longest-running or longest-cycle checklist items in the current selection."
              accent="#fb7185"
              items={flowInsights.checklistOutliers}
              emptyText="No checklist outliers detected from the current records."
              expandedKey={expandedOutlierId}
              onToggle={setExpandedOutlierId}
              getKey={(item, index) => item.externalId || `${item.label}-${index}`}
              renderCollapsedPills={(item) => (
                <>
                  <MetricPill label="Total" value={item.value} tone="#fb7185" />
                </>
              )}
              renderExpandedMeta={(item) => (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 10 }}>
                  <div style={detailTileStyle}>
                    <div style={detailLabelStyle}>Duration</div>
                    <div style={detailValueStyle}>{item.value}</div>
                  </div>
                  <div style={detailTileStyle}>
                    <div style={detailLabelStyle}>Equipment</div>
                    <div style={detailValueStyle}>{item.assetLabel}</div>
                  </div>
                </div>
              )}
              getLink={(item) => buildChecklistUrl(primaryProjectId, item.externalId)}
            />
            <ExpandableInsightList
              icon={Siren}
              title="Top 5 Equipment With Max Issues"
              subtitle="Equipment/assets carrying the heaviest linked issue load right now."
              accent="#60a5fa"
              items={flowInsights.topIssueEquipment}
              emptyText="No issue-linked equipment found for the selected project."
              expandedKey={expandedIssueEquipmentId}
              onToggle={setExpandedIssueEquipmentId}
              getKey={(item, index) => item.externalId || `${item.label}-${index}`}
              renderCollapsedPills={(item) => (
                <>
                  <MetricPill label="Total" value={item.value} tone="#60a5fa" />
                  <MetricPill label="Open" value={item.open} tone="#f87171" />
                  <MetricPill label="Closed" value={item.closed} tone="#22c55e" />
                </>
              )}
              renderExpandedMeta={(item) => (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 10 }}>
                  <div style={detailTileStyle}>
                    <div style={detailLabelStyle}>Mapped Checklists</div>
                    <div style={detailValueStyle}>{item.checklistCount}</div>
                  </div>
                  <div style={detailTileStyle}>
                    <div style={detailLabelStyle}>Type</div>
                    <div style={detailValueStyle}>{item.type}</div>
                  </div>
                </div>
              )}
              getLink={(item) => buildEquipmentUrl(primaryProjectId, item.externalId)}
            />
          </div>
        </>
      ) : (
        <EquipmentChecklistMatrix
          matrix={matrix}
          loading={matrixLoading}
          searchValue={matrixSearch}
          onSearchChange={setMatrixSearch}
          focusLabel={matrixSearch}
        />
      )}
    </div>
  )
}
