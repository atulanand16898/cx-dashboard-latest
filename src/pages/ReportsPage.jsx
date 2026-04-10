import React, { useEffect, useMemo, useState } from 'react'
import {
  AlertTriangle,
  ArrowLeft,
  ArrowRight,
  BarChart3,
  Briefcase,
  CalendarDays,
  Camera,
  CheckSquare,
  ChevronDown,
  ChevronUp,
  ClipboardList,
  Copy,
  Cpu,
  Download,
  Eye,
  FileText,
  FolderOpen,
  GripVertical,
  LayoutTemplate,
  Pencil,
  Plus,
  RefreshCw,
  Save,
  Shield,
  Sparkles,
  Trash2,
  Users,
} from 'lucide-react'
import toast from 'react-hot-toast'
import { useProject } from '../context/ProjectContext'
import { reportsApi } from '../services/api'
import { Modal } from '../components/ui'

const TEMPLATE_STORAGE_KEY = 'modum_iq_report_templates_v1'

const REPORT_TYPES = [
  { id: 'daily', label: 'Daily' },
  { id: 'weekly', label: 'Weekly' },
  { id: 'custom', label: 'Custom' },
]

const SECTION_LIBRARY = [
  { id: 'summary', label: 'Summary', description: 'Executive overview for the selected date range.', icon: Sparkles, accent: '#60a5fa', tone: 'rgba(96,165,250,0.12)' },
  { id: 'custom', label: 'Custom', description: 'Free-form narrative or a tailored report block.', icon: FileText, accent: '#a78bfa', tone: 'rgba(167,139,250,0.12)' },
  { id: 'checklists', label: 'Checklists', description: 'Insight text, graph, and checklist table.', icon: ClipboardList, accent: '#34d399', tone: 'rgba(52,211,153,0.12)' },
  { id: 'issues', label: 'Issues', description: 'Priority trend, status summary, and issue table.', icon: AlertTriangle, accent: '#f97316', tone: 'rgba(249,115,22,0.12)' },
  { id: 'equipment', label: 'Equipment', description: 'Equipment mix, status split, and detailed rows.', icon: Cpu, accent: '#22c55e', tone: 'rgba(34,197,94,0.12)' },
  { id: 'tests', label: 'Tests', description: 'Testing volume, coverage split, and table preview.', icon: CheckSquare, accent: '#38bdf8', tone: 'rgba(56,189,248,0.12)' },
  { id: 'progressphotos', label: 'Progress Photos', description: 'Progress-photo narrative and photo placeholders.', icon: Camera, accent: '#f59e0b', tone: 'rgba(245,158,11,0.12)' },
  { id: 'personnel', label: 'Personnel', description: 'Team mix and company participation summary.', icon: Users, accent: '#818cf8', tone: 'rgba(129,140,248,0.12)' },
  { id: 'activities', label: 'Activities', description: 'Completed and active delivery actions.', icon: BarChart3, accent: '#2dd4bf', tone: 'rgba(45,212,191,0.12)' },
  { id: 'upcoming', label: 'Upcoming', description: 'Tasks due in the near-term window.', icon: CalendarDays, accent: '#c084fc', tone: 'rgba(192,132,252,0.12)' },
  { id: 'safety', label: 'Safety', description: 'Safety notes and narrative callouts.', icon: Shield, accent: '#fb7185', tone: 'rgba(251,113,133,0.12)' },
  { id: 'commercials', label: 'Commercials', description: 'Commercial risk, cost, and contractual notes.', icon: Briefcase, accent: '#facc15', tone: 'rgba(250,204,21,0.12)' },
]

const STARTER_TEMPLATES = [
  { id: 'starter-executive-weekly', name: 'Executive Weekly Update', description: 'A clean weekly leadership pack with core delivery health.', reportType: 'weekly', sections: ['summary', 'checklists', 'issues', 'equipment', 'tests'] },
  { id: 'starter-closeout', name: 'Closeout Readiness', description: 'Focused on punch items, tests, and final narrative.', reportType: 'custom', sections: ['summary', 'checklists', 'issues', 'tests', 'custom'] },
  { id: 'starter-photo-progress', name: 'Progress Photo Pack', description: 'A site progress report with narrative and photo placeholders.', reportType: 'weekly', sections: ['summary', 'progressphotos', 'issues', 'equipment'] },
]

function isDataSection(sectionId) {
  return ['checklists', 'issues', 'equipment', 'tests', 'personnel', 'activities', 'upcoming'].includes(sectionId)
}

function defaultSectionSettings(sectionId, existing = {}) {
  const meta = sectionById(sectionId)
  return {
    title: existing.title || meta.label,
    narrative: existing.narrative || '',
    includeInsights: existing.includeInsights ?? true,
    includeChart: existing.includeChart ?? isDataSection(sectionId),
    includeTable: existing.includeTable ?? isDataSection(sectionId),
    chartType: existing.chartType || (sectionId === 'progressphotos' ? 'gallery' : sectionId === 'summary' ? 'headline' : 'bar'),
    issueStatuses: Array.isArray(existing.issueStatuses) ? existing.issueStatuses : [],
    checklistStatuses: Array.isArray(existing.checklistStatuses) ? existing.checklistStatuses : [],
    equipmentTypes: Array.isArray(existing.equipmentTypes) ? existing.equipmentTypes : [],
  }
}

function buildSectionSettings(sections, storedSettings = []) {
  return sections.map((sectionId, index) => defaultSectionSettings(sectionId, storedSettings[index] || {}))
}

function uniqueValues(values) {
  return [...new Set((values || []).filter(Boolean))]
}

function collectSectionSelections(draft, sectionId, key) {
  const selections = draft.sections.reduce((all, currentSectionId, index) => {
    if (currentSectionId !== sectionId) return all
    const settings = defaultSectionSettings(currentSectionId, draft.sectionSettings?.[index])
    return [...all, ...(settings[key] || [])]
  }, [])
  return uniqueValues(selections)
}

function findSectionNarrative(draft, sectionId, fallback = '') {
  const index = draft.sections.findIndex(item => item === sectionId)
  if (index === -1) return fallback
  const settings = defaultSectionSettings(sectionId, draft.sectionSettings?.[index])
  return settings.narrative || fallback
}

function todayIso() {
  return new Date().toISOString().slice(0, 10)
}

function weeklyRange() {
  const now = new Date()
  const day = now.getDay() || 7
  const monday = new Date(now)
  monday.setDate(now.getDate() - day + 1)
  const sunday = new Date(monday)
  sunday.setDate(monday.getDate() + 6)
  return { from: monday.toISOString().slice(0, 10), to: sunday.toISOString().slice(0, 10) }
}

function createDraft(template = {}) {
  const weekly = weeklyRange()
  const sections = Array.isArray(template.sections) && template.sections.length ? [...template.sections] : ['summary', 'checklists', 'issues']
  return {
    id: template.id || `draft-${Date.now()}`,
    isStarter: Boolean(template.isStarter),
    name: template.name || 'New Report Template',
    description: template.description || '',
    reportType: template.reportType || 'weekly',
    dateFrom: template.dateFrom || weekly.from,
    dateTo: template.dateTo || weekly.to,
    sections,
    issueStatuses: template.issueStatuses || [],
    checklistStatuses: template.checklistStatuses || [],
    equipmentTypes: template.equipmentTypes || [],
    summaryText: template.summaryText || '',
    safetyNotes: template.safetyNotes || '',
    commercialNotes: template.commercialNotes || '',
    customSectionText: template.customSectionText || '',
    progressPhotosText: template.progressPhotosText || '',
    projectDescription: template.projectDescription || '',
    clientName: template.clientName || '',
    projectCode: template.projectCode || '',
    shiftWindow: template.shiftWindow || '',
    reportAuthor: template.reportAuthor || '',
    peopleOnSite: template.peopleOnSite || '',
    sectionSettings: buildSectionSettings(sections, template.sectionSettings),
  }
}

