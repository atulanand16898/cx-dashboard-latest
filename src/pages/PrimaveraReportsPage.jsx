import React, { useEffect, useMemo, useState } from 'react'
import { ClipboardCheck, Database, FileText, FileUp, Loader2, Plus, Settings2, TrendingUp } from 'lucide-react'
import toast from 'react-hot-toast'
import {
  CartesianGrid,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { Modal } from '../components/ui'
import { useProject } from '../context/ProjectContext'
import { xerProcessingApi } from '../services/api'

const BASELINE_PROGRESS_TABLES = [
  { key: 'TASK', summaryKey: 'activities', label: 'Activities' },
  { key: 'TASKRSRC', summaryKey: 'taskResources', label: 'Task Resources' },
  { key: 'RSRC', summaryKey: 'resources', label: 'Resources' },
  { key: 'CALENDAR', summaryKey: 'calendars', label: 'Calendars' },
  { key: 'ACTVCODE', summaryKey: 'activityCodes', label: 'Activity Codes' },
  { key: 'TASKACTV', summaryKey: 'taskActivityCodes', label: 'Task Activity Codes' },
  { key: 'TASKPRED', summaryKey: 'taskPredecessors', label: 'Predecessors' },
]

const STEP3_ACTIONS = [
  {
    key: 'analyze_baseline',
    label: 'Analyze Baseline',
    description: 'Open the baseline analysis view with the selected data date, first KPIs, and planned S-curves.',
    icon: TrendingUp,
    accent: '#60a5fa',
  },
  {
    key: 'dcma_14',
    label: '14 DCMA Checkpoints',
    description: 'Run the DCMA 14-point schedule quality assessment against the processed baseline.',
    icon: ClipboardCheck,
    accent: '#f59e0b',
  },
  {
    key: 'project_report',
    label: 'Prepare Project Report',
    description: 'Turn the analyzed baseline into a project-report flow after the KPI and S-curve layer is in place.',
    icon: FileText,
    accent: '#34d399',
    status: 'Next',
  },
]

function Card({ children, style }) {
  return (
    <div
      style={{
        background: 'var(--bg-card)',
        border: '1px solid var(--border)',
        borderRadius: 18,
        padding: 20,
        ...style,
      }}
    >
      {children}
    </div>
  )
}

function Label({ children }) {
  return (
    <div style={{ fontSize: 11, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.1em', marginBottom: 8 }}>
      {children}
    </div>
  )
}

function Input({ style, ...props }) {
  return (
    <input
      {...props}
      style={{
        width: '100%',
        padding: '12px 14px',
        borderRadius: 14,
        background: 'var(--bg-base)',
        border: '1px solid var(--border)',
        color: 'var(--text-primary)',
        fontSize: 13,
        ...style,
      }}
    />
  )
}

function ChoicePill({ label, selected, onClick, disabled = false }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '9px 12px',
        borderRadius: 999,
        border: selected ? '1px solid rgba(37,99,235,0.55)' : '1px solid var(--border)',
        background: selected ? 'rgba(37,99,235,0.18)' : 'var(--bg-base)',
        color: selected ? '#bfdbfe' : 'var(--text-primary)',
        fontSize: 13,
        fontWeight: selected ? 800 : 600,
        cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.6 : 1,
      }}
    >
      {label}
    </button>
  )
}

function TextArea({ style, ...props }) {
  return (
    <textarea
      {...props}
      style={{
        width: '100%',
        minHeight: 96,
        padding: '12px 14px',
        borderRadius: 14,
        background: 'var(--bg-base)',
        border: '1px solid var(--border)',
        color: 'var(--text-primary)',
        fontSize: 13,
        resize: 'vertical',
        ...style,
      }}
    />
  )
}

function PrimaryButton({ children, icon: Icon, style, disabled, ...props }) {
  return (
    <button
      {...props}
      disabled={disabled}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 8,
        padding: '12px 16px',
        borderRadius: 14,
        border: '1px solid #2563eb',
        background: disabled ? 'rgba(37,99,235,0.45)' : '#2563eb',
        color: '#fff',
        fontSize: 13,
        fontWeight: 800,
        cursor: disabled ? 'not-allowed' : 'pointer',
        ...style,
      }}
    >
      {Icon ? <Icon size={15} /> : null}
      {children}
    </button>
  )
}

function SecondaryButton({ children, icon: Icon, style, disabled, ...props }) {
  return (
    <button
      {...props}
      disabled={disabled}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 8,
        padding: '12px 16px',
        borderRadius: 14,
        border: '1px solid var(--border)',
        background: 'var(--bg-base)',
        color: 'var(--text-primary)',
        fontSize: 13,
        fontWeight: 800,
        cursor: disabled ? 'not-allowed' : 'pointer',
        opacity: disabled ? 0.6 : 1,
        ...style,
      }}
    >
      {Icon ? <Icon size={15} /> : null}
      {children}
    </button>
  )
}

function StepActionCard({ title, description, status, icon: Icon, accent, active, onClick }) {
  return (
    <button
      type="button"
      onClick={onClick}
      style={{
        textAlign: 'left',
        width: '100%',
        padding: 18,
        borderRadius: 18,
        border: active ? `1px solid ${accent}` : '1px solid var(--border)',
        background: active ? `linear-gradient(180deg, ${accent}14 0%, rgba(15,23,42,0.18) 100%)` : 'var(--bg-card)',
        cursor: 'pointer',
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'flex-start' }}>
        <div style={{ display: 'flex', gap: 12 }}>
          <div
            style={{
              width: 40,
              height: 40,
              borderRadius: 14,
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              background: `${accent}20`,
              color: accent,
              flexShrink: 0,
            }}
          >
            <Icon size={18} />
          </div>
          <div>
            <div style={{ fontSize: 15, fontWeight: 800, color: 'var(--text-primary)' }}>{title}</div>
            <div style={{ fontSize: 12, color: '#64748b', marginTop: 6, lineHeight: 1.6 }}>{description}</div>
          </div>
        </div>
        {status ? <StatusPill value={status} /> : null}
      </div>
    </button>
  )
}

function MetricTile({ label, value, sub }) {
  return (
    <div style={{ padding: 16, borderRadius: 16, background: 'var(--bg-base)', border: '1px solid var(--border)' }}>
      <div style={{ fontSize: 10, fontWeight: 800, color: '#64748b', letterSpacing: '0.08em', textTransform: 'uppercase' }}>{label}</div>
      <div style={{ fontSize: 28, fontWeight: 800, color: 'var(--text-primary)', marginTop: 10, lineHeight: 1.1 }}>{value}</div>
      <div style={{ fontSize: 12, color: '#64748b', marginTop: 8, lineHeight: 1.6 }}>{sub}</div>
    </div>
  )
}

