import React, { useEffect, useMemo, useState } from 'react'
import { CalendarDays, FileText, RefreshCw, Save, Sparkles } from 'lucide-react'
import toast from 'react-hot-toast'
import { useProject } from '../context/ProjectContext'
import { reportsApi } from '../services/api'

const REPORT_TYPES = [
  { id: 'daily', label: 'Daily' },
  { id: 'weekly', label: 'Weekly' },
  { id: 'custom', label: 'Custom' },
]

const SECTION_OPTIONS = [
  { id: 'summary', label: 'Summary' },
  { id: 'personnel', label: 'Personnel' },
  { id: 'activities', label: 'Activities' },
  { id: 'upcoming', label: 'Upcoming' },
  { id: 'safety', label: 'Safety' },
  { id: 'checklists', label: 'Checklists' },
  { id: 'issues', label: 'Issues' },
  { id: 'tests', label: 'Tests' },
  { id: 'equipment', label: 'Equipment' },
  { id: 'commercials', label: 'Commercials' },
]

function StatCard({ label, value, sub, color }) {
  return (
    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 14, padding: '16px 18px' }}>
      <div style={{ fontSize: 10, fontWeight: 700, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.08em' }}>{label}</div>
      <div style={{ fontSize: 28, fontWeight: 800, color: color || 'var(--text-primary)', lineHeight: 1.1, marginTop: 8 }}>{value}</div>
      <div style={{ fontSize: 12, color: '#64748b', marginTop: 6 }}>{sub}</div>
    </div>
  )
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
  return {
    from: monday.toISOString().slice(0, 10),
    to: sunday.toISOString().slice(0, 10),
  }
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

function downloadBlob(blob, fileName) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.click()
  URL.revokeObjectURL(url)
}

function SectionToggle({ label, active, onClick }) {
  return (
    <button
      onClick={onClick}
      style={{
        padding: '9px 12px',
        borderRadius: 10,
        border: `1px solid ${active ? 'rgba(37,99,235,0.4)' : 'var(--border)'}`,
        background: active ? 'rgba(37,99,235,0.12)' : 'var(--bg-base)',
        color: active ? '#bfdbfe' : '#94a3b8',
        fontSize: 12,
        fontWeight: 700,
        cursor: 'pointer',
      }}
    >
      {label}
    </button>
  )
}

function OptionChip({ label, active, onClick }) {
  return (
    <button
      onClick={onClick}
      style={{
        padding: '7px 10px',
        borderRadius: 999,
        border: `1px solid ${active ? 'rgba(14,165,233,0.35)' : 'var(--border)'}`,
        background: active ? 'rgba(14,165,233,0.12)' : 'var(--bg-base)',
        color: active ? '#7dd3fc' : '#94a3b8',
        fontSize: 11,
        fontWeight: 700,
        cursor: 'pointer',
      }}
    >
      {label}
    </button>
  )
}

function CountPills({ values, tone = 'blue' }) {
  if (!values || Object.keys(values).length === 0) return null

  const tones = {
    blue: { background: 'rgba(14,165,233,0.12)', border: '1px solid rgba(14,165,233,0.2)', color: '#7dd3fc' },
    green: { background: 'rgba(34,197,94,0.12)', border: '1px solid rgba(34,197,94,0.2)', color: '#86efac' },
    amber: { background: 'rgba(250,204,21,0.12)', border: '1px solid rgba(250,204,21,0.2)', color: '#fde68a' },
    rose: { background: 'rgba(248,113,113,0.12)', border: '1px solid rgba(248,113,113,0.2)', color: '#fca5a5' },
    violet: { background: 'rgba(167,139,250,0.12)', border: '1px solid rgba(167,139,250,0.2)', color: '#c4b5fd' },
  }

  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 10 }}>
      {Object.entries(values).map(([label, count]) => (
        <span key={label} style={{ padding: '6px 10px', borderRadius: 999, ...(tones[tone] || tones.blue), fontSize: 11, fontWeight: 700 }}>
          {label}: {count}
        </span>
      ))}
    </div>
  )
}

