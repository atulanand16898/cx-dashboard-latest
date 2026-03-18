import React, { useEffect, useRef, useState } from 'react'
import { NavLink, useNavigate, useLocation } from 'react-router-dom'
import { Activity, CheckCircle2, RefreshCw, X } from 'lucide-react'
import { useAuth } from '../../context/AuthContext'
import { useTheme } from '../../context/ThemeContext'
import { useProject } from '../../context/ProjectContext'
import { assetsApi, checklistsApi, companiesApi, equipmentApi, personsApi, projectsApi, rolesApi, syncApi, tasksApi, issuesApi } from '../../services/api'
import toast from 'react-hot-toast'

// Primary landing-page tabs — always visible in main navbar
// Order matches modum.me exactly
const PRIMARY_TABS = [
  { label: 'Tracker Pulse',    to: '/tracker-pulse' },
  { label: 'Planned vs Actual', to: '/planned-vs-actual' },
  { label: 'Checklist Flow',   to: '/checklist-flow' },
  { label: 'Issue Radar',      to: '/issue-radar' },
  { label: 'AI Copilot',       to: '/ai-copilot' },
  { label: 'Asset Readiness',  to: '/asset-readiness' },
  { label: 'Reports',          to: '/reports' },
  { label: 'Project Access',   to: '/project-access', adminOnly: true },
]

// Project-context tabs — shown in secondary bar only when a project is selected
const PROJECT_TABS = [
  { label: 'Tasks',      to: '/tasks' },
  { label: 'Checklists', to: '/checklists' },
  { label: 'Equipment',  to: '/equipment' },
  { label: 'People',     to: '/persons' },
  { label: 'Companies',  to: '/companies' },
  { label: 'Roles',      to: '/roles' },
  { label: 'Sync',       to: '/sync' },
]

const SYNC_STEPS = [
  { key: 'projects', label: 'Projects', run: (projectId) => projectsApi.syncOne(projectId) },
  { key: 'issues', label: 'Issues', run: (projectId) => issuesApi.syncAll(projectId) },
  { key: 'tasks', label: 'Tasks', run: (projectId) => tasksApi.sync(projectId) },
  { key: 'checklists', label: 'Checklists', run: (projectId) => checklistsApi.sync(projectId) },
  { key: 'equipment', label: 'Equipment', run: (projectId) => equipmentApi.sync(projectId) },
  { key: 'persons', label: 'Persons', run: (projectId) => personsApi.sync(projectId) },
  { key: 'companies', label: 'Companies', run: (projectId) => companiesApi.sync(projectId) },
  { key: 'roles', label: 'Roles', run: (projectId) => rolesApi.sync(projectId) },
  { key: 'assets', label: 'Assets', run: (projectId) => assetsApi.syncAll(projectId) },
  { key: 'files', label: 'Files', run: null, optional: true, skipMessage: 'Skipped in quick sync. Run file sync separately when needed.' },
]

function TabLink({ to, label, compact }) {
  return (
    <NavLink
      to={to}
      style={({ isActive }) => ({
        padding: compact ? '5px 10px' : '6px 14px',
        borderRadius: 8,
        fontSize: compact ? 12 : 13,
        fontWeight: 500,
        color: isActive ? 'white' : '#64748b',
        background: isActive ? '#0ea5e9' : 'transparent',
        textDecoration: 'none',
        transition: 'all 0.18s ease',
        whiteSpace: 'nowrap',
        flexShrink: 0,
      })}
      onMouseEnter={e => {
        if (!e.currentTarget.style.background.includes('0ea5e9')) {
          e.currentTarget.style.color = '#cbd5e1'
        }
      }}
      onMouseLeave={e => {
        if (!e.currentTarget.style.background.includes('0ea5e9')) {
          e.currentTarget.style.color = '#64748b'
        }
      }}
    >
      {label}
    </NavLink>
  )
}