function readStoredTemplates() {
  if (typeof window === 'undefined') return []
  try {
    const raw = window.localStorage.getItem(TEMPLATE_STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function writeStoredTemplates(templates) {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(TEMPLATE_STORAGE_KEY, JSON.stringify(templates))
}

function formatDate(value) {
  if (!value) return ''
  try {
    return new Date(value).toLocaleString('en-US', {
      month: 'short',
      day: 'numeric',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return value
  }
}

function formatRangeLabel(dateFrom, dateTo) {
  if (!dateFrom || !dateTo) return 'Selected reporting window'
  if (dateFrom === dateTo) return dateFrom
  return `${dateFrom} to ${dateTo}`
}

function downloadBlob(blob, fileName) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.click()
  URL.revokeObjectURL(url)
}

function sumCounts(values) {
  return Object.values(values || {}).reduce((total, value) => total + (Number(value) || 0), 0)
}

function normalizeLabel(value) {
  return String(value || '').trim().toLowerCase()
}

function matchCount(values, needles) {
  return Object.entries(values || {}).reduce((total, [label, count]) => {
    const normalized = normalizeLabel(label)
    const matches = needles.some(needle => normalized.includes(needle))
    return matches ? total + (Number(count) || 0) : total
  }, 0)
}

function topEntry(values) {
  const entries = Object.entries(values || {})
    .map(([label, count]) => [label, Number(count) || 0])
    .sort((left, right) => right[1] - left[1])
  return entries[0] || null
}

function findProgress(items, term) {
  if (!Array.isArray(items)) return null
  return items.find(item => normalizeLabel(item.category).includes(term))
}

function pluralize(value, singular, plural = `${singular}s`) {
  return value === 1 ? singular : plural
}

function sectionById(sectionId) {
  return SECTION_LIBRARY.find(section => section.id === sectionId) || SECTION_LIBRARY[0]
}

function draftFromReport(report) {
  const reportData = report?.reportData || {}
  const filters = reportData.filters || {}
  const manual = reportData.manualContent || {}
  const range = reportData.range || {}
  const sections = report?.sections || reportData.sections || []
  const savedSectionSettings = Array.isArray(reportData.sectionSettings) ? reportData.sectionSettings : []
  const sectionSettings = buildSectionSettings(sections, savedSectionSettings).map((settings, index) => {
    const sectionId = sections[index]
    if (sectionId === 'summary') return { ...settings, narrative: manual.summaryText || reportData.sectionData?.summary?.text || '' }
    if (sectionId === 'custom') return { ...settings, narrative: manual.customSectionText || reportData.sectionData?.custom?.text || '' }
    if (sectionId === 'safety') return { ...settings, narrative: manual.safetyNotes || reportData.sectionData?.safety?.text || '' }
    if (sectionId === 'commercials') return { ...settings, narrative: manual.commercialNotes || reportData.sectionData?.commercials?.text || '' }
    if (sectionId === 'progressphotos') return { ...settings, narrative: manual.progressPhotosText || reportData.sectionData?.progressphotos?.text || '' }
    if (sectionId === 'issues') return { ...settings, issueStatuses: filters.issueStatuses || [] }
    if (sectionId === 'checklists') return { ...settings, checklistStatuses: filters.checklistStatuses || [] }
    if (sectionId === 'equipment') return { ...settings, equipmentTypes: filters.equipmentTypes || [] }
    return settings
  })
  return createDraft({
    id: `report-${report?.id || Date.now()}`,
    name: report?.title || 'Saved report',
    description: report?.subtitle || '',
    reportType: report?.reportType || 'custom',
    dateFrom: range.from || report?.dateFrom,
    dateTo: range.to || report?.dateTo,
    sections,
    issueStatuses: filters.issueStatuses || [],
    checklistStatuses: filters.checklistStatuses || [],
    equipmentTypes: filters.equipmentTypes || [],
    summaryText: manual.summaryText || reportData.sectionData?.summary?.text || '',
    safetyNotes: manual.safetyNotes || reportData.sectionData?.safety?.text || '',
    commercialNotes: manual.commercialNotes || reportData.sectionData?.commercials?.text || '',
    customSectionText: manual.customSectionText || reportData.sectionData?.custom?.text || '',
    progressPhotosText: manual.progressPhotosText || reportData.sectionData?.progressphotos?.text || '',
    projectDescription: manual.projectDescription || '',
    clientName: manual.clientName || '',
    projectCode: manual.projectCode || '',
    shiftWindow: manual.shiftWindow || '',
    reportAuthor: manual.reportAuthor || '',
    peopleOnSite: manual.peopleOnSite || '',
    sectionSettings,
  })
}

function SurfaceCard({ children, style, ...props }) {
  return (
    <div
      style={{
        background: 'var(--bg-card)',
        border: '1px solid var(--border)',
        borderRadius: 18,
        padding: 18,
        ...style,
      }}
      {...props}
    >
      {children}
    </div>
  )
}

function Button({ children, icon: Icon, variant = 'secondary', disabled = false, onClick, style, type = 'button' }) {
  const variants = {
    primary: { background: '#2563eb', border: '1px solid #2563eb', color: '#ffffff' },
    secondary: { background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)' },
    subtle: { background: 'transparent', border: '1px solid transparent', color: '#94a3b8' },
  }
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 8,
        padding: '10px 14px',
        borderRadius: 12,
        cursor: disabled ? 'not-allowed' : 'pointer',
        fontSize: 12,
        fontWeight: 700,
        opacity: disabled ? 0.6 : 1,
        ...variants[variant],
        ...style,
      }}
    >
      {Icon ? <Icon size={14} /> : null}
      {children}
    </button>
  )
}

function MetricCard({ label, value, sub, accent }) {
  return (
    <SurfaceCard style={{ padding: '16px 18px' }}>
      <div style={{ fontSize: 10, fontWeight: 800, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.08em' }}>{label}</div>
      <div style={{ fontSize: 30, fontWeight: 800, color: accent || 'var(--text-primary)', lineHeight: 1.1, marginTop: 10 }}>{value}</div>
      <div style={{ fontSize: 12, color: '#64748b', marginTop: 8, lineHeight: 1.5 }}>{sub}</div>
    </SurfaceCard>
  )
}

function CountPills({ values, color }) {
  const entries = Object.entries(values || {})
  if (!entries.length) return null
  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
      {entries.map(([label, count]) => (
        <span
          key={label}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 6,
            padding: '6px 10px',
            borderRadius: 999,
            background: color?.tone || 'rgba(148,163,184,0.12)',
            border: `1px solid ${color?.accent || 'rgba(148,163,184,0.18)'}`,
            color: color?.text || '#cbd5e1',
            fontSize: 11,
            fontWeight: 700,
          }}
        >
          <span>{label}</span>
          <span>{count}</span>
        </span>
      ))}
    </div>
  )
}

function CountBarChart({ title, values, accent = '#60a5fa' }) {
  const entries = Object.entries(values || {})
    .map(([label, count]) => ({ label, count: Number(count) || 0 }))
    .sort((left, right) => right.count - left.count)
    .slice(0, 6)

  if (!entries.length) return null
  const max = Math.max(...entries.map(entry => entry.count), 1)

  return (
    <SurfaceCard style={{ padding: 16 }}>
      <div style={{ fontSize: 13, fontWeight: 800, color: 'var(--text-primary)', marginBottom: 12 }}>{title}</div>
      <div style={{ display: 'grid', gap: 10 }}>
        {entries.map(entry => (
          <div key={entry.label} style={{ display: 'grid', gap: 6 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 10, fontSize: 12 }}>
              <span style={{ color: '#cbd5e1', fontWeight: 600 }}>{entry.label}</span>
              <span style={{ color: '#94a3b8' }}>{entry.count}</span>
            </div>
            <div style={{ height: 10, background: 'rgba(148,163,184,0.12)', borderRadius: 999, overflow: 'hidden' }}>
              <div style={{ height: '100%', width: `${(entry.count / max) * 100}%`, background: accent, borderRadius: 999 }} />
            </div>
          </div>
        ))}
      </div>
    </SurfaceCard>
  )
}