function BreakdownChart({ title, items }) {
  if (!Array.isArray(items) || items.length === 0) return null

  return (
    <div style={{ display: 'grid', gap: 10, marginBottom: 12 }}>
      <div style={{ fontSize: 12, fontWeight: 800, color: '#e2e8f0' }}>{title}</div>
      {items.slice(0, 8).map(item => {
        const total = Number(item.total || 0)
        const open = Number(item.open || 0)
        const closed = Number(item.closed || 0)
        const openWidth = total > 0 ? `${(open / total) * 100}%` : '0%'
        const closedWidth = total > 0 ? `${(closed / total) * 100}%` : '0%'

        return (
          <div key={item.category} style={{ padding: '12px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 10, marginBottom: 6 }}>
              <div style={{ fontSize: 12, fontWeight: 800, color: 'var(--text-primary)' }}>{item.category}</div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#94a3b8' }}>{total} total</div>
            </div>
            <div style={{ fontSize: 11, color: '#94a3b8', marginBottom: 8 }}>Open {open} / Closed {closed}</div>
            <div style={{ height: 10, width: '100%', borderRadius: 999, background: 'rgba(148,163,184,0.16)', overflow: 'hidden', display: 'flex' }}>
              <div style={{ width: openWidth, background: '#f97316' }} />
              <div style={{ width: closedWidth, background: '#22c55e' }} />
            </div>
          </div>
        )
      })}
    </div>
  )
}

function DataTable({ rows, maxRows }) {
  if (!Array.isArray(rows) || rows.length === 0) return null

  const headers = Object.keys(rows[0] || {})
  const visibleRows = rows.slice(0, maxRows)

  return (
    <div style={{ overflowX: 'auto', border: '1px solid var(--border)', borderRadius: 12 }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 720 }}>
        <thead>
          <tr style={{ background: 'rgba(148,163,184,0.08)' }}>
            {headers.map(header => (
              <th key={header} style={{ padding: '10px 12px', textAlign: 'left', fontSize: 10, letterSpacing: '0.08em', textTransform: 'uppercase', color: '#94a3b8', borderBottom: '1px solid var(--border)' }}>
                {header.replace(/([a-z])([A-Z])/g, '$1 $2')}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {visibleRows.map((row, index) => (
            <tr key={index}>
              {headers.map(header => (
                <td key={header} style={{ padding: '10px 12px', fontSize: 12, color: '#cbd5e1', borderBottom: index === visibleRows.length - 1 ? 'none' : '1px solid rgba(148,163,184,0.08)' }}>
                  {String(row[header] || '-')}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function SectionPreview({ section, data, reportType }) {
  const sectionTitle = SECTION_OPTIONS.find(item => item.id === section)?.label || section
  const rowPreviewLimit = reportType === 'custom' ? 18 : 6

  return (
    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 14, padding: 16 }}>
      <div style={{ fontSize: 14, fontWeight: 800, color: 'var(--text-primary)', marginBottom: 10 }}>{sectionTitle}</div>
      {data?.text ? (
        <div style={{ fontSize: 13, color: '#cbd5e1', whiteSpace: 'pre-wrap', lineHeight: 1.6, marginBottom: 10 }}>{data.text}</div>
      ) : null}
      <CountPills values={data?.byCompany} tone="green" />
      <CountPills values={data?.byStatus} tone="rose" />
      <CountPills values={data?.byTag} tone="amber" />
      <CountPills values={data?.byPriority} tone="rose" />
      <CountPills values={data?.byType} tone="violet" />
      <CountPills values={data?.topLocations} tone="blue" />
      <BreakdownChart
        title={section === 'checklists' ? 'Checklist Open vs Closed by Tag Level' : section === 'issues' ? 'Issue Open vs Closed by Priority' : 'Open vs Closed by Category'}
        items={data?.progressByCategory}
      />
      {data?.totalTests !== undefined ? (
        <div style={{ fontSize: 12, color: '#38bdf8', fontWeight: 700, marginBottom: 10 }}>Total tests: {data.totalTests}</div>
      ) : null}
      {Array.isArray(data?.rows) && data.rows.length > 0 ? (
        <>
          {Number.isFinite(data?.totalRows) ? (
            <div style={{ fontSize: 11, color: '#64748b', marginBottom: 8 }}>
              Showing {Math.min(data.rows.length, rowPreviewLimit)} of {data.totalRows} rows saved in this report snapshot.
            </div>
          ) : null}
          <DataTable rows={data.rows} maxRows={rowPreviewLimit} />
        </>
      ) : null}
    </div>
  )
}

export default function ReportsPage() {
  const { projects, activeProject, setActiveProject } = useProject()
  const [reportType, setReportType] = useState('weekly')
  const [dateFrom, setDateFrom] = useState(weeklyRange().from)
  const [dateTo, setDateTo] = useState(weeklyRange().to)
  const [title, setTitle] = useState('')
  const [selectedSections, setSelectedSections] = useState(SECTION_OPTIONS.map(section => section.id))
  const [issueStatuses, setIssueStatuses] = useState([])
  const [checklistStatuses, setChecklistStatuses] = useState([])
  const [equipmentTypes, setEquipmentTypes] = useState([])
  const [summaryText, setSummaryText] = useState('')
  const [safetyNotes, setSafetyNotes] = useState('')
  const [commercialNotes, setCommercialNotes] = useState('')
  const [projectDescription, setProjectDescription] = useState('')
  const [clientName, setClientName] = useState('')
  const [projectCode, setProjectCode] = useState('')
  const [shiftWindow, setShiftWindow] = useState('')
  const [reportAuthor, setReportAuthor] = useState('')
  const [peopleOnSite, setPeopleOnSite] = useState('')
  const [options, setOptions] = useState({ issueStatuses: [], checklistStatuses: [], equipmentTypes: [] })
  const [savedReports, setSavedReports] = useState([])
  const [previewReport, setPreviewReport] = useState(null)
  const [loading, setLoading] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [downloadingId, setDownloadingId] = useState(null)

  useEffect(() => {
    if (reportType === 'daily') {
      const today = todayIso()
      setDateFrom(today)
      setDateTo(today)
    } else if (reportType === 'weekly') {
      const range = weeklyRange()
      setDateFrom(range.from)
      setDateTo(range.to)
    }
  }, [reportType])

  useEffect(() => {
    if (!activeProject?.externalId) return
    let ignore = false

    async function loadData() {
      setLoading(true)
      try {
        const [optionsRes, reportsRes] = await Promise.all([
          reportsApi.getOptions(activeProject.externalId),
          reportsApi.getAll(activeProject.externalId),
        ])
        if (ignore) return
        setOptions(optionsRes.data.data || { issueStatuses: [], checklistStatuses: [], equipmentTypes: [] })
        const reports = reportsRes.data.data || []
        setSavedReports(reports)
        if (!previewReport && reports.length > 0) {
          setPreviewReport(reports[0])
        }
      } catch (err) {
        if (!ignore) {
          toast.error(err.response?.data?.message || 'Failed to load report data')
        }
      } finally {
        if (!ignore) setLoading(false)
      }
    }

    loadData()
    return () => { ignore = true }
  }, [activeProject?.externalId])

  const summary = previewReport?.reportData?.summary || {}
  const sectionData = previewReport?.reportData?.sectionData || {}
  const selectedProject = activeProject

  const reportSubtitle = useMemo(() => {
    if (!selectedProject) return 'Select a project to generate a saved report'
    return `${selectedProject.name} • ${reportType} report • ${dateFrom} to ${dateTo}`
  }, [selectedProject, reportType, dateFrom, dateTo])

  function toggleArrayValue(value, current, setter) {
    setter(current.includes(value) ? current.filter(item => item !== value) : [...current, value])
  }

  function toggleSection(sectionId) {
    setSelectedSections(prev => (
      prev.includes(sectionId)
        ? prev.filter(item => item !== sectionId)
        : [...prev, sectionId]
    ))
  }

  async function handleGenerate() {
    if (!selectedProject?.externalId) {
      return toast.error('Select a project first')
    }
    setGenerating(true)
    try {
      const res = await reportsApi.generate({
        projectId: selectedProject.externalId,
        title,
        reportType,
        dateFrom,
        dateTo,
        sections: selectedSections,
        issueStatuses,
        checklistStatuses,
        equipmentTypes,
        summaryText,
        safetyNotes,
        commercialNotes,
        projectDescription,
        clientName,
        projectCode,
        shiftWindow,
        reportAuthor,
        peopleOnSite,
      })
      const report = res.data.data
      setPreviewReport(report)
      setSavedReports(prev => [report, ...prev.filter(item => item.id !== report.id)])
      toast.success('Report generated and saved')
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to generate report')
    } finally {
      setGenerating(false)
    }
  }

  async function handleOpen(reportId) {
    try {
      const res = await reportsApi.getById(reportId)
      setPreviewReport(res.data.data)
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to open saved report')
    }
  }

  async function handleDownload(report, format) {
    setDownloadingId(`${report.id}-${format}`)
    try {
      const res = await reportsApi.download(report.id, format)
      const extension = format === 'csv' ? 'csv' : format === 'pdf' ? 'pdf' : 'json'
      const fileName = `${(report.title || 'saved-report').replace(/[^a-z0-9]+/gi, '-').replace(/(^-|-$)/g, '') || 'saved-report'}.${extension}`
      downloadBlob(res.data, fileName)
      toast.success(`${format.toUpperCase()} download started`)
    } catch (err) {
      toast.error(err.response?.data?.message || 'Download failed')
    } finally {
      setDownloadingId(null)
    }
  }

  if (!projects.length) {
    return (
      <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 16, padding: '48px 32px', textAlign: 'center' }}>
        <FileText size={34} style={{ color: '#64748b', marginBottom: 14 }} />
        <div style={{ fontSize: 18, fontWeight: 800, color: 'var(--text-primary)' }}>No projects available</div>
        <div style={{ fontSize: 13, color: '#64748b', marginTop: 8 }}>Load a project first to generate a saved report from the synced database.</div>
      </div>
    )
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
        <div>
          <h2 style={{ fontSize: 22, fontWeight: 800, color: 'var(--text-primary)', margin: 0 }}>Reports</h2>
          <p style={{ fontSize: 13, color: '#64748b', marginTop: 4 }}>
            Generate daily, weekly, or custom reports from your synced DB, save them once, and download them again later without rebuilding.
          </p>
        </div>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '10px 12px', borderRadius: 12, background: 'rgba(37,99,235,0.08)', border: '1px solid rgba(37,99,235,0.22)', color: '#93c5fd', fontSize: 12, fontWeight: 700 }}>
          <CalendarDays size={14} />
          {reportSubtitle}
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1.15fr 0.85fr', gap: 16, alignItems: 'start' }}>
        <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 16, padding: 18, display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr auto', gap: 12, alignItems: 'end' }}>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 6 }}>Project</div>
              <select value={selectedProject?.id || ''} onChange={e => setActiveProject(projects.find(project => String(project.id) === e.target.value) || null)} style={{ width: '100%', padding: '10px 12px', borderRadius: 10, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12 }}>
                {projects.map(project => (
                  <option key={project.id} value={project.id}>
                    {project.name} ({project.externalId})
                  </option>
                ))}
              </select>
            </div>
            <button onClick={handleGenerate} disabled={generating || !selectedProject} style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '11px 16px', borderRadius: 10, border: 'none', background: '#2563eb', color: '#fff', fontSize: 12, fontWeight: 700, cursor: 'pointer', opacity: generating ? 0.7 : 1 }}>
              {generating ? <RefreshCw size={14} style={{ animation: 'spin 1s linear infinite' }} /> : <Save size={14} />}
              {generating ? 'Generating...' : 'Generate & Save'}
            </button>
          </div>

          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {REPORT_TYPES.map(type => (
              <button
                key={type.id}
                onClick={() => setReportType(type.id)}
                style={{
                  padding: '10px 14px',
                  borderRadius: 10,
                  border: `1px solid ${reportType === type.id ? 'rgba(37,99,235,0.4)' : 'var(--border)'}`,
                  background: reportType === type.id ? 'rgba(37,99,235,0.12)' : 'var(--bg-base)',
                  color: reportType === type.id ? '#bfdbfe' : '#94a3b8',
                  fontSize: 12,
                  fontWeight: 700,
                  cursor: 'pointer',
                }}
              >
                {type.label}
              </button>
            ))}
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12 }}>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 6 }}>Report Title</div>
              <input value={title} onChange={e => setTitle(e.target.value)} placeholder="Optional custom title" style={{ width: '100%', padding: '10px 12px', borderRadius: 10, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, outline: 'none' }} />
            </div>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 6 }}>From</div>
              <input type="date" value={dateFrom} onChange={e => setDateFrom(e.target.value)} disabled={reportType !== 'custom'} style={{ width: '100%', padding: '10px 12px', borderRadius: 10, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, outline: 'none', opacity: reportType === 'custom' ? 1 : 0.7 }} />
            </div>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 6 }}>To</div>
              <input type="date" value={dateTo} onChange={e => setDateTo(e.target.value)} disabled={reportType !== 'custom'} style={{ width: '100%', padding: '10px 12px', borderRadius: 10, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, outline: 'none', opacity: reportType === 'custom' ? 1 : 0.7 }} />
            </div>
          </div>

          <div>
            <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 8 }}>Report Sections</div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              {SECTION_OPTIONS.map(section => (
                <SectionToggle
                  key={section.id}
                  label={section.label}
                  active={selectedSections.includes(section.id)}
                  onClick={() => toggleSection(section.id)}
                />
              ))}
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 14 }}>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 8 }}>Issue Status Filter</div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                {options.issueStatuses.map(status => (
                  <OptionChip key={status} label={status} active={issueStatuses.includes(status.toLowerCase())} onClick={() => toggleArrayValue(status.toLowerCase(), issueStatuses, setIssueStatuses)} />
                ))}
              </div>
            </div>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 8 }}>Checklist Status Filter</div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                {options.checklistStatuses.map(status => (
                  <OptionChip key={status} label={status} active={checklistStatuses.includes(status.toLowerCase())} onClick={() => toggleArrayValue(status.toLowerCase(), checklistStatuses, setChecklistStatuses)} />
                ))}
              </div>
            </div>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 8 }}>Equipment Type Filter</div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                {options.equipmentTypes.map(type => (
                  <OptionChip key={type} label={type} active={equipmentTypes.includes(type.toLowerCase())} onClick={() => toggleArrayValue(type.toLowerCase(), equipmentTypes, setEquipmentTypes)} />
                ))}
              </div>
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 12 }}>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 6 }}>Project Description</div>
              <input value={projectDescription} onChange={e => setProjectDescription(e.target.value)} placeholder="Daily / weekly report title" style={{ width: '100%', padding: '10px 12px', borderRadius: 10, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, outline: 'none' }} />
            </div>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 6 }}>Client</div>
              <input value={clientName} onChange={e => setClientName(e.target.value)} placeholder="Client / tenant" style={{ width: '100%', padding: '10px 12px', borderRadius: 10, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, outline: 'none' }} />
            </div>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 6 }}>Project Code</div>
              <input value={projectCode} onChange={e => setProjectCode(e.target.value)} placeholder="Project ID / code" style={{ width: '100%', padding: '10px 12px', borderRadius: 10, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, outline: 'none' }} />
            </div>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 6 }}>Shift Window</div>
              <input value={shiftWindow} onChange={e => setShiftWindow(e.target.value)} placeholder="7:30 AM - 5:30 PM" style={{ width: '100%', padding: '10px 12px', borderRadius: 10, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, outline: 'none' }} />
            </div>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 6 }}>Author</div>
              <input value={reportAuthor} onChange={e => setReportAuthor(e.target.value)} placeholder="Report author" style={{ width: '100%', padding: '10px 12px', borderRadius: 10, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, outline: 'none' }} />
            </div>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 6 }}>Location</div>
              <input value={selectedProject?.location || ''} readOnly style={{ width: '100%', padding: '10px 12px', borderRadius: 10, background: 'rgba(255,255,255,0.04)', border: '1px solid var(--border)', color: '#94a3b8', fontSize: 12, outline: 'none' }} />
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: 12 }}>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 6 }}>People On Site Detail</div>
              <textarea value={peopleOnSite} onChange={e => setPeopleOnSite(e.target.value)} placeholder={'One person per line, for example:\nAli Mehdi Syed | CxE | Onsite | L2-L5 | 1'} style={{ width: '100%', minHeight: 96, padding: '12px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, resize: 'vertical', outline: 'none' }} />
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 12 }}>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 6 }}>Executive Summary</div>
              <textarea value={summaryText} onChange={e => setSummaryText(e.target.value)} placeholder="Add manual summary notes" style={{ width: '100%', minHeight: 110, padding: '12px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, resize: 'vertical', outline: 'none' }} />
            </div>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 6 }}>Safety Notes</div>
              <textarea value={safetyNotes} onChange={e => setSafetyNotes(e.target.value)} placeholder="Add safety observations" style={{ width: '100%', minHeight: 110, padding: '12px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, resize: 'vertical', outline: 'none' }} />
            </div>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 6 }}>Commercial Notes</div>
              <textarea value={commercialNotes} onChange={e => setCommercialNotes(e.target.value)} placeholder="Add commercial / risk notes" style={{ width: '100%', minHeight: 110, padding: '12px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, resize: 'vertical', outline: 'none' }} />
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 16, padding: 18 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
              <Sparkles size={16} style={{ color: '#60a5fa' }} />
              <div style={{ fontSize: 15, fontWeight: 800, color: 'var(--text-primary)' }}>Preview & Export</div>
            </div>
            <div style={{ fontSize: 12, color: '#64748b', lineHeight: 1.6 }}>
              Generate a report once and it is stored in the database. You can reopen the saved version anytime and download it again as PDF, JSON, or CSV.
            </div>
          </div>

          <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 16, padding: 18 }}>
            <div style={{ fontSize: 14, fontWeight: 800, color: 'var(--text-primary)', marginBottom: 10 }}>Saved Reports</div>
            {loading ? (
              <div style={{ fontSize: 12, color: '#64748b' }}>Loading saved reports...</div>
            ) : savedReports.length === 0 ? (
              <div style={{ fontSize: 12, color: '#64748b' }}>No saved reports yet for this project.</div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10, maxHeight: 420, overflowY: 'auto' }}>
                {savedReports.map(report => (
                  <div key={report.id} style={{ padding: '12px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)' }}>
                    <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)' }}>{report.title}</div>
                    <div style={{ fontSize: 11, color: '#64748b', marginTop: 4 }}>{report.subtitle}</div>
                    <div style={{ fontSize: 11, color: '#64748b', marginTop: 4 }}>Saved {formatDate(report.generatedAt)}</div>
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 10 }}>
                      <button onClick={() => handleOpen(report.id)} style={{ padding: '8px 10px', borderRadius: 9, border: '1px solid var(--border)', background: 'var(--bg-card)', color: '#cbd5e1', fontSize: 11, fontWeight: 700, cursor: 'pointer' }}>
                        Open
                      </button>
                      <button onClick={() => handleDownload(report, 'csv')} disabled={downloadingId === `${report.id}-csv`} style={{ padding: '8px 10px', borderRadius: 9, border: '1px solid rgba(34,197,94,0.2)', background: 'rgba(34,197,94,0.08)', color: '#86efac', fontSize: 11, fontWeight: 700, cursor: 'pointer' }}>
                        {downloadingId === `${report.id}-csv` ? 'Downloading...' : 'Download CSV'}
                      </button>
                      <button onClick={() => handleDownload(report, 'json')} disabled={downloadingId === `${report.id}-json`} style={{ padding: '8px 10px', borderRadius: 9, border: '1px solid rgba(14,165,233,0.2)', background: 'rgba(14,165,233,0.08)', color: '#7dd3fc', fontSize: 11, fontWeight: 700, cursor: 'pointer' }}>
                        {downloadingId === `${report.id}-json` ? 'Downloading...' : 'Download JSON'}
                      </button>
                      <button onClick={() => handleDownload(report, 'pdf')} disabled={downloadingId === `${report.id}-pdf`} style={{ padding: '8px 10px', borderRadius: 9, border: '1px solid rgba(239,68,68,0.22)', background: 'rgba(239,68,68,0.08)', color: '#fda4af', fontSize: 11, fontWeight: 700, cursor: 'pointer' }}>
                        {downloadingId === `${report.id}-pdf` ? 'Downloading...' : 'Download PDF'}
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      {previewReport ? (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', gap: 14 }}>
            <StatCard label="Checklists" value={summary.checklists?.total ?? 0} sub={`${summary.checklists?.closed ?? 0} closed / ${summary.checklists?.open ?? 0} open`} color="#22c55e" />
            <StatCard label="Issues" value={summary.issues?.total ?? 0} sub={`${summary.issues?.closed ?? 0} closed / ${summary.issues?.open ?? 0} open`} color="#ef4444" />
            <StatCard label="Tasks" value={summary.tasks?.total ?? 0} sub={`${summary.tasks?.closed ?? 0} closed / ${summary.tasks?.open ?? 0} open`} color="#60a5fa" />
            <StatCard label="Equipment" value={summary.equipment?.total ?? 0} sub={`${summary.equipment?.tests ?? 0} tests in scope`} color="#a78bfa" />
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: previewReport.reportType === 'custom' ? '1fr' : 'repeat(2, minmax(0, 1fr))', gap: 16 }}>
            {(previewReport.sections || []).map(section => (
              <SectionPreview key={section} section={section} data={sectionData?.[section]} reportType={previewReport.reportType} />
            ))}
          </div>
        </>
      ) : (
        <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 16, padding: '42px 32px', textAlign: 'center' }}>
          <FileText size={30} style={{ color: '#64748b', marginBottom: 12 }} />
          <div style={{ fontSize: 16, fontWeight: 800, color: 'var(--text-primary)' }}>No preview yet</div>
          <div style={{ fontSize: 12, color: '#64748b', marginTop: 8 }}>Generate a report or open a saved one to review its saved snapshot and download it again.</div>
        </div>
      )}
    </div>
  )
}