function StatusPill({ value }) {
  const normalized = String(value || 'pending').toLowerCase()
  const palette = {
    completed: { color: '#86efac', border: 'rgba(34,197,94,0.24)', background: 'rgba(34,197,94,0.10)' },
    configured: { color: '#7dd3fc', border: 'rgba(14,165,233,0.24)', background: 'rgba(14,165,233,0.10)' },
    uploading: { color: '#7dd3fc', border: 'rgba(14,165,233,0.24)', background: 'rgba(14,165,233,0.10)' },
    detected: { color: '#93c5fd', border: 'rgba(96,165,250,0.24)', background: 'rgba(96,165,250,0.10)' },
    processing: { color: '#fde68a', border: 'rgba(250,204,21,0.24)', background: 'rgba(250,204,21,0.10)' },
    persisting: { color: '#fde68a', border: 'rgba(250,204,21,0.24)', background: 'rgba(250,204,21,0.10)' },
    failed: { color: '#fca5a5', border: 'rgba(248,113,113,0.24)', background: 'rgba(248,113,113,0.10)' },
    skipped: { color: '#cbd5e1', border: 'rgba(148,163,184,0.22)', background: 'rgba(148,163,184,0.08)' },
    pending: { color: '#cbd5e1', border: 'rgba(148,163,184,0.22)', background: 'rgba(148,163,184,0.08)' },
  }[normalized] || { color: '#cbd5e1', border: 'rgba(148,163,184,0.22)', background: 'rgba(148,163,184,0.08)' }

  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: '6px 10px',
        borderRadius: 999,
        fontSize: 11,
        fontWeight: 800,
        color: palette.color,
        border: `1px solid ${palette.border}`,
        background: palette.background,
        textTransform: 'capitalize',
      }}
    >
      {normalized.replaceAll('_', ' ')}
    </span>
  )
}

function formatDateTime(value) {
  if (!value) return 'Not available yet'
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

function formatCount(value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '0'
  return new Intl.NumberFormat('en-US').format(Number(value))
}

function formatPercent(value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '0%'
  return `${Number(value).toFixed(1)}%`
}

function formatCurrency(value) {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '$0'
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    notation: Math.abs(Number(value)) >= 1000000 ? 'compact' : 'standard',
    maximumFractionDigits: 1,
  }).format(Number(value))
}

function parseProgressJson(progressJson) {
  if (!progressJson) return null
  if (typeof progressJson === 'object') return progressJson
  try {
    return JSON.parse(progressJson)
  } catch {
    return null
  }
}

function parseJsonArray(value, fallbackValue = '') {
  if (Array.isArray(value)) return value.filter(Boolean)
  if (typeof value === 'string' && value.trim()) {
    try {
      const parsed = JSON.parse(value)
      if (Array.isArray(parsed)) {
        return parsed.filter(Boolean)
      }
    } catch {
      if (fallbackValue) {
        return String(fallbackValue).includes(' selected') || fallbackValue === 'ALL' ? [] : [fallbackValue]
      }
      return []
    }
  }

  if (fallbackValue && !String(fallbackValue).includes(' selected') && fallbackValue !== 'ALL') {
    return [fallbackValue]
  }
  return []
}

function uniqueResourceNames(resources, selectedTypes) {
  if (!Array.isArray(resources) || resources.length === 0) return []
  const normalizedTypes = Array.isArray(selectedTypes) ? selectedTypes : []
  const filtered = normalizedTypes.length
    ? resources.filter((item) => normalizedTypes.includes(item.resourceType))
    : resources

  const seen = new Set()
  return filtered.filter((item) => {
    if (!item?.resourceName || seen.has(item.resourceName)) {
      return false
    }
    seen.add(item.resourceName)
    return true
  })
}

const DCMA_DESCRIPTIONS = {
  1:  'Activities missing a predecessor or successor relationship.',
  2:  'Relationships with negative lag (leads compress the schedule).',
  3:  'Relationships with positive lag (lags delay successors artificially).',
  4:  'Start-to-Finish (SF) relationships, which are rarely valid.',
  5:  'Activities with hard constraints (Must Start On, Must Finish On, Mandatory).',
  6:  'Incomplete activities with total float exceeding 44 working days.',
  7:  'Activities with negative total float (behind schedule).',
  8:  'Activities with baseline duration exceeding 44 working days.',
  9:  'Activities with dates outside the project start–finish range.',
  10: 'Non-milestone activities with no resource assignment.',
  11: 'Activities missing a baseline start or finish date.',
  12: 'Critical Path Length Index — measures schedule efficiency (threshold ≥ 1.0).',
  13: 'Baseline Execution Index — ratio of completed vs planned activities at data date.',
  14: 'Verification that a continuous critical path exists from start to finish.',
}