function ProgressChart({ title, items, accentOpen = '#f97316', accentClosed = '#22c55e' }) {
  const rows = Array.isArray(items) ? items.slice(0, 6) : []
  if (!rows.length) return null

  return (
    <SurfaceCard style={{ padding: 16 }}>
      <div style={{ fontSize: 13, fontWeight: 800, color: 'var(--text-primary)', marginBottom: 12 }}>{title}</div>
      <div style={{ display: 'grid', gap: 12 }}>
        {rows.map(item => {
          const total = Number(item.total || 0)
          const open = Number(item.open || 0)
          const closed = Number(item.closed || 0)
          const openWidth = total ? `${(open / total) * 100}%` : '0%'
          const closedWidth = total ? `${(closed / total) * 100}%` : '0%'
          return (
            <div key={item.category} style={{ display: 'grid', gap: 6 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 10, fontSize: 12 }}>
                <span style={{ color: '#cbd5e1', fontWeight: 700 }}>{item.category}</span>
                <span style={{ color: '#94a3b8' }}>{total} total</span>
              </div>
              <div style={{ fontSize: 11, color: '#94a3b8' }}>Open {open} / Closed {closed}</div>
              <div style={{ display: 'flex', height: 10, borderRadius: 999, overflow: 'hidden', background: 'rgba(148,163,184,0.12)' }}>
                <div style={{ width: openWidth, background: accentOpen }} />
                <div style={{ width: closedWidth, background: accentClosed }} />
              </div>
            </div>
          )
        })}
      </div>
    </SurfaceCard>
  )
}