function formatDateTime(value) {
  if (!value) return 'Not synced yet'
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

function statusColors(status) {
  switch ((status || '').toUpperCase()) {
    case 'COMPLETED':
      return { color: '#86efac', border: 'rgba(34,197,94,0.24)', background: 'rgba(34,197,94,0.10)' }
    case 'RUNNING':
      return { color: '#7dd3fc', border: 'rgba(14,165,233,0.24)', background: 'rgba(14,165,233,0.10)' }
    case 'PARTIAL':
      return { color: '#fde68a', border: 'rgba(250,204,21,0.24)', background: 'rgba(250,204,21,0.10)' }
    case 'FAILED':
      return { color: '#fca5a5', border: 'rgba(248,113,113,0.24)', background: 'rgba(248,113,113,0.10)' }
    default:
      return { color: '#94a3b8', border: 'rgba(148,163,184,0.22)', background: 'rgba(148,163,184,0.08)' }
  }
}

function buildTableStatuses(projectName) {
  return SYNC_STEPS.map(step => ({
    key: step.key,
    label: step.label,
    status: 'PENDING',
    runningCount: 0,
    completedProjects: 0,
    failedProjects: 0,
    recordsSynced: 0,
    projectName: projectName || '',
    message: '',
  }))
}

function normalizeSyncResponse(stepKey, payload, fallbackProjectName) {
  if (!payload) {
    return {
      status: 'FAILED',
      recordsSynced: 0,
      completedProjects: 0,
      failedProjects: 1,
      projectName: fallbackProjectName,
      message: 'No response',
    }
  }

  if (stepKey === 'projects') {
    const status = String(payload.status || 'SUCCESS').toUpperCase()
    return {
      status: status === 'SUCCESS' ? 'COMPLETED' : status === 'PARTIAL' ? 'PARTIAL' : 'FAILED',
      recordsSynced: Number(payload.recordsSynced || 0),
      completedProjects: status === 'SUCCESS' || status === 'PARTIAL' ? 1 : 0,
      failedProjects: status === 'FAILED' ? 1 : 0,
      projectName: fallbackProjectName,
      message: payload.message || '',
    }
  }

  if (payload.recordsSynced !== undefined && payload.status !== undefined) {
    const status = String(payload.status || 'SUCCESS').toUpperCase()
    return {
      status: status === 'SUCCESS' ? 'COMPLETED' : status === 'PARTIAL' ? 'PARTIAL' : 'FAILED',
      recordsSynced: Number(payload.recordsSynced || 0),
      completedProjects: status === 'SUCCESS' || status === 'PARTIAL' ? 1 : 0,
      failedProjects: status === 'FAILED' ? 1 : 0,
      projectName: fallbackProjectName,
      message: payload.message || '',
    }
  }

  if (payload.syncResult) {
    return {
      status: 'COMPLETED',
      recordsSynced: Number(payload.syncResult.totalSynced || 0),
      completedProjects: 1,
      failedProjects: 0,
      projectName: fallbackProjectName,
      message: 'File sync completed',
    }
  }

  const details = Array.isArray(payload.details) ? payload.details : []
  const detail = details[0] || {}
  const failedCount = Number(payload.failedCount || 0)
  const successCount = Number(payload.successCount || 0)
  const status = failedCount > 0 ? (successCount > 0 ? 'PARTIAL' : 'FAILED') : 'COMPLETED'

  return {
    status,
    recordsSynced: Number(payload.totalRecordsSynced || detail.recordsSynced || 0),
    completedProjects: successCount,
    failedProjects: failedCount,
    projectName: detail.projectName || fallbackProjectName,
    message: detail.errorMessage || '',
  }
}

export default function Navbar() {
  const { logout, isAdmin } = useAuth()
  const { isDark, toggleTheme } = useTheme()
  const { activeProject, fetchProjects } = useProject()
  const navigate = useNavigate()
  const location = useLocation()
  const closeTimerRef = useRef(null)
  const [syncStatus, setSyncStatus] = useState(null)
  const [syncWindowOpen, setSyncWindowOpen] = useState(false)
  const [syncStarting, setSyncStarting] = useState(false)
  const [lastUpdatedAt, setLastUpdatedAt] = useState('')

  // Detect if current page is a project-context page
  const isProjectPage = PROJECT_TABS.some(t => location.pathname === t.to)
  const hasProject = !!activeProject

  const visiblePrimaryTabs = PRIMARY_TABS.filter(tab => !tab.adminOnly || isAdmin)

  useEffect(() => {
    const key = activeProject?.externalId
      ? `last_sync_completed_at:${activeProject.externalId}`
      : 'last_sync_completed_at'
    setLastUpdatedAt(localStorage.getItem(key) || '')
    return () => {
      clearTimeout(closeTimerRef.current)
    }
  }, [activeProject?.externalId])

  const handleSyncFinished = async (data) => {
    setSyncStarting(false)
    const completedAt = data?.completedAt || new Date().toISOString()
    const key = activeProject?.externalId
      ? `last_sync_completed_at:${activeProject.externalId}`
      : 'last_sync_completed_at'
    localStorage.setItem(key, completedAt)
    localStorage.setItem('last_sync_completed_at', completedAt)
    setLastUpdatedAt(completedAt)
    try {
      await fetchProjects()
    } catch {
      // project refresh is best-effort
    }
    closeTimerRef.current = setTimeout(() => {
      setSyncWindowOpen(false)
    }, 2200)
  }

  const startSync = async () => {
    if (!activeProject?.externalId) {
      toast.error('Select a project first')
      return
    }

    const startedAt = new Date().toISOString()
    const initialTables = buildTableStatuses(activeProject.name)
    setSyncWindowOpen(true)
    setSyncStarting(true)
    clearTimeout(closeTimerRef.current)
    setSyncStatus({
      running: true,
      complete: false,
      failed: false,
      progressPercent: 0,
      currentItem: `Starting sync for ${activeProject.name}`,
      startedAt,
      lastUpdatedAt: startedAt,
      tableStatuses: initialTables,
      message: '',
    })

    let completedSteps = 0
    let anyFailures = false
    const currentTables = buildTableStatuses(activeProject.name)

    for (const step of SYNC_STEPS) {
      if (!step.run) {
        completedSteps += 1
        for (let i = 0; i < currentTables.length; i += 1) {
          if (currentTables[i].key === step.key) {
            currentTables[i] = {
              ...currentTables[i],
              status: 'SKIPPED',
              runningCount: 0,
              message: step.skipMessage || '',
              projectName: activeProject.name,
            }
          }
        }
        setSyncStatus(prev => ({
          ...(prev || {}),
          running: completedSteps < SYNC_STEPS.length,
          progressPercent: Math.round((completedSteps / SYNC_STEPS.length) * 100),
          currentItem: step.skipMessage || `Skipped ${step.label}`,
          lastUpdatedAt: new Date().toISOString(),
          tableStatuses: [...currentTables],
        }))
        continue
      }

      setSyncStatus(prev => ({
        ...(prev || {}),
        running: true,
        currentItem: `${step.label} • ${activeProject.name}`,
        lastUpdatedAt: new Date().toISOString(),
        tableStatuses: currentTables.map(item =>
          item.key === step.key
            ? { ...item, status: 'RUNNING', runningCount: 1 }
            : item
        ),
      }))

      try {
        const response = await step.run(activeProject.externalId)
        const normalized = normalizeSyncResponse(step.key, response.data?.data, activeProject.name)
        anyFailures = anyFailures || normalized.status === 'FAILED' || normalized.status === 'PARTIAL'
        completedSteps += 1

        for (let i = 0; i < currentTables.length; i += 1) {
          if (currentTables[i].key === step.key) {
            currentTables[i] = {
              ...currentTables[i],
              ...normalized,
              runningCount: 0,
            }
          }
        }

        setSyncStatus(prev => ({
          ...(prev || {}),
          running: completedSteps < SYNC_STEPS.length,
          progressPercent: Math.round((completedSteps / SYNC_STEPS.length) * 100),
          currentItem: completedSteps < SYNC_STEPS.length ? `Completed ${step.label}` : `Completed ${activeProject.name}`,
          lastUpdatedAt: new Date().toISOString(),
          tableStatuses: [...currentTables],
        }))
      } catch (err) {
        anyFailures = true
        completedSteps += 1
        const message = err.response?.data?.message || `Failed to sync ${step.label.toLowerCase()}`
        for (let i = 0; i < currentTables.length; i += 1) {
          if (currentTables[i].key === step.key) {
            currentTables[i] = {
              ...currentTables[i],
              status: 'FAILED',
              runningCount: 0,
              failedProjects: 1,
              message,
            }
          }
        }
        setSyncStatus(prev => ({
          ...(prev || {}),
          running: completedSteps < SYNC_STEPS.length,
          progressPercent: Math.round((completedSteps / SYNC_STEPS.length) * 100),
          currentItem: message,
          lastUpdatedAt: new Date().toISOString(),
          tableStatuses: [...currentTables],
        }))
      }
    }

    const finishedAt = new Date().toISOString()
    const finalStatus = {
      running: false,
      complete: true,
      failed: anyFailures,
      progressPercent: 100,
      currentItem: anyFailures ? `Selected project sync finished with warnings for ${activeProject.name}` : `Selected project sync completed for ${activeProject.name}`,
      startedAt,
      completedAt: finishedAt,
      lastUpdatedAt: finishedAt,
      tableStatuses: [...currentTables],
      message: anyFailures ? 'Selected project sync completed with some failures.' : 'Selected project sync completed successfully.',
    }
    setSyncStatus(finalStatus)
    await handleSyncFinished(finalStatus)
    toast.success(anyFailures ? 'Selected project sync completed with warnings' : 'Selected project sync completed')
  }

  const progress = Math.round(syncStatus?.progressPercent || 0)
  const tableStatuses = syncStatus?.tableStatuses || []
  const syncRunning = syncStarting
  const syncDone = !!syncStatus?.complete && !syncRunning

  return (
    <header style={{
      background: '#0d1628',
      borderBottom: '1px solid rgba(255,255,255,0.07)',
      position: 'sticky', top: 0, zIndex: 40,
    }}>
      {/* ── Row 1: Logo + Primary Tabs + Controls ── */}
      <div style={{ display: 'flex', alignItems: 'center', height: 54, padding: '0 20px', gap: 0 }}>

        {/* Logo */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginRight: 24, flexShrink: 0 }}>
          <div style={{
            width: 34, height: 34, borderRadius: 9,
            background: 'linear-gradient(135deg, #0ea5e9, #2563eb)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            boxShadow: '0 2px 12px rgba(14,165,233,0.35)',
          }}>
            <Activity size={16} color="white" />
          </div>
          <div>
            <div style={{ fontSize: 13, fontWeight: 700, color: 'white', lineHeight: 1.2 }}>MODEM IQ</div>
            <div style={{ fontSize: 9.5, color: '#475569', lineHeight: 1.2 }}>Data-Driven Project Decisions</div>
          </div>
        </div>

        {/* Primary nav tabs */}
        <nav style={{ display: 'flex', alignItems: 'center', gap: 2, flex: 1, overflow: 'hidden' }}>
          {visiblePrimaryTabs.map(tab => (
            <TabLink key={tab.to} to={tab.to} label={tab.label} />
          ))}
        </nav>

        {/* Right controls: sync + theme toggle + sign out */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexShrink: 0, marginLeft: 12 }}>
          <button
            onClick={startSync}
            disabled={syncRunning || !activeProject}
            title={activeProject ? `Sync selected project: ${activeProject.name}` : 'Select a project first'}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 8,
              padding: '6px 10px',
              borderRadius: 10,
              border: '1px solid rgba(14,165,233,0.22)',
              background: syncRunning ? 'rgba(14,165,233,0.12)' : 'rgba(255,255,255,0.03)',
              color: syncRunning ? '#7dd3fc' : !activeProject ? '#64748b' : '#cbd5e1',
              cursor: syncRunning || !activeProject ? 'not-allowed' : 'pointer',
              opacity: activeProject ? 1 : 0.7,
              transition: 'all 0.18s ease',
            }}
          >
            <RefreshCw size={14} style={syncRunning ? { animation: 'spin 1s linear infinite' } : undefined} />
            <span style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-start', lineHeight: 1.1 }}>
              <span style={{ fontSize: 12, fontWeight: 700 }}>Sync Project</span>
              <span style={{ fontSize: 10, color: '#64748b' }}>
                Last updated {lastUpdatedAt ? formatDateTime(lastUpdatedAt) : 'Never'}
              </span>
            </span>
          </button>

          {/* Theme toggle pill */}
          <button
            onClick={toggleTheme}
            title={`Switch to ${isDark ? 'light' : 'dark'} mode`}
            style={{
              display: 'flex', alignItems: 'center', gap: 6,
              background: 'none', border: 'none', cursor: 'pointer', padding: '4px 8px',
              borderRadius: 8,
              transition: 'background 0.15s',
            }}
            onMouseEnter={e => e.currentTarget.style.background = 'rgba(255,255,255,0.05)'}
            onMouseLeave={e => e.currentTarget.style.background = 'none'}
          >
            <span style={{ fontSize: 12, fontWeight: 600, color: '#94a3b8' }}>
              {isDark ? 'Light' : 'Dark'}
            </span>
            <span style={{
              width: 34, height: 20, borderRadius: 999,
              background: isDark ? '#0ea5e9' : '#334155',
              position: 'relative', display: 'inline-flex', alignItems: 'center',
              transition: 'background 0.25s ease',
              border: `1px solid ${isDark ? 'rgba(14,165,233,0.5)' : 'rgba(255,255,255,0.1)'}`,
              flexShrink: 0,
            }}>
              <span style={{
                position: 'absolute', top: 2,
                left: isDark ? 16 : 2,
                width: 14, height: 14, borderRadius: '50%',
                background: 'white',
                transition: 'left 0.25s ease',
                boxShadow: '0 1px 4px rgba(0,0,0,0.3)',
              }} />
            </span>
          </button>

          <div style={{ width: 1, height: 20, background: 'rgba(255,255,255,0.08)', flexShrink: 0 }} />

          <button
            onClick={async () => { await logout(); navigate('/login') }}
            style={{
              background: 'none', border: 'none', cursor: 'pointer',
              fontSize: 12, fontWeight: 500, color: '#64748b',
              display: 'flex', alignItems: 'center', gap: 5,
              padding: '4px 8px', borderRadius: 7,
              transition: 'all 0.15s',
            }}
            onMouseEnter={e => { e.currentTarget.style.color = '#f87171'; e.currentTarget.style.background = 'rgba(248,113,113,0.08)' }}
            onMouseLeave={e => { e.currentTarget.style.color = '#64748b'; e.currentTarget.style.background = 'none' }}
          >
            Sign Out
          </button>
        </div>
      </div>

      {/* ── Row 2: Project context tabs — only shown when a project is active ── */}
      {hasProject && (
        <div style={{
          display: 'flex', alignItems: 'center', height: 36,
          padding: '0 20px',
          borderTop: '1px solid rgba(255,255,255,0.04)',
          background: 'rgba(0,0,0,0.15)',
          gap: 2,
        }}>
          {/* Small label */}
          <span style={{
            fontSize: 10, fontWeight: 600, color: '#334155',
            textTransform: 'uppercase', letterSpacing: '0.08em',
            marginRight: 8, flexShrink: 0,
          }}>
            Project:
          </span>
          {PROJECT_TABS.map(tab => (
            <TabLink key={tab.to} to={tab.to} label={tab.label} compact />
          ))}
        </div>
      )}

      {syncWindowOpen && (
        <div style={{
          position: 'fixed',
          top: 72,
          right: 20,
          width: 440,
          maxWidth: 'calc(100vw - 24px)',
          background: '#0f172a',
          border: '1px solid rgba(255,255,255,0.08)',
          borderRadius: 18,
          boxShadow: '0 22px 60px rgba(2,6,23,0.55)',
          zIndex: 80,
          overflow: 'hidden',
        }}>
          <div style={{ padding: '16px 18px', borderBottom: '1px solid rgba(255,255,255,0.06)', display: 'flex', justifyContent: 'space-between', gap: 12 }}>
            <div>
              <div style={{ fontSize: 15, fontWeight: 800, color: 'white', display: 'flex', alignItems: 'center', gap: 8 }}>
                {syncDone ? <CheckCircle2 size={16} color="#22c55e" /> : <RefreshCw size={16} color="#38bdf8" style={syncRunning ? { animation: 'spin 1s linear infinite' } : undefined} />}
                Selected Project Sync
              </div>
              <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>
                {syncRunning ? (syncStatus?.currentItem || 'Sync in progress...') : syncDone ? 'Sync completed successfully.' : (syncStatus?.message || 'No active sync job')}
              </div>
            </div>
            <button
              onClick={() => setSyncWindowOpen(false)}
              style={{
                width: 30,
                height: 30,
                borderRadius: 10,
                border: '1px solid rgba(255,255,255,0.08)',
                background: 'transparent',
                color: '#94a3b8',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                flexShrink: 0,
              }}
            >
              <X size={14} />
            </button>
          </div>

          <div style={{ padding: 18, display: 'flex', flexDirection: 'column', gap: 14 }}>
            <div>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 10, marginBottom: 8 }}>
                <span style={{ fontSize: 12, color: '#cbd5e1', fontWeight: 700 }}>Overall progress</span>
                <span style={{ fontSize: 12, color: '#7dd3fc', fontWeight: 800 }}>{progress}%</span>
              </div>
              <div style={{ width: '100%', height: 10, borderRadius: 999, background: 'rgba(148,163,184,0.14)', overflow: 'hidden' }}>
                <div style={{ width: `${progress}%`, height: '100%', background: syncDone ? 'linear-gradient(90deg, #22c55e, #4ade80)' : 'linear-gradient(90deg, #0ea5e9, #2563eb)', transition: 'width 0.3s ease' }} />
              </div>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 10 }}>
              <div style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)', borderRadius: 12, padding: 12 }}>
                <div style={{ fontSize: 10, letterSpacing: '0.08em', textTransform: 'uppercase', color: '#64748b', marginBottom: 6 }}>Started</div>
                <div style={{ fontSize: 12, color: '#e2e8f0', fontWeight: 700 }}>{formatDateTime(syncStatus?.startedAt)}</div>
              </div>
              <div style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)', borderRadius: 12, padding: 12 }}>
                <div style={{ fontSize: 10, letterSpacing: '0.08em', textTransform: 'uppercase', color: '#64748b', marginBottom: 6 }}>Last Updated</div>
                <div style={{ fontSize: 12, color: '#e2e8f0', fontWeight: 700 }}>{formatDateTime(syncStatus?.lastUpdatedAt || lastUpdatedAt)}</div>
              </div>
            </div>

            <div style={{ maxHeight: 320, overflowY: 'auto', border: '1px solid rgba(255,255,255,0.06)', borderRadius: 14 }}>
              <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 0.8fr 0.8fr', padding: '10px 12px', background: 'rgba(148,163,184,0.08)', fontSize: 10, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '0.08em' }}>
                <span>Table</span>
                <span>Status</span>
                <span>Records</span>
              </div>
              {tableStatuses.map((item) => {
                const colors = statusColors(item.status)
                return (
                  <div key={item.key} style={{ display: 'grid', gridTemplateColumns: '1.2fr 0.8fr 0.8fr', padding: '12px', borderTop: '1px solid rgba(255,255,255,0.05)', alignItems: 'center', gap: 10 }}>
                    <div>
                      <div style={{ fontSize: 12, fontWeight: 700, color: '#e2e8f0' }}>{item.label}</div>
                      <div style={{ fontSize: 10, color: '#64748b', marginTop: 2 }}>
                        {item.projectName || (item.status === 'COMPLETED' ? 'Finished' : item.status === 'PENDING' ? 'Waiting' : 'In progress')}
                      </div>
                    </div>
                    <span style={{
                      display: 'inline-flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      padding: '6px 8px',
                      borderRadius: 999,
                      fontSize: 10,
                      fontWeight: 800,
                      color: colors.color,
                      border: `1px solid ${colors.border}`,
                      background: colors.background,
                    }}>
                      {item.status}
                    </span>
                    <div style={{ fontSize: 12, color: '#cbd5e1', fontWeight: 700 }}>
                      {item.recordsSynced || 0}
                      {(item.completedProjects || item.failedProjects) ? (
                        <div style={{ fontSize: 10, color: '#64748b', fontWeight: 500, marginTop: 2 }}>
                          {item.completedProjects || 0} ok / {item.failedProjects || 0} fail
                        </div>
                      ) : null}
                    </div>
                  </div>
                )
              })}
            </div>
          </div>
        </div>
      )}
    </header>
  )
}