function DcmaPanel({ dcma, loading, expanded, setExpanded, baselineComplete }) {
  if (!baselineComplete) {
    return (
      <div style={{ padding: 18, borderRadius: 16, background: 'var(--bg-base)', border: '1px solid var(--border)', color: '#94a3b8', fontSize: 13 }}>
        Complete a baseline import first to run the DCMA checkpoints.
      </div>
    )
  }

  if (loading) {
    return (
      <div style={{ padding: 18, borderRadius: 16, background: 'var(--bg-base)', border: '1px solid var(--border)', color: '#94a3b8', fontSize: 13, display: 'flex', alignItems: 'center', gap: 8 }}>
        <Loader2 size={15} style={{ animation: 'spin 1s linear infinite' }} />
        Loading DCMA checkpoints…
      </div>
    )
  }

  if (!dcma) {
    return (
      <div style={{ padding: 18, borderRadius: 16, background: 'var(--bg-base)', border: '1px solid var(--border)', color: '#94a3b8', fontSize: 13, lineHeight: 1.7 }}>
        DCMA checkpoints are computed automatically when a baseline is processed. Re-upload the XER file to generate them.
      </div>
    )
  }

  const overall = dcma.overallStatus === 'PASS'
  const checkpoints = Array.isArray(dcma.checkpoints) ? dcma.checkpoints : []

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* Summary bar */}
      <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
        <div style={{
          display: 'inline-flex', alignItems: 'center', gap: 8, padding: '8px 14px',
          borderRadius: 999, fontSize: 13, fontWeight: 800,
          background: overall ? 'rgba(34,197,94,0.12)' : 'rgba(248,113,113,0.12)',
          border: `1px solid ${overall ? 'rgba(34,197,94,0.3)' : 'rgba(248,113,113,0.3)'}`,
          color: overall ? '#86efac' : '#fca5a5',
        }}>
          <ClipboardCheck size={14} />
          {overall ? 'OVERALL PASS' : 'OVERALL FAIL'}
        </div>
        <div style={{ fontSize: 13, color: '#94a3b8' }}>
          <span style={{ color: '#86efac', fontWeight: 800 }}>{dcma.passCount}</span> passing &nbsp;·&nbsp;
          <span style={{ color: '#fca5a5', fontWeight: 800 }}>{dcma.failCount}</span> failing
        </div>
      </div>

      {/* Checkpoint grid */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))', gap: 8 }}>
        {checkpoints.map((cp) => {
          const pass = cp.status === 'PASS'
          const isSpecial = cp.checkpointId === 12 || cp.checkpointId === 13 || cp.checkpointId === 14
          const scoreLabel = isSpecial
            ? (cp.checkpointId === 14 ? (pass ? 'PASS' : 'FAIL') : `${(cp.score / 100).toFixed(3)}`)
            : `${cp.score?.toFixed(1)}%`
          const isOpen = expanded === cp.checkpointId
          const exceptions = Array.isArray(cp.exceptions) ? cp.exceptions : []

          return (
            <div
              key={cp.checkpointId}
              onClick={() => !pass && exceptions.length > 0 ? setExpanded(isOpen ? null : cp.checkpointId) : null}
              style={{
                padding: 14, borderRadius: 14,
                border: `1px solid ${pass ? 'rgba(34,197,94,0.2)' : 'rgba(248,113,113,0.25)'}`,
                background: pass ? 'rgba(34,197,94,0.06)' : 'rgba(248,113,113,0.06)',
                cursor: !pass && exceptions.length > 0 ? 'pointer' : 'default',
                gridColumn: isOpen ? '1 / -1' : undefined,
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8 }}>
                <div>
                  <div style={{ fontSize: 10, color: '#64748b', fontWeight: 800, letterSpacing: '0.08em', textTransform: 'uppercase', marginBottom: 4 }}>
                    CP {cp.checkpointId}
                  </div>
                  <div style={{ fontSize: 13, fontWeight: 800, color: 'var(--text-primary)' }}>{cp.name}</div>
                  <div style={{ fontSize: 11, color: '#64748b', marginTop: 4, lineHeight: 1.5 }}>
                    {DCMA_DESCRIPTIONS[cp.checkpointId]}
                  </div>
                </div>
                <div style={{ textAlign: 'right', flexShrink: 0 }}>
                  <div style={{ fontSize: 18, fontWeight: 800, color: pass ? '#86efac' : '#fca5a5' }}>{scoreLabel}</div>
                  <div style={{ fontSize: 10, color: '#64748b', marginTop: 2 }}>
                    {cp.violatingCount} / {cp.totalCount} fail
                  </div>
                </div>
              </div>

              {/* Expanded exception list */}
              {isOpen && exceptions.length > 0 && (
                <div style={{ marginTop: 12, borderTop: '1px solid rgba(248,113,113,0.15)', paddingTop: 10 }}>
                  <div style={{ fontSize: 11, fontWeight: 800, color: '#f87171', marginBottom: 6, textTransform: 'uppercase', letterSpacing: '0.06em' }}>
                    Exceptions ({Math.min(exceptions.length, 200)}{exceptions.length >= 200 ? '+' : ''})
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 4, maxHeight: 300, overflowY: 'auto' }}>
                    {exceptions.slice(0, 200).map((ex, i) => (
                      <div key={i} style={{ fontSize: 11, color: '#cbd5e1', padding: '6px 10px', background: 'rgba(248,113,113,0.06)', borderRadius: 8, lineHeight: 1.5 }}>
                        {ex.activityId ? <span style={{ color: '#93c5fd', fontWeight: 700 }}>{ex.activityId} </span> : null}
                        {ex.activityName ? <span style={{ color: '#94a3b8' }}>{ex.activityName} · </span> : null}
                        {ex.reason}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}

export default function PrimaveraReportsPage() {
  const {
    projects,
    activeProject,
    setActiveProject,
    setSelectedProjects,
    fetchProjects,
  } = useProject()

  const [createOpen, setCreateOpen] = useState(false)
  const [createDraft, setCreateDraft] = useState({ name: '', projectCode: '', notes: '' })
  const [creating, setCreating] = useState(false)
  const [workflow, setWorkflow] = useState(null)
  const [workflowLoading, setWorkflowLoading] = useState(false)
  const [baselineFile, setBaselineFile] = useState(null)
  const [uploading, setUploading] = useState(false)
  const [progressOptions, setProgressOptions] = useState(null)
  const [optionsLoading, setOptionsLoading] = useState(false)
  const [selectedResourceTypes, setSelectedResourceTypes] = useState([])
  const [selectedResourceNames, setSelectedResourceNames] = useState([])
  const [allResourceNames, setAllResourceNames] = useState(false)
  const [progressNotes, setProgressNotes] = useState('')
  const [savingProgress, setSavingProgress] = useState(false)
  const [processingMonitorOpen, setProcessingMonitorOpen] = useState(false)
  const [step3Mode, setStep3Mode] = useState('analyze_baseline')
  const [analysisDate, setAnalysisDate] = useState('')
  const [analysis, setAnalysis] = useState(null)
  const [analysisLoading, setAnalysisLoading] = useState(false)
  const [dcma, setDcma] = useState(null)
  const [dcmaLoading, setDcmaLoading] = useState(false)
  const [dcmaExpanded, setDcmaExpanded] = useState(null)

  const latestBaselineImport = workflow?.latestBaselineImport || null
  const progressMeasurementConfig = workflow?.progressMeasurementConfig || null
  const baselineComplete = latestBaselineImport?.status === 'completed'
  const baselineProcessing = latestBaselineImport?.status === 'processing'
  const hasProgressOptions = Boolean(progressOptions?.resourceTypes?.length && progressOptions?.resources?.length)
  const baselineHasNoResources = Boolean(baselineComplete && progressOptions && !optionsLoading && !hasProgressOptions)
  const baselineProgress = useMemo(() => parseProgressJson(latestBaselineImport?.progressJson), [latestBaselineImport?.progressJson])
  const baselineSummary = useMemo(() => parseProgressJson(latestBaselineImport?.summaryJson), [latestBaselineImport?.summaryJson])
  const processingMonitor = useMemo(() => {
    if (baselineProgress) {
      return baselineProgress
    }

    if (latestBaselineImport?.status === 'completed' && baselineSummary) {
      const tables = BASELINE_PROGRESS_TABLES.map((table) => {
        const detectedRows = Number(
          baselineSummary?.detectedTables?.[table.key]
          ?? baselineSummary?.[table.summaryKey]
          ?? 0
        )
        return {
          ...table,
          status: detectedRows > 0 ? 'completed' : 'skipped',
          detectedRows,
          persistedRows: Number(baselineSummary?.[table.summaryKey] ?? detectedRows),
        }
      })
      const completedTables = tables.filter((table) => table.status === 'completed' || table.status === 'skipped').length
      return {
        phase: 'completed',
        message: 'Baseline processing finished. The saved summary below reflects the latest imported table counts.',
        percent: 100,
        fileName: latestBaselineImport.originalFileName,
        completedTables,
        totalTables: tables.length,
        tables,
      }
    }

    if (uploading) {
      return {
        phase: 'uploading',
        message: 'Uploading the baseline file to the server and preparing the Primavera processor.',
        percent: 6,
        fileName: baselineFile?.name || latestBaselineImport?.originalFileName || 'Baseline upload',
        tables: BASELINE_PROGRESS_TABLES.map((table) => ({
          ...table,
          status: 'pending',
          detectedRows: 0,
          persistedRows: null,
        })),
      }
    }

    if (baselineProcessing) {
      return {
        phase: 'processing',
        message: 'The Primavera processor is running in the background. Table-level progress will appear here as each save completes.',
        percent: 15,
        fileName: latestBaselineImport?.originalFileName,
        tables: BASELINE_PROGRESS_TABLES.map((table) => ({
          ...table,
          status: 'pending',
          detectedRows: 0,
          persistedRows: null,
        })),
      }
    }

    return null
  }, [baselineFile?.name, baselineProcessing, baselineProgress, baselineSummary, latestBaselineImport?.originalFileName, latestBaselineImport?.status, uploading])

  useEffect(() => {
    if (!activeProject && projects.length) {
      setActiveProject(projects[0])
      setSelectedProjects([projects[0]])
    }
  }, [activeProject, projects, setActiveProject, setSelectedProjects])

  useEffect(() => {
    let cancelled = false

    const loadWorkflow = async () => {
      if (!activeProject?.id) {
        setWorkflow(null)
        return
      }
      setWorkflowLoading(true)
      try {
        const response = await xerProcessingApi.getWorkflow(activeProject.id)
        if (!cancelled) {
          setWorkflow(response.data.data)
        }
      } catch (error) {
        if (!cancelled) {
          toast.error(error.response?.data?.message || error.message || 'Failed to load Primavera workflow')
        }
      } finally {
        if (!cancelled) {
          setWorkflowLoading(false)
        }
      }
    }

    loadWorkflow()
    return () => {
      cancelled = true
    }
  }, [activeProject?.id])

  useEffect(() => {
    if (!activeProject?.id || !baselineProcessing) {
      return undefined
    }

    const timer = window.setInterval(async () => {
      try {
        const response = await xerProcessingApi.getWorkflow(activeProject.id)
        setWorkflow(response.data.data)
      } catch {
        // keep the current state and try again on the next interval
      }
    }, 2000)

    return () => window.clearInterval(timer)
  }, [activeProject?.id, baselineProcessing])

  useEffect(() => {
    if (uploading || baselineProcessing) {
      setProcessingMonitorOpen(true)
    }
  }, [uploading, baselineProcessing])

  useEffect(() => {
    if (latestBaselineImport?.dataDate) {
      setAnalysisDate(latestBaselineImport.dataDate)
      return
    }
    if (baselineComplete && !analysisDate) {
      setAnalysisDate(new Date().toISOString().slice(0, 10))
    }
  }, [baselineComplete, latestBaselineImport?.dataDate])

  useEffect(() => {
    let cancelled = false

    const loadOptions = async () => {
      if (!activeProject?.id || !baselineComplete) {
        setProgressOptions(null)
        return
      }

      setOptionsLoading(true)
      try {
        const response = await xerProcessingApi.getProgressMeasurementOptions(activeProject.id)
        if (cancelled) return
        const nextOptions = response.data.data
        setProgressOptions(nextOptions)

        const configuredTypes = parseJsonArray(
          workflow?.progressMeasurementConfig?.resourceTypesJson,
          workflow?.progressMeasurementConfig?.resourceType,
        )
        const configuredNames = parseJsonArray(
          workflow?.progressMeasurementConfig?.resourceNamesJson,
          workflow?.progressMeasurementConfig?.resourceName,
        )
        const configuredAllNames = Boolean(workflow?.progressMeasurementConfig?.allResourceNames)
        const initialTypes = configuredTypes.length
          ? configuredTypes
          : (nextOptions.resourceTypes || [])
        const initialVisibleNames = uniqueResourceNames(nextOptions.resources, initialTypes).map((item) => item.resourceName)
        const initialNames = configuredAllNames
          ? []
          : configuredNames.filter((name) => initialVisibleNames.includes(name))

        setSelectedResourceTypes(initialTypes)
        setSelectedResourceNames(initialNames)
        setAllResourceNames(configuredAllNames)
        setProgressNotes(workflow?.progressMeasurementConfig?.notes || '')
      } catch (error) {
        if (!cancelled) {
          toast.error(error.response?.data?.message || error.message || 'Failed to load progress-measurement options')
        }
      } finally {
        if (!cancelled) {
          setOptionsLoading(false)
        }
      }
    }

    loadOptions()
    return () => {
      cancelled = true
    }
  }, [activeProject?.id, baselineComplete, workflow?.progressMeasurementConfig])

  useEffect(() => {
    let cancelled = false

    const loadAnalysis = async () => {
      if (!activeProject?.id || !baselineComplete || step3Mode !== 'analyze_baseline') {
        if (step3Mode !== 'analyze_baseline') {
          setAnalysis(null)
        }
        return
      }

      setAnalysisLoading(true)
      try {
        const response = await xerProcessingApi.getBaselineAnalysis(activeProject.id, analysisDate || undefined)
        if (!cancelled) {
          setAnalysis(response.data.data)
        }
      } catch (error) {
        if (!cancelled) {
          toast.error(error.response?.data?.message || error.message || 'Failed to analyze the Primavera baseline')
        }
      } finally {
        if (!cancelled) {
          setAnalysisLoading(false)
        }
      }
    }

    loadAnalysis()
    return () => {
      cancelled = true
    }
  }, [activeProject?.id, analysisDate, baselineComplete, step3Mode])

  useEffect(() => {
    let cancelled = false

    const loadDcma = async () => {
      if (!activeProject?.id || !baselineComplete || step3Mode !== 'dcma_14') {
        if (step3Mode !== 'dcma_14') setDcma(null)
        return
      }

      setDcmaLoading(true)
      try {
        const response = await xerProcessingApi.getDcmaCheckpoints(activeProject.id)
        if (!cancelled) setDcma(response.data.data)
      } catch (error) {
        if (!cancelled) {
          if (error.response?.status === 404) {
            setDcma(null)
          } else {
            toast.error(error.response?.data?.message || 'Failed to load DCMA checkpoints')
          }
        }
      } finally {
        if (!cancelled) setDcmaLoading(false)
      }
    }

    loadDcma()
    return () => { cancelled = true }
  }, [activeProject?.id, baselineComplete, step3Mode])

  const visibleResourceNames = useMemo(() => {
    return uniqueResourceNames(progressOptions?.resources, selectedResourceTypes)
  }, [progressOptions?.resources, selectedResourceTypes])

  const savedResourceTypes = useMemo(
    () => parseJsonArray(progressMeasurementConfig?.resourceTypesJson, progressMeasurementConfig?.resourceType),
    [progressMeasurementConfig?.resourceType, progressMeasurementConfig?.resourceTypesJson],
  )

  const savedResourceNames = useMemo(
    () => parseJsonArray(progressMeasurementConfig?.resourceNamesJson, progressMeasurementConfig?.resourceName),
    [progressMeasurementConfig?.resourceName, progressMeasurementConfig?.resourceNamesJson],
  )

  const progressCurveData = useMemo(() => analysis?.progressCurve || [], [analysis?.progressCurve])
  const costCurveData = useMemo(() => analysis?.costCurve || [], [analysis?.costCurve])

  const monitorTables = processingMonitor?.tables?.length
    ? processingMonitor.tables
    : BASELINE_PROGRESS_TABLES.map((table) => ({
        ...table,
        status: 'pending',
        detectedRows: 0,
        persistedRows: null,
      }))

  const handleCreateProject = async (event) => {
    event.preventDefault()
    if (!createDraft.name.trim()) {
      toast.error('Enter a report project name')
      return
    }

    setCreating(true)
    try {
      const response = await xerProcessingApi.createProject({
        name: createDraft.name.trim(),
        projectCode: createDraft.projectCode.trim(),
        notes: createDraft.notes.trim(),
      })
      const createdProject = response.data.data
      await fetchProjects()
      setActiveProject(createdProject)
      setSelectedProjects([createdProject])
      setCreateOpen(false)
      setCreateDraft({ name: '', projectCode: '', notes: '' })
      toast.success('Primavera report project created')
    } catch (error) {
      toast.error(error.response?.data?.message || error.message || 'Failed to create the Primavera report project')
    } finally {
      setCreating(false)
    }
  }

  const handleBaselineUpload = async () => {
    if (!activeProject?.id) {
      toast.error('Create or select a Primavera project first')
      return
    }
    if (!baselineFile) {
      toast.error('Choose the baseline .xer file first')
      return
    }

    setUploading(true)
    setProcessingMonitorOpen(true)
    try {
      await xerProcessingApi.uploadBaseline(activeProject.id, baselineFile)
      setBaselineFile(null)
      const workflowResponse = await xerProcessingApi.getWorkflow(activeProject.id)
      setWorkflow(workflowResponse.data.data)
      toast.success('Baseline uploaded. Processing started in the background.')
    } catch (error) {
      const serverMessage = error.response?.data?.message
      const uploadMessage = error.response?.status === 413
        ? 'This XER file is too large for the current backend limit. Please keep uploads under 200 MB.'
        : serverMessage || error.message || 'Baseline upload failed'
      toast.error(uploadMessage)
    } finally {
      setUploading(false)
    }
  }

  const handleSaveProgressMeasurement = async () => {
    if (!activeProject?.id) {
      toast.error('Select a Primavera project first')
      return
    }
    if (!selectedResourceTypes.length) {
      toast.error('Choose at least one resource type')
      return
    }
    if (!allResourceNames && !selectedResourceNames.length) {
      toast.error('Choose one or more resource names, or use All')
      return
    }

    setSavingProgress(true)
    try {
      await xerProcessingApi.saveProgressMeasurement(activeProject.id, {
        baselineImportId: progressOptions?.baselineImportId || latestBaselineImport?.id,
        resourceTypes: selectedResourceTypes,
        resourceNames: selectedResourceNames,
        allResourceNames,
        notes: progressNotes.trim(),
      })
      const workflowResponse = await xerProcessingApi.getWorkflow(activeProject.id)
      setWorkflow(workflowResponse.data.data)
      toast.success('Progress measurement saved for future progress imports')
    } catch (error) {
      toast.error(error.response?.data?.message || error.message || 'Failed to save progress measurement')
    } finally {
      setSavingProgress(false)
    }
  }

  const toggleResourceType = (resourceType) => {
    setSelectedResourceTypes((current) => {
      const next = current.includes(resourceType)
        ? current.filter((item) => item !== resourceType)
        : [...current, resourceType]

      if (!allResourceNames) {
        const nextVisibleNames = new Set(uniqueResourceNames(progressOptions?.resources, next).map((item) => item.resourceName))
        setSelectedResourceNames((currentNames) => currentNames.filter((name) => nextVisibleNames.has(name)))
      }

      return next
    })
  }

  const toggleResourceName = (resourceName) => {
    setSelectedResourceNames((current) => (
      current.includes(resourceName)
        ? current.filter((item) => item !== resourceName)
        : [...current, resourceName]
    ))
  }

  return (
    <div style={{ display: 'grid', gap: 18 }}>
      <Card style={{ display: 'flex', justifyContent: 'space-between', gap: 16, alignItems: 'flex-start', flexWrap: 'wrap' }}>
        <div style={{ maxWidth: 720 }}>
          <div style={{ fontSize: 28, fontWeight: 800, color: 'var(--text-primary)' }}>Primavera Baseline Report Studio</div>
          <div style={{ fontSize: 14, color: '#64748b', marginTop: 8, lineHeight: 1.7 }}>
            Keep Primavera completely isolated from the MODUM workspace. Create a report project, upload the baseline XER,
            let the processor save the core tables in the background, then choose the rsrc_type and rsrc_name
            that should drive future progress measurements.
          </div>
        </div>
        <PrimaryButton icon={Plus} onClick={() => setCreateOpen(true)}>
          Create New Report
        </PrimaryButton>
      </Card>

      {!projects.length ? (
        <Card style={{ textAlign: 'center', padding: '42px 24px' }}>
          <div style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: 64, height: 64, borderRadius: 20, background: 'rgba(37,99,235,0.12)', color: '#60a5fa', marginBottom: 16 }}>
            <Database size={28} />
          </div>
          <div style={{ fontSize: 20, fontWeight: 800, color: 'var(--text-primary)' }}>No Primavera report projects yet</div>
          <div style={{ fontSize: 13, color: '#64748b', marginTop: 8, lineHeight: 1.7 }}>
            Start with "Create New Report", then we’ll take the workflow straight into baseline upload.
          </div>
        </Card>
      ) : null}

      {activeProject ? (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 0.8fr', gap: 18 }}>
            <Card>
              <Label>Selected Primavera Project</Label>
              <div style={{ fontSize: 22, fontWeight: 800, color: 'var(--text-primary)' }}>{activeProject.name}</div>
              <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginTop: 14 }}>
                {activeProject.projectCode ? <StatusPill value={activeProject.projectCode} /> : null}
                <StatusPill value={workflow?.project?.baselineStatus || activeProject.baselineStatus || 'pending'} />
                <StatusPill value={workflow?.project?.progressMeasurementStatus || activeProject.progressMeasurementStatus || 'pending'} />
              </div>
              <div style={{ fontSize: 13, color: '#64748b', marginTop: 14, lineHeight: 1.7 }}>
                {activeProject.notes || 'This project will hold the Primavera baseline import and the saved progress-measurement definition.'}
              </div>
            </Card>

            <Card>
              <Label>Workflow Snapshot</Label>
              {workflowLoading ? (
                <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8, color: '#94a3b8', fontSize: 13 }}>
                  <Loader2 size={15} style={{ animation: 'spin 1s linear infinite' }} />
                  Loading workflow...
                </div>
              ) : (
                <div style={{ display: 'grid', gap: 10 }}>
                  <div style={{ fontSize: 13, color: '#94a3b8' }}>
                    Baseline imports: <strong style={{ color: 'var(--text-primary)' }}>{workflow?.baselineImportCount || 0}</strong>
                  </div>
                  <div style={{ fontSize: 13, color: '#94a3b8' }}>
                    Latest baseline: <strong style={{ color: 'var(--text-primary)' }}>{latestBaselineImport?.originalFileName || 'Not uploaded yet'}</strong>
                  </div>
                  <div style={{ fontSize: 13, color: '#94a3b8' }}>
                    Updated: <strong style={{ color: 'var(--text-primary)' }}>{formatDateTime(latestBaselineImport?.completedAt || latestBaselineImport?.startedAt)}</strong>
                  </div>
                  <div style={{ display: 'grid', gap: 8, marginTop: 8 }}>
                    {(workflow?.nextSteps || []).map((step) => (
                      <div key={step} style={{ fontSize: 13, color: '#cbd5e1', lineHeight: 1.6 }}>
                        {step}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </Card>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 18, alignItems: 'start' }}>
            <Card>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'center', marginBottom: 18 }}>
                <div>
                  <Label>Step 1</Label>
                  <div style={{ fontSize: 20, fontWeight: 800, color: 'var(--text-primary)' }}>Upload Baseline XER</div>
                </div>
                <StatusPill value={workflow?.project?.baselineStatus || 'pending'} />
              </div>

              <div style={{ fontSize: 13, color: '#64748b', lineHeight: 1.7, marginBottom: 16 }}>
                Once uploaded, the backend stores the file, runs the Python processor in the background, and saves the parsed tables for this Primavera project. Local uploads currently support files up to 200 MB.
              </div>

              <input
                type="file"
                accept=".xer"
                onChange={(event) => setBaselineFile(event.target.files?.[0] || null)}
                style={{ width: '100%', marginBottom: 14, color: 'var(--text-primary)' }}
              />

              {baselineFile ? (
                <div style={{ fontSize: 12, color: '#cbd5e1', marginBottom: 14 }}>
                  Ready to upload: <strong>{baselineFile.name}</strong>
                </div>
              ) : null}

              <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                <PrimaryButton icon={FileUp} onClick={handleBaselineUpload} disabled={uploading || !baselineFile}>
                  {uploading ? 'Uploading...' : 'Upload Baseline'}
                </PrimaryButton>
                {baselineProcessing ? (
                  <SecondaryButton icon={Loader2} disabled>
                    Processing in background
                  </SecondaryButton>
                ) : null}
                {(processingMonitor || latestBaselineImport) ? (
                  <SecondaryButton icon={Database} onClick={() => setProcessingMonitorOpen(true)}>
                    View processing monitor
                  </SecondaryButton>
                ) : null}
              </div>

              {latestBaselineImport ? (
                <div style={{ marginTop: 18, padding: 14, borderRadius: 16, background: 'var(--bg-base)', border: '1px solid var(--border)' }}>
                  <div style={{ fontSize: 13, fontWeight: 800, color: 'var(--text-primary)' }}>{latestBaselineImport.originalFileName}</div>
                  <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginTop: 10 }}>
                    <StatusPill value={latestBaselineImport.status} />
                    {latestBaselineImport.projectCodeFromFile ? <StatusPill value={latestBaselineImport.projectCodeFromFile} /> : null}
                    {latestBaselineImport.revisionLabel ? <StatusPill value={latestBaselineImport.revisionLabel} /> : null}
                  </div>
                  {latestBaselineImport.errorMessage ? (
                    <div style={{ fontSize: 12, color: '#fca5a5', marginTop: 12, lineHeight: 1.7 }}>
                      {latestBaselineImport.errorMessage}
                    </div>
                  ) : null}
                </div>
              ) : null}
            </Card>

            <Card>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'center', marginBottom: 18 }}>
                <div>
                  <Label>Step 2</Label>
                  <div style={{ fontSize: 20, fontWeight: 800, color: 'var(--text-primary)' }}>Progress Measurement</div>
                </div>
                <StatusPill value={workflow?.project?.progressMeasurementStatus || 'pending'} />
              </div>

              <div style={{ fontSize: 13, color: '#64748b', lineHeight: 1.7, marginBottom: 16 }}>
                After the baseline is processed, choose the rsrc_type and rsrc_name from the imported file. That selection is then saved for future progress measurements.
              </div>

              {!baselineComplete ? (
                <div style={{ padding: 16, borderRadius: 16, background: 'var(--bg-base)', border: '1px solid var(--border)', color: '#94a3b8', fontSize: 13, lineHeight: 1.7 }}>
                  {baselineProcessing
                    ? 'The baseline is still processing. As soon as it finishes, the resource type and name dropdowns will populate from the imported file.'
                    : 'Upload and complete the baseline processing first.'}
                </div>
              ) : (
                <div style={{ display: 'grid', gap: 14 }}>
                  {baselineHasNoResources ? (
                    <div style={{ padding: 14, borderRadius: 16, background: 'rgba(148,163,184,0.08)', border: '1px solid rgba(148,163,184,0.16)', color: '#cbd5e1', fontSize: 13, lineHeight: 1.7 }}>
                      This baseline finished processing, but no rsrc_type / rsrc_name rows were found in the imported XER. Upload a baseline that includes resource assignments if you want the progress-measurement dropdowns to populate.
                    </div>
                  ) : null}

                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                    <div>
                      <Label>Resource Type</Label>
                      <div style={{ padding: 14, borderRadius: 16, background: 'var(--bg-base)', border: '1px solid var(--border)' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 10, flexWrap: 'wrap', marginBottom: 10 }}>
                          <div style={{ fontSize: 12, color: '#94a3b8' }}>
                            {selectedResourceTypes.length} selected
                          </div>
                          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                            <SecondaryButton
                              type="button"
                              onClick={() => setSelectedResourceTypes(progressOptions?.resourceTypes || [])}
                              disabled={optionsLoading || !progressOptions?.resourceTypes?.length}
                              style={{ padding: '8px 10px', fontSize: 12 }}
                            >
                              Select all
                            </SecondaryButton>
                            <SecondaryButton
                              type="button"
                              onClick={() => setSelectedResourceTypes([])}
                              disabled={optionsLoading || !selectedResourceTypes.length}
                              style={{ padding: '8px 10px', fontSize: 12 }}
                            >
                              Clear
                            </SecondaryButton>
                          </div>
                        </div>

                        {!progressOptions?.resourceTypes?.length ? (
                          <div style={{ fontSize: 12, color: '#94a3b8' }}>No resource types found in this baseline</div>
                        ) : (
                          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                            {progressOptions.resourceTypes.map((resourceType) => (
                              <ChoicePill
                                key={resourceType}
                                label={resourceType}
                                selected={selectedResourceTypes.includes(resourceType)}
                                onClick={() => toggleResourceType(resourceType)}
                                disabled={optionsLoading}
                              />
                            ))}
                          </div>
                        )}
                      </div>
                    </div>

                    <div>
                      <Label>Resource Name</Label>
                      <div style={{ padding: 14, borderRadius: 16, background: 'var(--bg-base)', border: '1px solid var(--border)' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 10, flexWrap: 'wrap', marginBottom: 10 }}>
                          <div style={{ fontSize: 12, color: '#94a3b8' }}>
                            {allResourceNames ? 'All names enabled' : `${selectedResourceNames.length} selected`}
                          </div>
                          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                            <ChoicePill
                              label="ALL"
                              selected={allResourceNames}
                              onClick={() => {
                                setAllResourceNames((current) => !current)
                                if (!allResourceNames) {
                                  setSelectedResourceNames([])
                                }
                              }}
                              disabled={optionsLoading || !visibleResourceNames.length}
                            />
                            <SecondaryButton
                              type="button"
                              onClick={() => setSelectedResourceNames([])}
                              disabled={optionsLoading || allResourceNames || !selectedResourceNames.length}
                              style={{ padding: '8px 10px', fontSize: 12 }}
                            >
                              Clear
                            </SecondaryButton>
                          </div>
                        </div>

                        {allResourceNames ? (
                          <div style={{ fontSize: 12, color: '#cbd5e1', lineHeight: 1.7 }}>
                            All resource names from the currently selected resource types will be used for future progress measurements.
                          </div>
                        ) : !visibleResourceNames.length ? (
                          <div style={{ fontSize: 12, color: '#94a3b8' }}>
                            No resource names found for the current type selection
                          </div>
                        ) : (
                          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', maxHeight: 220, overflowY: 'auto' }}>
                            {visibleResourceNames.map((item) => (
                              <ChoicePill
                                key={item.resourceName}
                                label={item.resourceName}
                                selected={selectedResourceNames.includes(item.resourceName)}
                                onClick={() => toggleResourceName(item.resourceName)}
                                disabled={optionsLoading}
                              />
                            ))}
                          </div>
                        )}
                      </div>
                    </div>
                  </div>

                  <div style={{ padding: 12, borderRadius: 14, background: 'rgba(37,99,235,0.08)', border: '1px solid rgba(37,99,235,0.16)', fontSize: 12, color: '#bfdbfe', lineHeight: 1.7 }}>
                    Pick one or more rsrc_type values, then either choose one or more rsrc_name values or switch on ALL to keep every visible name under those types.
                  </div>

                  <div>
                    <Label>Notes</Label>
                    <TextArea
                      value={progressNotes}
                      onChange={(event) => setProgressNotes(event.target.value)}
                      placeholder="Optional notes for how this progress measurement should be reused later."
                    />
                  </div>

                  <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                    <PrimaryButton icon={Settings2} onClick={handleSaveProgressMeasurement} disabled={savingProgress || optionsLoading || !selectedResourceTypes.length || (!allResourceNames && !selectedResourceNames.length) || baselineHasNoResources}>
                      {savingProgress ? 'Saving...' : 'Save Progress Measurement'}
                    </PrimaryButton>
                    {optionsLoading ? (
                      <SecondaryButton icon={Loader2} disabled>
                        Loading dropdown options
                      </SecondaryButton>
                    ) : null}
                  </div>
                </div>
              )}

              {progressMeasurementConfig ? (
                <div style={{ marginTop: 18, padding: 14, borderRadius: 16, background: 'var(--bg-base)', border: '1px solid var(--border)' }}>
                  <div style={{ fontSize: 13, fontWeight: 800, color: 'var(--text-primary)' }}>Saved measurement profile</div>
                  <div style={{ display: 'grid', gap: 8, marginTop: 10, fontSize: 12, color: '#cbd5e1' }}>
                    <div><strong>Resource types:</strong> {savedResourceTypes.length ? savedResourceTypes.join(', ') : progressMeasurementConfig.resourceType}</div>
                    <div><strong>Resource names:</strong> {progressMeasurementConfig.allResourceNames ? 'ALL visible names from the selected types' : (savedResourceNames.length ? savedResourceNames.join(', ') : progressMeasurementConfig.resourceName)}</div>
                    <div><strong>Configured:</strong> {formatDateTime(progressMeasurementConfig.configuredAt)}</div>
                    {progressMeasurementConfig.notes ? <div><strong>Notes:</strong> {progressMeasurementConfig.notes}</div> : null}
                  </div>
                </div>
              ) : null}
            </Card>
          </div>

          {baselineComplete ? (
            <Card style={{ display: 'grid', gap: 16 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'center', flexWrap: 'wrap' }}>
                <div>
                  <Label>Step 3</Label>
                  <div style={{ fontSize: 20, fontWeight: 800, color: 'var(--text-primary)' }}>Baseline Intelligence</div>
                </div>
                <StatusPill value={step3Mode === 'analyze_baseline' ? 'active' : 'pending'} />
              </div>

              <div style={{ fontSize: 13, color: '#64748b', lineHeight: 1.7 }}>
                Choose the next Primavera step: Analyze Baseline, 14 DCMA Checkpoints, or Prepare Project Report.
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 12 }}>
                {STEP3_ACTIONS.map((action) => (
                  <StepActionCard
                    key={action.key}
                    title={action.label}
                    description={action.description}
                    status={action.status}
                    icon={action.icon}
                    accent={action.accent}
                    active={step3Mode === action.key}
                    onClick={() => setStep3Mode(action.key)}
                  />
                ))}
              </div>

              {step3Mode === 'analyze_baseline' ? (
                <div style={{ display: 'grid', gap: 16 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', alignItems: 'flex-end' }}>
                    <div style={{ maxWidth: 740 }}>
                      <div style={{ fontSize: 16, fontWeight: 800, color: 'var(--text-primary)' }}>Analyze Baseline</div>
                      <div style={{ fontSize: 12, color: '#64748b', marginTop: 6, lineHeight: 1.7 }}>
                        Start with the baseline dataDate, planned KPI cut, and the first planned S-curves for progress and cost. This uses the current progress-measurement scope when it exists.
                      </div>
                    </div>
                    <div style={{ display: 'grid', gap: 8, minWidth: 220 }}>
                      <Label>Selected Data Date</Label>
                      <Input type="date" value={analysisDate} onChange={(event) => setAnalysisDate(event.target.value)} />
                    </div>
                  </div>

                  {!progressMeasurementConfig ? (
                    <div style={{ padding: 14, borderRadius: 16, background: 'rgba(250,204,21,0.08)', border: '1px solid rgba(250,204,21,0.18)', color: '#fde68a', fontSize: 12, lineHeight: 1.7 }}>
                      No progress-measurement filter is saved yet, so the baseline analysis will use all imported task-resource rows until you save a Primavera scope in Step 2.
                    </div>
                  ) : null}

                  {analysisLoading ? (
                    <div style={{ padding: 18, borderRadius: 16, background: 'var(--bg-base)', border: '1px solid var(--border)', color: '#94a3b8', fontSize: 13, display: 'inline-flex', alignItems: 'center', gap: 8 }}>
                      <Loader2 size={15} style={{ animation: 'spin 1s linear infinite' }} />
                      Building the baseline KPI and S-curve view...
                    </div>
                  ) : analysis ? (
                    <>
                      <div style={{ padding: 14, borderRadius: 16, background: 'var(--bg-base)', border: '1px solid var(--border)', display: 'grid', gap: 8 }}>
                        <div style={{ fontSize: 13, fontWeight: 800, color: 'var(--text-primary)' }}>Current analysis scope</div>
                        <div style={{ fontSize: 12, color: '#cbd5e1', lineHeight: 1.7 }}>{analysis.scopeSummary}</div>
                        <div style={{ fontSize: 12, color: '#64748b', lineHeight: 1.7 }}>{analysis.calculationNote}</div>
                      </div>

                      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', gap: 12 }}>
                        <MetricTile
                          label="Plan %"
                          value={formatPercent(analysis.planPercent)}
                          sub={`Planned progress at ${analysis.selectedDataDate || analysisDate || 'the selected data date'}`}
                        />
                        <MetricTile
                          label="Planned Value Cost"
                          value={formatCurrency(analysis.plannedCostAtDataDate)}
                          sub="Cumulative planned cost at the selected data date"
                        />
                        <MetricTile
                          label="Total Baseline Cost"
                          value={formatCurrency(analysis.totalPlannedCost)}
                          sub="Total planned cost in the current Primavera scope"
                        />
                        <MetricTile
                          label="Scope Size"
                          value={`${formatCount(analysis.taskResourceCount)} / ${formatCount(analysis.activityCount)}`}
                          sub="Task-resource rows and linked activities included in this cut"
                        />
                      </div>

                      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
                        <div style={{ padding: 16, borderRadius: 16, background: 'var(--bg-base)', border: '1px solid var(--border)' }}>
                          <div style={{ fontSize: 15, fontWeight: 800, color: 'var(--text-primary)', marginBottom: 8 }}>Planned Progress S-Curve</div>
                          <div style={{ fontSize: 12, color: '#64748b', lineHeight: 1.6, marginBottom: 14 }}>
                            Planned progress percentage driven by the saved Primavera scope and the selected data date.
                          </div>
                          <div style={{ width: '100%', height: 280 }}>
                            <ResponsiveContainer>
                              <LineChart data={progressCurveData} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
                                <CartesianGrid stroke="rgba(148,163,184,0.12)" vertical={false} />
                                <XAxis dataKey="label" stroke="#64748b" tick={{ fill: '#94a3b8', fontSize: 11 }} />
                                <YAxis stroke="#64748b" tick={{ fill: '#94a3b8', fontSize: 11 }} tickFormatter={(value) => `${value}%`} width={48} />
                                <Tooltip
                                  formatter={(value) => [formatPercent(value), 'Planned progress']}
                                  labelFormatter={(label) => `Bucket: ${label}`}
                                  contentStyle={{ background: '#0f172a', border: '1px solid rgba(148,163,184,0.18)', borderRadius: 12 }}
                                />
                                <ReferenceLine
                                  y={analysis.planPercent}
                                  stroke="rgba(96,165,250,0.55)"
                                  strokeDasharray="4 4"
                                />
                                <Line type="monotone" dataKey="plannedProgressPercent" stroke="#60a5fa" strokeWidth={3} dot={false} />
                              </LineChart>
                            </ResponsiveContainer>
                          </div>
                        </div>

                        <div style={{ padding: 16, borderRadius: 16, background: 'var(--bg-base)', border: '1px solid var(--border)' }}>
                          <div style={{ fontSize: 15, fontWeight: 800, color: 'var(--text-primary)', marginBottom: 8 }}>Planned Cost S-Curve</div>
                          <div style={{ fontSize: 12, color: '#64748b', lineHeight: 1.6, marginBottom: 14 }}>
                            Cumulative planned cost spread linearly across the saved baseline task-resource dates.
                          </div>
                          <div style={{ width: '100%', height: 280 }}>
                            <ResponsiveContainer>
                              <LineChart data={costCurveData} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
                                <CartesianGrid stroke="rgba(148,163,184,0.12)" vertical={false} />
                                <XAxis dataKey="label" stroke="#64748b" tick={{ fill: '#94a3b8', fontSize: 11 }} />
                                <YAxis stroke="#64748b" tick={{ fill: '#94a3b8', fontSize: 11 }} tickFormatter={(value) => formatCurrency(value)} width={82} />
                                <Tooltip
                                  formatter={(value) => [formatCurrency(value), 'Cumulative planned cost']}
                                  labelFormatter={(label) => `Bucket: ${label}`}
                                  contentStyle={{ background: '#0f172a', border: '1px solid rgba(148,163,184,0.18)', borderRadius: 12 }}
                                />
                                <ReferenceLine
                                  y={analysis.plannedCostAtDataDate}
                                  stroke="rgba(52,211,153,0.55)"
                                  strokeDasharray="4 4"
                                />
                                <Line type="monotone" dataKey="cumulativePlannedCost" stroke="#34d399" strokeWidth={3} dot={false} />
                              </LineChart>
                            </ResponsiveContainer>
                          </div>
                        </div>
                      </div>
                    </>
                  ) : (
                    <div style={{ padding: 18, borderRadius: 16, background: 'var(--bg-base)', border: '1px solid var(--border)', color: '#94a3b8', fontSize: 13 }}>
                      No Primavera analysis is available yet for this baseline.
                    </div>
                  )}
                </div>
              ) : step3Mode === 'dcma_14' ? (
                <DcmaPanel dcma={dcma} loading={dcmaLoading} expanded={dcmaExpanded} setExpanded={setDcmaExpanded} baselineComplete={baselineComplete} />
              ) : (
                <div style={{ padding: 18, borderRadius: 16, background: 'var(--bg-base)', border: '1px solid var(--border)', color: '#cbd5e1', fontSize: 13, lineHeight: 1.7 }}>
                  The project-report lane will build on the baseline analysis and DCMA results. Once the KPI and S-curves are stable, we’ll use this branch to compose the Primavera project report package.
                </div>
              )}
            </Card>
          ) : null}
        </>
      ) : null}

      <Modal open={createOpen} onClose={() => setCreateOpen(false)} title="Create Primavera Report Project">
        <form onSubmit={handleCreateProject} style={{ display: 'grid', gap: 14 }}>
          <div>
            <Label>Report Project Name</Label>
            <Input
              value={createDraft.name}
              onChange={(event) => setCreateDraft((current) => ({ ...current, name: event.target.value }))}
              placeholder="Baseline Report - Package A"
            />
          </div>
          <div>
            <Label>Project Code</Label>
            <Input
              value={createDraft.projectCode}
              onChange={(event) => setCreateDraft((current) => ({ ...current, projectCode: event.target.value }))}
              placeholder="Optional code"
            />
          </div>
          <div>
            <Label>Notes</Label>
            <TextArea
              value={createDraft.notes}
              onChange={(event) => setCreateDraft((current) => ({ ...current, notes: event.target.value }))}
              placeholder="Short context for this Primavera reporting workflow."
            />
          </div>
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
            <SecondaryButton type="button" onClick={() => setCreateOpen(false)}>
              Cancel
            </SecondaryButton>
            <PrimaryButton type="submit" icon={Plus} disabled={creating}>
              {creating ? 'Creating...' : 'Create Project'}
            </PrimaryButton>
          </div>
        </form>
      </Modal>

      <Modal open={processingMonitorOpen} onClose={() => setProcessingMonitorOpen(false)} title="Baseline Processing Monitor">
        <div style={{ display: 'grid', gap: 16 }}>
          <div style={{ padding: 14, borderRadius: 16, background: 'var(--bg-base)', border: '1px solid var(--border)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'flex-start', flexWrap: 'wrap' }}>
              <div>
                <div style={{ fontSize: 16, fontWeight: 800, color: 'var(--text-primary)' }}>
                  {processingMonitor?.fileName || latestBaselineImport?.originalFileName || 'Baseline import'}
                </div>
                <div style={{ fontSize: 13, color: '#94a3b8', marginTop: 6, lineHeight: 1.6 }}>
                  {processingMonitor?.message || 'Waiting for the baseline processor to begin.'}
                </div>
              </div>
              <StatusPill value={latestBaselineImport?.status || processingMonitor?.phase || 'pending'} />
            </div>

            <div style={{ marginTop: 16 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 10, fontSize: 12, color: '#cbd5e1', marginBottom: 8 }}>
                <span>{processingMonitor?.phase ? `Phase: ${String(processingMonitor.phase).replaceAll('_', ' ')}` : 'Phase: pending'}</span>
                <span>{Math.max(0, Math.min(100, Math.round(processingMonitor?.percent || 0)))}%</span>
              </div>
              <div style={{ height: 10, borderRadius: 999, background: 'rgba(148,163,184,0.14)', overflow: 'hidden' }}>
                <div
                  style={{
                    width: `${Math.max(0, Math.min(100, Number(processingMonitor?.percent || 0)))}%`,
                    height: '100%',
                    borderRadius: 999,
                    background: 'linear-gradient(90deg, #2563eb 0%, #22c55e 100%)',
                    transition: 'width 0.4s ease',
                  }}
                />
              </div>
            </div>

            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', marginTop: 14 }}>
              <span style={{ fontSize: 12, color: '#cbd5e1' }}>
                Tables completed: <strong>{processingMonitor?.completedTables || 0}/{processingMonitor?.totalTables || BASELINE_PROGRESS_TABLES.length}</strong>
              </span>
              <span style={{ fontSize: 12, color: '#94a3b8' }}>
                Started: <strong style={{ color: '#cbd5e1' }}>{formatDateTime(latestBaselineImport?.startedAt || processingMonitor?.startedAt)}</strong>
              </span>
            </div>

            {latestBaselineImport?.errorMessage ? (
              <div style={{ marginTop: 14, padding: 12, borderRadius: 14, background: 'rgba(248,113,113,0.10)', border: '1px solid rgba(248,113,113,0.20)', color: '#fecaca', fontSize: 12, lineHeight: 1.7 }}>
                {latestBaselineImport.errorMessage}
              </div>
            ) : null}
          </div>

          <div style={{ display: 'grid', gap: 12 }}>
            {monitorTables.map((table) => (
              <div
                key={table.key}
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'minmax(0, 1fr) auto auto',
                  gap: 12,
                  alignItems: 'center',
                  padding: 14,
                  borderRadius: 16,
                  background: 'var(--bg-base)',
                  border: '1px solid var(--border)',
                }}
              >
                <div>
                  <div style={{ fontSize: 14, fontWeight: 800, color: 'var(--text-primary)' }}>{table.label}</div>
                  <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>
                    Raw XER table {table.key}
                  </div>
                </div>
                <div style={{ textAlign: 'right', fontSize: 12, color: '#cbd5e1', lineHeight: 1.6 }}>
                  <div>Detected: <strong>{formatCount(table.detectedRows)}</strong></div>
                  <div>Saved: <strong>{table.persistedRows == null ? '—' : formatCount(table.persistedRows)}</strong></div>
                </div>
                <StatusPill value={table.status} />
              </div>
            ))}
          </div>
        </div>
      </Modal>
    </div>
  )
}