function DataTable({ rows, maxRows = 6 }) {
  if (!Array.isArray(rows) || rows.length === 0) return null
  const visibleRows = rows.slice(0, maxRows)
  const headers = Object.keys(visibleRows[0] || {})

  return (
    <div style={{ overflowX: 'auto', border: '1px solid var(--border)', borderRadius: 14 }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 720 }}>
        <thead>
          <tr style={{ background: 'rgba(148,163,184,0.08)' }}>
            {headers.map(header => (
              <th key={header} style={{ padding: '10px 12px', textAlign: 'left', fontSize: 10, letterSpacing: '0.08em', textTransform: 'uppercase', color: '#94a3b8', borderBottom: '1px solid var(--border)' }}>
                {String(header).replace(/([a-z])([A-Z])/g, '$1 $2')}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {visibleRows.map((row, index) => (
            <tr key={`${index}-${headers.join('-')}`}>
              {headers.map(header => (
                <td key={header} style={{ padding: '10px 12px', fontSize: 12, color: '#cbd5e1', borderBottom: index === visibleRows.length - 1 ? 'none' : '1px solid rgba(148,163,184,0.08)' }}>
                  {String(row[header] ?? '-')}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function PlaceholderSection({ meta, title, text, settings }) {
  return (
    <SurfaceCard style={{ padding: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
        <div style={{ width: 34, height: 34, borderRadius: 12, background: meta.tone, border: `1px solid ${meta.accent}`, display: 'flex', alignItems: 'center', justifyContent: 'center', color: meta.accent }}>
          <meta.icon size={16} />
        </div>
        <div>
          <div style={{ fontSize: 14, fontWeight: 800, color: 'var(--text-primary)' }}>{title || meta.label}</div>
          <div style={{ fontSize: 12, color: '#64748b' }}>{meta.description}</div>
        </div>
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 12 }}>
        {settings?.includeInsights ? <span style={{ padding: '5px 9px', borderRadius: 999, background: 'rgba(37,99,235,0.08)', border: '1px solid rgba(37,99,235,0.18)', color: '#93c5fd', fontSize: 11, fontWeight: 700 }}>Insights</span> : null}
        {settings?.includeChart ? <span style={{ padding: '5px 9px', borderRadius: 999, background: 'rgba(167,139,250,0.08)', border: '1px solid rgba(167,139,250,0.18)', color: '#c4b5fd', fontSize: 11, fontWeight: 700 }}>Chart</span> : null}
        {settings?.includeTable ? <span style={{ padding: '5px 9px', borderRadius: 999, background: 'rgba(34,197,94,0.08)', border: '1px solid rgba(34,197,94,0.18)', color: '#86efac', fontSize: 11, fontWeight: 700 }}>Table</span> : null}
      </div>
      {text ? (
        <div style={{ fontSize: 13, color: '#cbd5e1', lineHeight: 1.7, whiteSpace: 'pre-wrap' }}>{text}</div>
      ) : (
        <div style={{ fontSize: 12, color: '#64748b', lineHeight: 1.7 }}>
          This section is part of the template. Once you generate the report, it will render the insight block, graph, and table for the chosen date range.
        </div>
      )}
      {meta.id === 'progressphotos' ? (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 10, marginTop: 14 }}>
          {[1, 2, 3].map(slot => (
            <div key={slot} style={{ height: 96, borderRadius: 14, border: '1px dashed rgba(148,163,184,0.28)', background: 'rgba(148,163,184,0.06)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#64748b', fontSize: 12, fontWeight: 600 }}>
              Photo slot {slot}
            </div>
          ))}
        </div>
      ) : null}
    </SurfaceCard>
  )
}

function buildInsights(sectionId, data, rangeLabel) {
  if (!data) return []

  if (sectionId === 'checklists') {
    const total = Number(data.totalRows || sumCounts(data.byStatus))
    const closed = matchCount(data.byStatus, ['closed', 'complete', 'completed', 'finished', 'approved', 'pass'])
    const open = Math.max(total - closed, 0)
    const red = findProgress(data.progressByCategory, 'red')
    const yellow = findProgress(data.progressByCategory, 'yellow')
    const largest = (Array.isArray(data.progressByCategory) ? [...data.progressByCategory] : []).sort((left, right) => Number(right.open || 0) - Number(left.open || 0))[0]
    const lines = []
    if (total > 0) lines.push(`${rangeLabel}, ${closed} of ${total} checklists are closed${open ? ` and ${open} remain active.` : '.'}`)
    if (red) lines.push(red.open === 0 ? 'During this period all red-tag checklists are done.' : `${red.open} red-tag ${pluralize(red.open, 'checklist')} still open in this window.`)
    if (yellow) lines.push(`${yellow.total} yellow-tag checklists were active, with ${yellow.closed} already closed.`)
    if (largest && Number(largest.open || 0) > 0) lines.push(`${largest.category} is carrying the biggest remaining checklist backlog.`)
    return lines
  }

  if (sectionId === 'issues') {
    const total = Number(data.totalRows || sumCounts(data.byStatus))
    const closed = matchCount(data.byStatus, ['closed', 'complete', 'completed', 'resolved', 'done'])
    const open = Math.max(total - closed, 0)
    const red = findProgress(data.progressByCategory, 'red')
    const yellow = findProgress(data.progressByCategory, 'yellow')
    const topLocation = topEntry(data.topLocations)
    const lines = []
    if (total > 0) lines.push(`${rangeLabel}, ${closed} of ${total} tracked issues are closed${open ? ` and ${open} still need attention.` : '.'}`)
    if (red) lines.push(red.open === 0 ? 'All red-priority issues in the selected window are closed.' : `${red.open} red-priority ${pluralize(red.open, 'issue')} remain open.`)
    if (yellow) lines.push(`${yellow.total} yellow-priority issues were active during this period.`)
    if (topLocation) lines.push(`${topLocation[0]} is currently the busiest location with ${topLocation[1]} logged issues.`)
    return lines
  }

  if (sectionId === 'equipment') {
    const total = Number(data.totalRows || sumCounts(data.byType))
    const topType = topEntry(data.byType)
    const topStatus = topEntry(data.byStatus)
    const lines = []
    if (total > 0) lines.push(`${rangeLabel}, ${total} equipment records are captured in the report snapshot.`)
    if (topType) lines.push(`${topType[0]} is the largest equipment group in this report.`)
    if (topStatus) lines.push(`${topStatus[0]} is the most common equipment status right now.`)
    return lines
  }

  if (sectionId === 'tests') {
    const totalTests = Number(data.totalTests || 0)
    const topType = topEntry(data.byType)
    const lines = []
    if (totalTests > 0) lines.push(`${rangeLabel}, ${totalTests} tests are represented in the selected report window.`)
    if (topType) lines.push(`${topType[0]} is the most active testing bucket in this snapshot.`)
    return lines
  }

  if (sectionId === 'personnel') {
    const total = Number(data.totalRows || sumCounts(data.byCompany))
    const topCompany = topEntry(data.byCompany)
    return [total > 0 ? `${total} personnel rows are included in this report window.` : null, topCompany ? `${topCompany[0]} has the largest represented team in this period.` : null].filter(Boolean)
  }

  if (sectionId === 'activities') return Number(data.totalRows || 0) > 0 ? [`${data.totalRows} delivery activities are captured for ${rangeLabel}.`] : []
  if (sectionId === 'upcoming') return Number(data.totalRows || 0) > 0 ? [`${data.totalRows} upcoming actions are due in the near-term lookahead.`] : []

  return []
}

function SectionPreviewCard({ sectionId, previewReport, draft, settings }) {
  const meta = sectionById(sectionId)
  const reportData = previewReport?.reportData || {}
  const sectionData = reportData.sectionData?.[sectionId]
  const rangeLabel = reportData.range?.label || formatRangeLabel(draft.dateFrom, draft.dateTo)
  const insights = settings?.includeInsights === false ? [] : buildInsights(sectionId, sectionData, rangeLabel)
  const draftTextBySection = {
    summary: settings?.narrative || draft.summaryText,
    custom: settings?.narrative || draft.customSectionText,
    safety: settings?.narrative || draft.safetyNotes,
    commercials: settings?.narrative || draft.commercialNotes,
    progressphotos: settings?.narrative || draft.progressPhotosText,
  }
  const displayTitle = settings?.title || meta.label

  if (!sectionData) return <PlaceholderSection meta={meta} title={displayTitle} text={draftTextBySection[sectionId]} settings={settings} />

  return (
    <SurfaceCard style={{ padding: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 }}>
        <div style={{ width: 34, height: 34, borderRadius: 12, background: meta.tone, border: `1px solid ${meta.accent}`, display: 'flex', alignItems: 'center', justifyContent: 'center', color: meta.accent }}>
          <meta.icon size={16} />
        </div>
        <div>
          <div style={{ fontSize: 14, fontWeight: 800, color: 'var(--text-primary)' }}>{displayTitle}</div>
          <div style={{ fontSize: 12, color: '#64748b' }}>{rangeLabel}</div>
        </div>
      </div>

      {insights.length ? (
        <div style={{ display: 'grid', gap: 8, marginBottom: 14 }}>
          {insights.map(line => (
            <div key={line} style={{ padding: '10px 12px', borderRadius: 12, background: 'rgba(37,99,235,0.08)', border: '1px solid rgba(37,99,235,0.18)', color: '#dbeafe', fontSize: 12, lineHeight: 1.6 }}>
              {line}
            </div>
          ))}
        </div>
      ) : null}

      {settings?.narrative ? <div style={{ fontSize: 13, color: '#cbd5e1', lineHeight: 1.7, whiteSpace: 'pre-wrap', marginBottom: 14 }}>{settings.narrative}</div> : null}
      {sectionData.text && sectionData.text !== settings?.narrative ? <div style={{ fontSize: 13, color: '#cbd5e1', lineHeight: 1.7, whiteSpace: 'pre-wrap', marginBottom: 14 }}>{sectionData.text}</div> : null}

      <div style={{ display: 'grid', gap: 14 }}>
        {settings?.includeChart !== false && sectionData.byTag ? <CountBarChart title="Tag distribution" values={sectionData.byTag} accent="#f59e0b" /> : null}
        {settings?.includeChart !== false && sectionData.byPriority ? <CountBarChart title="Priority distribution" values={sectionData.byPriority} accent="#f97316" /> : null}
        {settings?.includeChart !== false && sectionData.byStatus ? <CountBarChart title="Status split" values={sectionData.byStatus} accent={meta.accent} /> : null}
        {settings?.includeChart !== false && sectionData.byType ? <CountBarChart title="Type split" values={sectionData.byType} accent={meta.accent} /> : null}
        {settings?.includeChart !== false && sectionData.byCompany ? <CountBarChart title="Company mix" values={sectionData.byCompany} accent="#818cf8" /> : null}
        {settings?.includeChart !== false && sectionData.topLocations ? <CountBarChart title="Top locations" values={sectionData.topLocations} accent="#38bdf8" /> : null}
        {settings?.includeChart !== false && sectionData.progressByCategory ? <ProgressChart title="Open vs closed trend" items={sectionData.progressByCategory} accentOpen={sectionId === 'issues' ? '#f97316' : '#f59e0b'} accentClosed="#22c55e" /> : null}
        {settings?.includeChart !== false && sectionId === 'progressphotos' ? (
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 10 }}>
            {[1, 2, 3].map(slot => (
              <div key={slot} style={{ height: 96, borderRadius: 14, border: '1px dashed rgba(148,163,184,0.28)', background: 'rgba(148,163,184,0.06)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#64748b', fontSize: 12, fontWeight: 600 }}>
                Photo slot {slot}
              </div>
            ))}
          </div>
        ) : null}
        {settings?.includeInsights !== false && sectionData.totalTests !== undefined ? <div style={{ fontSize: 12, fontWeight: 700, color: '#7dd3fc' }}>Total tests captured: {sectionData.totalTests}</div> : null}
        {settings?.includeChart !== false && (sectionData.byStatus || sectionData.byType || sectionData.byTag || sectionData.byPriority || sectionData.byCompany) ? (
          <CountPills values={sectionData.byStatus || sectionData.byType || sectionData.byTag || sectionData.byPriority || sectionData.byCompany} color={{ accent: 'rgba(148,163,184,0.18)', tone: 'rgba(148,163,184,0.12)', text: '#cbd5e1' }} />
        ) : null}
        {settings?.includeTable !== false && Array.isArray(sectionData.rows) && sectionData.rows.length ? (
          <>
            <div style={{ fontSize: 11, color: '#64748b' }}>Showing up to 6 rows from the saved snapshot{Number.isFinite(sectionData.totalRows) ? ` out of ${sectionData.totalRows}` : ''}.</div>
            <DataTable rows={sectionData.rows} maxRows={6} />
          </>
        ) : null}
      </div>
    </SurfaceCard>
  )
}

function AccordionPanel({ title, subtitle, count, open, onToggle, children }) {
  return (
    <SurfaceCard style={{ padding: 0, overflow: 'hidden' }}>
      <button type="button" onClick={onToggle} style={{ width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12, padding: '16px 18px', background: 'transparent', border: 'none', cursor: 'pointer', color: 'inherit' }}>
        <div style={{ textAlign: 'left' }}>
          <div style={{ fontSize: 14, fontWeight: 800, color: 'var(--text-primary)' }}>{title}</div>
          <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>{subtitle}</div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          {count !== undefined ? <span style={{ minWidth: 28, height: 28, padding: '0 10px', borderRadius: 999, border: '1px solid rgba(37,99,235,0.2)', background: 'rgba(37,99,235,0.08)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', color: '#93c5fd', fontSize: 11, fontWeight: 800 }}>{count}</span> : null}
          {open ? <ChevronUp size={16} color="#94a3b8" /> : <ChevronDown size={16} color="#94a3b8" />}
        </div>
      </button>
      {open ? (
        <div style={{ padding: '0 18px 18px', borderTop: '1px solid rgba(148,163,184,0.08)' }}>
          <div style={{ paddingTop: 18 }}>{children}</div>
        </div>
      ) : null}
    </SurfaceCard>
  )
}

function SectionLibraryCard({ section, onAdd, onDragStart }) {
  const Icon = section.icon
  return (
    <div draggable onDragStart={onDragStart} style={{ display: 'grid', gridTemplateColumns: 'auto 1fr auto', gap: 10, alignItems: 'center', padding: '12px 12px', borderRadius: 14, border: '1px solid var(--border)', background: 'var(--bg-base)' }}>
      <div style={{ width: 34, height: 34, borderRadius: 12, background: section.tone, border: `1px solid ${section.accent}`, display: 'flex', alignItems: 'center', justifyContent: 'center', color: section.accent }}>
        <Icon size={16} />
      </div>
      <div>
        <div style={{ fontSize: 13, fontWeight: 800, color: 'var(--text-primary)' }}>{section.label}</div>
        <div style={{ fontSize: 11, color: '#64748b', marginTop: 3, lineHeight: 1.5 }}>{section.description}</div>
      </div>
      <Button onClick={onAdd} variant="secondary" style={{ minWidth: 76, padding: '8px 12px' }}>Add</Button>
    </div>
  )
}

function TemplateSectionCard({ section, index, settings, onRemove, onDuplicate, onEdit, onDragStart, onDragOver, onDrop }) {
  const Icon = section.icon
  const chips = [
    settings?.includeInsights ? 'Insights' : null,
    settings?.includeChart ? 'Chart' : null,
    settings?.includeTable ? 'Table' : null,
  ].filter(Boolean)
  return (
    <div draggable onDragStart={onDragStart} onDragOver={onDragOver} onDrop={onDrop} style={{ display: 'grid', gridTemplateColumns: 'auto auto 1fr auto', gap: 10, alignItems: 'center', padding: '12px 12px', borderRadius: 14, border: '1px solid var(--border)', background: 'var(--bg-base)' }}>
      <div style={{ color: '#64748b', display: 'flex', alignItems: 'center', justifyContent: 'center' }}><GripVertical size={16} /></div>
      <div style={{ width: 34, height: 34, borderRadius: 12, background: section.tone, border: `1px solid ${section.accent}`, display: 'flex', alignItems: 'center', justifyContent: 'center', color: section.accent }}>
        <Icon size={16} />
      </div>
      <div>
        <div style={{ fontSize: 13, fontWeight: 800, color: 'var(--text-primary)' }}>{index + 1}. {settings?.title || section.label}</div>
        <div style={{ fontSize: 11, color: '#64748b', marginTop: 3 }}>{section.description}</div>
        {chips.length ? (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 8 }}>
            {chips.map(chip => (
              <span key={chip} style={{ padding: '4px 8px', borderRadius: 999, background: 'rgba(148,163,184,0.08)', border: '1px solid rgba(148,163,184,0.14)', color: '#cbd5e1', fontSize: 10, fontWeight: 700 }}>
                {chip}
              </span>
            ))}
          </div>
        ) : null}
      </div>
      <div style={{ display: 'flex', gap: 8 }}>
        <Button variant="subtle" onClick={onEdit} style={{ padding: 8 }}><Pencil size={14} /></Button>
        <Button variant="subtle" onClick={onDuplicate} style={{ padding: 8 }}><Copy size={14} /></Button>
        <Button variant="subtle" onClick={onRemove} style={{ padding: 8, color: '#fca5a5' }}><Trash2 size={14} /></Button>
      </div>
    </div>
  )
}

function TemplateCard({ title, description, sections, badge, onOpen, onSecondary, secondaryLabel }) {
  return (
    <SurfaceCard style={{ display: 'grid', gap: 14 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
        <div>
          <div style={{ fontSize: 15, fontWeight: 800, color: 'var(--text-primary)' }}>{title}</div>
          <div style={{ fontSize: 12, color: '#64748b', marginTop: 6, lineHeight: 1.6 }}>{description}</div>
        </div>
        {badge ? <span style={{ padding: '6px 10px', borderRadius: 999, background: 'rgba(37,99,235,0.08)', border: '1px solid rgba(37,99,235,0.18)', color: '#93c5fd', fontSize: 11, fontWeight: 800, whiteSpace: 'nowrap' }}>{badge}</span> : null}
      </div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
        {sections.map(sectionId => {
          const section = sectionById(sectionId)
          return <span key={`${title}-${sectionId}`} style={{ padding: '6px 10px', borderRadius: 999, background: section.tone, border: `1px solid ${section.accent}`, color: '#e2e8f0', fontSize: 11, fontWeight: 700 }}>{section.label}</span>
        })}
      </div>
      <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
        <Button icon={ArrowRight} variant="primary" onClick={onOpen}>Open builder</Button>
        {onSecondary ? <Button variant="secondary" onClick={onSecondary}>{secondaryLabel}</Button> : null}
      </div>
    </SurfaceCard>
  )
}

function ModalField({ label, children, hint }) {
  return (
    <div style={{ display: 'grid', gap: 6 }}>
      <div style={{ fontSize: 11, fontWeight: 700, color: '#94a3b8' }}>{label}</div>
      {children}
      {hint ? <div style={{ fontSize: 11, color: '#64748b', lineHeight: 1.5 }}>{hint}</div> : null}
    </div>
  )
}

function TogglePill({ active, onClick, children }) {
  return (
    <button
      type="button"
      onClick={onClick}
      style={{
        padding: '8px 12px',
        borderRadius: 999,
        border: active ? '1px solid rgba(37,99,235,0.35)' : '1px solid var(--border)',
        background: active ? 'rgba(37,99,235,0.12)' : 'var(--bg-base)',
        color: active ? '#dbeafe' : '#cbd5e1',
        fontSize: 11,
        fontWeight: 700,
        cursor: 'pointer',
      }}
    >
      {children}
    </button>
  )
}

export default function ReportsPage() {
  const { projects, activeProject, setActiveProject } = useProject()
  const [view, setView] = useState('gallery')
  const [draft, setDraft] = useState(createDraft())
  const [userTemplates, setUserTemplates] = useState([])
  const [options, setOptions] = useState({ issueStatuses: [], checklistStatuses: [], equipmentTypes: [] })
  const [savedReports, setSavedReports] = useState([])
  const [previewReport, setPreviewReport] = useState(null)
  const [loading, setLoading] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [downloadingId, setDownloadingId] = useState(null)
  const [dragPayload, setDragPayload] = useState(null)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [editingSectionIndex, setEditingSectionIndex] = useState(null)

  useEffect(() => {
    setUserTemplates(readStoredTemplates())
  }, [])

  useEffect(() => {
    if (draft.reportType === 'daily') {
      const today = todayIso()
      setDraft(current => ({ ...current, dateFrom: today, dateTo: today }))
    } else if (draft.reportType === 'weekly') {
      const range = weeklyRange()
      setDraft(current => ({ ...current, dateFrom: range.from, dateTo: range.to }))
    }
  }, [draft.reportType])

  useEffect(() => {
    if (!activeProject?.externalId) return
    let ignore = false

    async function loadReportData() {
      setLoading(true)
      try {
        const [optionsResponse, reportsResponse] = await Promise.all([reportsApi.getOptions(activeProject.externalId), reportsApi.getAll(activeProject.externalId)])
        if (ignore) return
        setOptions(optionsResponse.data.data || { issueStatuses: [], checklistStatuses: [], equipmentTypes: [] })
        setSavedReports(reportsResponse.data.data || [])
      } catch (error) {
        if (!ignore) toast.error(error.response?.data?.message || 'Failed to load report workspace data')
      } finally {
        if (!ignore) setLoading(false)
      }
    }

    loadReportData()
    return () => { ignore = true }
  }, [activeProject?.externalId])

  const selectedProject = activeProject
  const totalReports = savedReports.length
  const previewSections = draft.sections
  const libraryStatusLabel = selectedProject
    ? `${selectedProject.name} | ${selectedProject.externalId} | ${totalReports} generated ${pluralize(totalReports, 'report')}`
    : 'Select a project to start building a report template.'
  const librarySubtitle = useMemo(() => {
    if (!selectedProject) return 'Select a project to start building a report template.'
    return `${selectedProject.name} • ${selectedProject.externalId} • ${totalReports} generated ${pluralize(totalReports, 'report')}`
  }, [selectedProject, totalReports])

  const currentEditingSectionId = editingSectionIndex !== null ? draft.sections[editingSectionIndex] : null
  const currentEditingMeta = currentEditingSectionId ? sectionById(currentEditingSectionId) : null
  const currentEditingSettings = currentEditingSectionId ? defaultSectionSettings(currentEditingSectionId, draft.sectionSettings?.[editingSectionIndex]) : null

  function updateDraft(field, value) { setDraft(current => ({ ...current, [field]: value })) }
  function updateSectionSettings(index, patch) {
    setDraft(current => ({
      ...current,
      sectionSettings: current.sectionSettings.map((settings, itemIndex) => (
        itemIndex === index ? defaultSectionSettings(current.sections[itemIndex], { ...settings, ...patch }) : settings
      )),
    }))
  }
  function toggleSectionSelection(index, field, value) {
    const currentSettings = defaultSectionSettings(draft.sections[index], draft.sectionSettings?.[index])
    const nextValues = currentSettings[field]?.includes(value)
      ? currentSettings[field].filter(item => item !== value)
      : [...(currentSettings[field] || []), value]
    updateSectionSettings(index, { [field]: nextValues })
  }
  function startNewTemplate() { setDraft(createDraft()); setPreviewReport(null); setView('builder') }
  function openTemplate(template, isStarter = false) { setDraft(createDraft({ ...template, isStarter })); setPreviewReport(null); setView('builder') }
  function removeSection(index) {
    setDraft(current => ({
      ...current,
      sections: current.sections.filter((_, itemIndex) => itemIndex !== index),
      sectionSettings: current.sectionSettings.filter((_, itemIndex) => itemIndex !== index),
    }))
  }
  function toggleMultiSelect(field, value) {
    setDraft(current => {
      const existing = current[field] || []
      const next = existing.includes(value) ? existing.filter(item => item !== value) : [...existing, value]
      return { ...current, [field]: next }
    })
  }
  function persistTemplate(nextDraft) {
    const templateToSave = { ...nextDraft, isStarter: false }
    const nextTemplates = [templateToSave, ...userTemplates.filter(template => template.id !== templateToSave.id)]
    setUserTemplates(nextTemplates)
    writeStoredTemplates(nextTemplates)
    setDraft(templateToSave)
    toast.success('Template saved')
  }
  function duplicateTemplateSection(index) {
    const sectionId = draft.sections[index]
    const section = sectionById(sectionId)
    setDraft(current => ({
      ...current,
      sections: [...current.sections, sectionId],
      sectionSettings: [...current.sectionSettings, defaultSectionSettings(sectionId, current.sectionSettings[index])],
    }))
    toast.success(`${section.label} added again for a second placement`)
  }
  function addSection(sectionId, insertAt) {
    setDraft(current => {
      const nextSections = [...current.sections]
      const nextSettings = [...current.sectionSettings]
      if (typeof insertAt === 'number') nextSections.splice(insertAt, 0, sectionId)
      else nextSections.push(sectionId)
      if (typeof insertAt === 'number') nextSettings.splice(insertAt, 0, defaultSectionSettings(sectionId))
      else nextSettings.push(defaultSectionSettings(sectionId))
      return { ...current, sections: nextSections, sectionSettings: nextSettings }
    })
  }
  function reorderSection(fromIndex, toIndex) {
    if (fromIndex === toIndex) return
    setDraft(current => {
      const nextSections = [...current.sections]
      const nextSettings = [...current.sectionSettings]
      const [moved] = nextSections.splice(fromIndex, 1)
      const [movedSettings] = nextSettings.splice(fromIndex, 1)
      nextSections.splice(toIndex, 0, moved)
      nextSettings.splice(toIndex, 0, movedSettings)
      return { ...current, sections: nextSections, sectionSettings: nextSettings }
    })
  }
  function handleDrop(insertIndex) {
    if (!dragPayload) return
    if (dragPayload.type === 'library') addSection(dragPayload.sectionId, insertIndex)
    if (dragPayload.type === 'template') reorderSection(dragPayload.index, typeof insertIndex === 'number' ? insertIndex : draft.sections.length - 1)
    setDragPayload(null)
  }

  async function handleGenerate() {
    if (!selectedProject?.externalId) return toast.error('Select a project before generating a report')
    if (!draft.name.trim()) return toast.error('Add a template name before generating')
    if (!draft.sections.length) return toast.error('Add at least one report section')

    const issueStatuses = uniqueValues([...(draft.issueStatuses || []), ...collectSectionSelections(draft, 'issues', 'issueStatuses')])
    const checklistStatuses = uniqueValues([...(draft.checklistStatuses || []), ...collectSectionSelections(draft, 'checklists', 'checklistStatuses')])
    const equipmentTypes = uniqueValues([...(draft.equipmentTypes || []), ...collectSectionSelections(draft, 'equipment', 'equipmentTypes')])
    const summaryText = findSectionNarrative(draft, 'summary', draft.summaryText)
    const customSectionText = findSectionNarrative(draft, 'custom', draft.customSectionText)
    const safetyNotes = findSectionNarrative(draft, 'safety', draft.safetyNotes)
    const commercialNotes = findSectionNarrative(draft, 'commercials', draft.commercialNotes)
    const progressPhotosText = findSectionNarrative(draft, 'progressphotos', draft.progressPhotosText)

    setGenerating(true)
    try {
      const response = await reportsApi.generate({
        projectId: selectedProject.externalId,
        title: draft.name,
        reportType: draft.reportType,
        dateFrom: draft.dateFrom,
        dateTo: draft.dateTo,
        sections: draft.sections,
        sectionSettings: draft.sectionSettings,
        issueStatuses,
        checklistStatuses,
        equipmentTypes,
        summaryText,
        safetyNotes,
        commercialNotes,
        customSectionText,
        progressPhotosText,
        projectDescription: draft.projectDescription,
        clientName: draft.clientName,
        projectCode: draft.projectCode,
        shiftWindow: draft.shiftWindow,
        reportAuthor: draft.reportAuthor,
        peopleOnSite: draft.peopleOnSite,
      })
      const report = response.data.data
      setPreviewReport(report)
      setSavedReports(current => [report, ...current.filter(item => item.id !== report.id)])
      persistTemplate(draft)
      toast.success('Report snapshot generated')
    } catch (error) {
      toast.error(error.response?.data?.message || 'Failed to generate report')
    } finally {
      setGenerating(false)
    }
  }

  async function openSavedReport(reportId) {
    try {
      const response = await reportsApi.getById(reportId)
      const report = response.data.data
      setPreviewReport(report)
      setDraft(draftFromReport(report))
      setView('builder')
      toast.success('Saved report opened')
    } catch (error) {
      toast.error(error.response?.data?.message || 'Failed to open report')
    }
  }

  async function downloadReport(report, format) {
    setDownloadingId(`${report.id}-${format}`)
    try {
      const response = await reportsApi.download(report.id, format)
      const extension = format === 'csv' ? 'csv' : format === 'pdf' ? 'pdf' : 'json'
      const fileName = `${(report.title || 'saved-report').replace(/[^a-z0-9]+/gi, '-').replace(/(^-|-$)/g, '') || 'saved-report'}.${extension}`
      downloadBlob(response.data, fileName)
      toast.success(`${format.toUpperCase()} download started`)
    } catch (error) {
      toast.error(error.response?.data?.message || 'Download failed')
    } finally {
      setDownloadingId(null)
    }
  }

  function deleteTemplate(templateId) {
    const nextTemplates = userTemplates.filter(template => template.id !== templateId)
    setUserTemplates(nextTemplates)
    writeStoredTemplates(nextTemplates)
    toast.success('Template removed')
  }

  if (!projects.length) {
    return (
      <SurfaceCard style={{ padding: '48px 32px', textAlign: 'center' }}>
        <FileText size={36} style={{ color: '#64748b', marginBottom: 14 }} />
        <div style={{ fontSize: 18, fontWeight: 800, color: 'var(--text-primary)' }}>No projects available</div>
        <div style={{ fontSize: 13, color: '#64748b', marginTop: 8 }}>Load a project first so the report builder can generate a snapshot from the synced database.</div>
      </SurfaceCard>
    )
  }

  if (view === 'gallery') {
    return (
      <div style={{ display: 'grid', gap: 16 }}>
        <SurfaceCard style={{ display: 'flex', justifyContent: 'space-between', gap: 16, alignItems: 'flex-start', flexWrap: 'wrap', padding: '16px 18px' }}>
          <div>
            <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '6px 10px', borderRadius: 999, background: 'rgba(37,99,235,0.08)', border: '1px solid rgba(37,99,235,0.18)', color: '#93c5fd', fontSize: 11, fontWeight: 800 }}>
              <LayoutTemplate size={14} />
              Report Builder
            </div>
            <div style={{ fontSize: 24, fontWeight: 800, color: 'var(--text-primary)', marginTop: 12 }}>Template gallery</div>
            <div style={{ fontSize: 12, color: '#64748b', marginTop: 8, lineHeight: 1.6, maxWidth: 760 }}>
              Keep the landing clean: choose a starter template or reuse a saved one, then edit sections inside compact popups instead of a crowded builder page.
            </div>
          </div>
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center' }}>
            <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '10px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)', color: '#cbd5e1', fontSize: 12, fontWeight: 700 }}>
              <FolderOpen size={14} />
              {libraryStatusLabel}
            </div>
            <Button icon={Plus} variant="primary" onClick={startNewTemplate}>New template</Button>
          </div>
        </SurfaceCard>

        <div style={{ display: 'grid', gridTemplateColumns: '1.12fr 0.88fr', gap: 16, alignItems: 'start' }}>
          <SurfaceCard style={{ display: 'grid', gap: 16 }}>
            <div>
              <div style={{ fontSize: 16, fontWeight: 800, color: 'var(--text-primary)' }}>Starter templates</div>
              <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>Open one, then edit each section row individually.</div>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(250px, 1fr))', gap: 12 }}>
              {STARTER_TEMPLATES.map(template => (
                <TemplateCard key={template.id} title={template.name} description={template.description} sections={template.sections} badge={template.reportType.toUpperCase()} onOpen={() => openTemplate(template, true)} />
              ))}
            </div>

            <div style={{ paddingTop: 8, borderTop: '1px solid rgba(148,163,184,0.08)' }}>
              <div style={{ fontSize: 16, fontWeight: 800, color: 'var(--text-primary)' }}>My templates</div>
              <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>Reusable local templates for the current workspace.</div>
            </div>

            {userTemplates.length ? (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(250px, 1fr))', gap: 12 }}>
                {userTemplates.map(template => (
                  <TemplateCard key={template.id} title={template.name} description={template.description || 'Custom reusable report template.'} sections={template.sections || []} badge="Saved" onOpen={() => openTemplate(template)} onSecondary={() => deleteTemplate(template.id)} secondaryLabel="Delete" />
                ))}
              </div>
            ) : (
              <SurfaceCard style={{ padding: '20px 18px', background: 'var(--bg-base)' }}>
                <div style={{ fontSize: 14, fontWeight: 800, color: 'var(--text-primary)' }}>No saved templates yet</div>
                <div style={{ fontSize: 12, color: '#64748b', marginTop: 6, lineHeight: 1.6 }}>Create a blank template or reuse a starter template. The builder will stay compact once you step in.</div>
              </SurfaceCard>
            )}
          </SurfaceCard>

          <SurfaceCard style={{ display: 'grid', gap: 14 }}>
            <div>
              <div style={{ fontSize: 16, fontWeight: 800, color: 'var(--text-primary)' }}>Generated reports library</div>
              <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>{librarySubtitle}</div>
            </div>
            <div style={{ display: 'grid', gap: 12 }}>
              {loading ? (
                <SurfaceCard style={{ padding: '20px 18px', background: 'var(--bg-base)' }}><div style={{ fontSize: 12, color: '#64748b' }}>Loading saved reports...</div></SurfaceCard>
              ) : savedReports.length ? (
                savedReports.map(report => (
                  <SurfaceCard key={report.id} style={{ padding: 16, background: 'var(--bg-base)' }}>
                    <div style={{ fontSize: 14, fontWeight: 800, color: 'var(--text-primary)' }}>{report.title}</div>
                    <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>{report.subtitle}</div>
                    <div style={{ fontSize: 11, color: '#64748b', marginTop: 8 }}>Saved {formatDate(report.generatedAt)}</div>
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 14 }}>
                      <Button icon={Eye} variant="secondary" onClick={() => openSavedReport(report.id)}>Open snapshot</Button>
                      <Button icon={Download} variant="secondary" onClick={() => downloadReport(report, 'pdf')} disabled={downloadingId === `${report.id}-pdf`}>{downloadingId === `${report.id}-pdf` ? 'Preparing...' : 'PDF'}</Button>
                      <Button variant="secondary" onClick={() => downloadReport(report, 'json')} disabled={downloadingId === `${report.id}-json`}>JSON</Button>
                    </div>
                  </SurfaceCard>
                ))
              ) : (
                <SurfaceCard style={{ padding: '20px 18px', background: 'var(--bg-base)' }}>
                  <div style={{ fontSize: 14, fontWeight: 800, color: 'var(--text-primary)' }}>No generated reports yet</div>
                  <div style={{ fontSize: 12, color: '#64748b', marginTop: 6, lineHeight: 1.6 }}>Build a template first, then generate the first snapshot from the compact builder.</div>
                </SurfaceCard>
              )}
            </div>
          </SurfaceCard>
        </div>
      </div>
    )
  }

  return (
    <div style={{ display: 'grid', gap: 16 }}>
      <SurfaceCard style={{ display: 'grid', gap: 14, padding: '16px 18px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap', alignItems: 'flex-start' }}>
          <div style={{ display: 'grid', gap: 10 }}>
            <button type="button" onClick={() => setView('gallery')} style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: 0, border: 'none', background: 'transparent', color: '#93c5fd', fontSize: 12, fontWeight: 700, cursor: 'pointer' }}>
              <ArrowLeft size={14} />
              Back to template gallery
            </button>
            <div style={{ fontSize: 26, fontWeight: 800, color: 'var(--text-primary)' }}>{draft.name || 'New Report Template'}</div>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <span style={{ padding: '6px 10px', borderRadius: 999, background: 'rgba(37,99,235,0.08)', border: '1px solid rgba(37,99,235,0.18)', color: '#93c5fd', fontSize: 11, fontWeight: 800 }}>{selectedProject?.name || 'No project selected'}</span>
              {selectedProject?.externalId ? <span style={{ padding: '6px 10px', borderRadius: 999, background: 'rgba(148,163,184,0.08)', border: '1px solid rgba(148,163,184,0.18)', color: '#cbd5e1', fontSize: 11, fontWeight: 700 }}>{selectedProject.externalId}</span> : null}
              <span style={{ padding: '6px 10px', borderRadius: 999, background: 'rgba(148,163,184,0.08)', border: '1px solid rgba(148,163,184,0.18)', color: '#cbd5e1', fontSize: 11, fontWeight: 700 }}>{draft.reportType}</span>
              <span style={{ padding: '6px 10px', borderRadius: 999, background: 'rgba(148,163,184,0.08)', border: '1px solid rgba(148,163,184,0.18)', color: '#cbd5e1', fontSize: 11, fontWeight: 700 }}>{formatRangeLabel(draft.dateFrom, draft.dateTo)}</span>
              <span style={{ padding: '6px 10px', borderRadius: 999, background: 'rgba(148,163,184,0.08)', border: '1px solid rgba(148,163,184,0.18)', color: '#cbd5e1', fontSize: 11, fontWeight: 700 }}>{draft.sections.length} {pluralize(draft.sections.length, 'section')}</span>
            </div>
          </div>
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
            <Button variant="secondary" icon={LayoutTemplate} onClick={() => setSettingsOpen(true)}>Report settings</Button>
            <Button variant="secondary" icon={Save} onClick={() => persistTemplate(draft)}>Save template</Button>
            <Button icon={RefreshCw} variant="primary" onClick={handleGenerate} disabled={generating}>{generating ? 'Generating...' : 'Generate snapshot'}</Button>
          </div>
        </div>
      </SurfaceCard>

      <div style={{ display: 'grid', gridTemplateColumns: '1.08fr 0.92fr', gap: 16, alignItems: 'start' }}>
        <SurfaceCard style={{ display: 'grid', gap: 12, padding: '16px 18px' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12 }}>
            <div>
              <div style={{ fontSize: 16, fontWeight: 800, color: 'var(--text-primary)' }}>Template sections</div>
              <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>Reorder here, then edit any row for narrative, charts, and data choices.</div>
            </div>
            <Button icon={Plus} variant="secondary" onClick={startNewTemplate}>Blank template</Button>
          </div>

          <div style={{ display: 'grid', gap: 10 }} onDragOver={event => event.preventDefault()} onDrop={() => handleDrop()}>
            {draft.sections.length ? draft.sections.map((sectionId, index) => {
              const section = sectionById(sectionId)
              const sectionSettings = defaultSectionSettings(sectionId, draft.sectionSettings?.[index])
              return (
                <TemplateSectionCard
                  key={`${sectionId}-${index}`}
                  section={section}
                  index={index}
                  settings={sectionSettings}
                  onEdit={() => setEditingSectionIndex(index)}
                  onRemove={() => removeSection(index)}
                  onDuplicate={() => duplicateTemplateSection(index)}
                  onDragStart={() => setDragPayload({ type: 'template', index })}
                  onDragOver={event => event.preventDefault()}
                  onDrop={() => handleDrop(index)}
                />
              )
            }) : (
              <div style={{ minHeight: 220, borderRadius: 16, border: '1px dashed rgba(148,163,184,0.22)', background: 'rgba(148,163,184,0.04)', display: 'flex', alignItems: 'center', justifyContent: 'center', textAlign: 'center', color: '#64748b', fontSize: 13, lineHeight: 1.7, padding: 24 }}>
                Add a section from the right to start the template. Each row can be edited later without cluttering the main page.
              </div>
            )}
          </div>
        </SurfaceCard>

        <SurfaceCard style={{ display: 'grid', gap: 12, padding: '16px 18px' }}>
          <div>
            <div style={{ fontSize: 16, fontWeight: 800, color: 'var(--text-primary)' }}>Available sections</div>
            <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>Drag in or add the sections you need, then tune each one from its edit popup.</div>
          </div>
          <div style={{ display: 'grid', gap: 10 }}>
            {SECTION_LIBRARY.map(section => <SectionLibraryCard key={section.id} section={section} onAdd={() => addSection(section.id)} onDragStart={() => setDragPayload({ type: 'library', sectionId: section.id })} />)}
          </div>
        </SurfaceCard>
      </div>

      <div style={{ display: 'grid', gap: 16 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <div>
            <div style={{ fontSize: 18, fontWeight: 800, color: 'var(--text-primary)' }}>Output preview</div>
            <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>Sections stay compact in the builder. The preview reflects the latest template setup and snapshot data.</div>
          </div>
          <div style={{ fontSize: 12, color: '#94a3b8' }}>{previewReport ? 'Previewing the latest generated snapshot.' : 'Showing template placeholders until the first generation run.'}</div>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))', gap: 16 }}>
          {previewSections.map((sectionId, index) => (
            <SectionPreviewCard
              key={`${sectionId}-${index}-preview`}
              sectionId={sectionId}
              previewReport={previewReport}
              draft={draft}
              settings={defaultSectionSettings(sectionId, draft.sectionSettings?.[index])}
            />
          ))}
        </div>
      </div>

      <Modal open={settingsOpen} onClose={() => setSettingsOpen(false)} title="Report settings">
        <div style={{ display: 'grid', gap: 14 }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 12 }}>
            <ModalField label="Project">
              <select value={selectedProject?.id || ''} onChange={event => setActiveProject(projects.find(project => String(project.id) === event.target.value) || null)} style={{ width: '100%', padding: '10px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12 }}>
                {projects.map(project => <option key={project.id} value={project.id}>{project.name} ({project.externalId})</option>)}
              </select>
            </ModalField>
            <ModalField label="Template name">
              <input value={draft.name} onChange={event => updateDraft('name', event.target.value)} placeholder="Executive Weekly Update" style={{ width: '100%', padding: '10px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12 }} />
            </ModalField>
            <ModalField label="Cadence">
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>{REPORT_TYPES.map(type => <TogglePill key={type.id} active={draft.reportType === type.id} onClick={() => updateDraft('reportType', type.id)}>{type.label}</TogglePill>)}</div>
            </ModalField>
            <ModalField label="Client">
              <input value={draft.clientName} onChange={event => updateDraft('clientName', event.target.value)} placeholder="Client or operator" style={{ width: '100%', padding: '10px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12 }} />
            </ModalField>
            <ModalField label="Date from">
              <input type="date" value={draft.dateFrom} onChange={event => updateDraft('dateFrom', event.target.value)} disabled={draft.reportType !== 'custom'} style={{ width: '100%', padding: '10px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, opacity: draft.reportType === 'custom' ? 1 : 0.7 }} />
            </ModalField>
            <ModalField label="Date to">
              <input type="date" value={draft.dateTo} onChange={event => updateDraft('dateTo', event.target.value)} disabled={draft.reportType !== 'custom'} style={{ width: '100%', padding: '10px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, opacity: draft.reportType === 'custom' ? 1 : 0.7 }} />
            </ModalField>
            <ModalField label="Project code">
              <input value={draft.projectCode} onChange={event => updateDraft('projectCode', event.target.value)} placeholder="Internal package or code" style={{ width: '100%', padding: '10px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12 }} />
            </ModalField>
            <ModalField label="Report author">
              <input value={draft.reportAuthor} onChange={event => updateDraft('reportAuthor', event.target.value)} placeholder="Prepared by" style={{ width: '100%', padding: '10px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12 }} />
            </ModalField>
          </div>
          <ModalField label="Template description">
            <textarea value={draft.description} onChange={event => updateDraft('description', event.target.value)} placeholder="What is this template for?" style={{ width: '100%', minHeight: 86, padding: '12px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, resize: 'vertical' }} />
          </ModalField>
          <ModalField label="Project / site notes">
            <textarea value={draft.projectDescription} onChange={event => updateDraft('projectDescription', event.target.value)} placeholder="Project context, shift window, or other report-level notes." style={{ width: '100%', minHeight: 90, padding: '12px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, resize: 'vertical' }} />
          </ModalField>
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
            <Button variant="secondary" onClick={() => setSettingsOpen(false)}>Close</Button>
          </div>
        </div>
      </Modal>

      <Modal open={editingSectionIndex !== null && Boolean(currentEditingMeta)} onClose={() => setEditingSectionIndex(null)} title={currentEditingMeta ? `Edit ${currentEditingMeta.label} section` : 'Edit section'}>
        {currentEditingMeta && currentEditingSettings ? (
          <div style={{ display: 'grid', gap: 14 }}>
            <ModalField label="Section title">
              <input value={currentEditingSettings.title} onChange={event => updateSectionSettings(editingSectionIndex, { title: event.target.value })} placeholder={currentEditingMeta.label} style={{ width: '100%', padding: '10px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12 }} />
            </ModalField>
            <ModalField label="Section notes" hint="Use this for narrative, headlines, or manual context for the section.">
              <textarea value={currentEditingSettings.narrative} onChange={event => updateSectionSettings(editingSectionIndex, { narrative: event.target.value })} placeholder={`Notes for ${currentEditingMeta.label}`} style={{ width: '100%', minHeight: 96, padding: '12px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, resize: 'vertical' }} />
            </ModalField>
            <ModalField label="Output blocks" hint="These control how the section preview renders and prepare the section for later output wiring.">
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                <TogglePill active={currentEditingSettings.includeInsights} onClick={() => updateSectionSettings(editingSectionIndex, { includeInsights: !currentEditingSettings.includeInsights })}>Insights</TogglePill>
                <TogglePill active={currentEditingSettings.includeChart} onClick={() => updateSectionSettings(editingSectionIndex, { includeChart: !currentEditingSettings.includeChart })}>Chart</TogglePill>
                <TogglePill active={currentEditingSettings.includeTable} onClick={() => updateSectionSettings(editingSectionIndex, { includeTable: !currentEditingSettings.includeTable })}>Table</TogglePill>
              </div>
            </ModalField>
            {currentEditingSectionId === 'issues' ? (
              <ModalField label="Issue statuses">
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>{options.issueStatuses.map(status => <TogglePill key={status} active={currentEditingSettings.issueStatuses.includes(status.toLowerCase())} onClick={() => toggleSectionSelection(editingSectionIndex, 'issueStatuses', status.toLowerCase())}>{status}</TogglePill>)}</div>
              </ModalField>
            ) : null}
            {currentEditingSectionId === 'checklists' ? (
              <ModalField label="Checklist statuses">
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>{options.checklistStatuses.map(status => <TogglePill key={status} active={currentEditingSettings.checklistStatuses.includes(status.toLowerCase())} onClick={() => toggleSectionSelection(editingSectionIndex, 'checklistStatuses', status.toLowerCase())}>{status}</TogglePill>)}</div>
              </ModalField>
            ) : null}
            {currentEditingSectionId === 'equipment' ? (
              <ModalField label="Equipment types">
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>{options.equipmentTypes.map(type => <TogglePill key={type} active={currentEditingSettings.equipmentTypes.includes(type.toLowerCase())} onClick={() => toggleSectionSelection(editingSectionIndex, 'equipmentTypes', type.toLowerCase())}>{type}</TogglePill>)}</div>
              </ModalField>
            ) : null}
            <ModalField label="Chart style">
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                {(currentEditingSectionId === 'progressphotos' ? ['gallery', 'headline'] : currentEditingSectionId === 'summary' ? ['headline', 'bar'] : ['bar', 'progress', 'trend']).map(option => (
                  <TogglePill key={option} active={currentEditingSettings.chartType === option} onClick={() => updateSectionSettings(editingSectionIndex, { chartType: option })}>{option}</TogglePill>
                ))}
              </div>
            </ModalField>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
              <Button variant="secondary" onClick={() => setEditingSectionIndex(null)}>Close</Button>
            </div>
          </div>
        ) : null}
      </Modal>
    </div>
  )
}
