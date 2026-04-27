import React, { useEffect, useMemo, useRef, useState } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import {
  BarChart3,
  Bot,
  Briefcase,
  Building2,
  CalendarDays,
  CheckCircle2,
  CheckSquare,
  ChevronDown,
  ClipboardList,
  FileBarChart2,
  FileText,
  FolderKanban,
  LayoutGrid,
  LogOut,
  Moon,
  Radar,
  RefreshCw,
  Search,
  ShieldCheck,
  Sun,
  UserRound,
  Users,
  Wrench,
  X,
} from 'lucide-react'
import { PRIVATE_LOGIN_PATH } from '../../config/appRoutes'
import toast from 'react-hot-toast'
import { useAuth } from '../../context/AuthContext'
import { useTheme } from '../../context/ThemeContext'
import { useProject } from '../../context/ProjectContext'
import ModumLogo from '../branding/ModumLogo'
import {
  assetsApi,
  checklistsApi,
  companiesApi,
  equipmentApi,
  personsApi,
  projectsApi,
  rolesApi,
  tasksApi,
  issuesApi,
} from '../../services/api'

const PRIMARY_TABS = [
  { label: 'Tracker Pulse', to: '/tracker-pulse', icon: LayoutGrid },
  { label: 'Planned vs Actual', to: '/planned-vs-actual', icon: BarChart3 },
  { label: 'Checklists Flow', to: '/asset-readiness', icon: ClipboardList },
  { label: 'Issue Radar', to: '/issue-radar', icon: Radar },
  { label: 'AI Copilot', to: '/ai-copilot', icon: Bot },
  { label: 'Reports', to: '/reports', icon: FileBarChart2 },
  { label: 'Project Access', to: '/project-access', icon: Briefcase, adminOnly: true },
]

const PROJECT_MENU_ITEMS = [
  { label: 'Tasks', to: '/tasks', icon: CheckSquare },
  { label: 'Checklists', to: '/checklists', icon: ClipboardList },
  { label: 'Issues', to: '/issues', icon: Radar },
  { label: 'Equipment', to: '/equipment', icon: Wrench },
  { label: 'Files', to: '/files', icon: FileText },
  { label: 'People', to: '/persons', icon: Users },
  { label: 'Companies', to: '/companies', icon: Building2 },
  { label: 'Roles', to: '/roles', icon: UserRound },
  { label: 'Sync', to: '/sync', icon: RefreshCw },
]

const SYNC_STEPS = [
  { key: 'projects', label: 'Projects', run: (projectId) => projectsApi.syncOne(projectId) },
  { key: 'issues', label: 'Issues', run: (projectId) => issuesApi.syncAll(projectId) },
  { key: 'tasks', label: 'Tasks', run: (projectId) => tasksApi.sync(projectId) },
  { key: 'checklists', label: 'Checklists', run: (projectId) => checklistsApi.syncWithStatusDates(projectId) },
  { key: 'equipment', label: 'Equipment', run: (projectId) => equipmentApi.sync(projectId) },
  { key: 'persons', label: 'Persons', run: (projectId) => personsApi.sync(projectId) },
  { key: 'companies', label: 'Companies', run: (projectId) => companiesApi.sync(projectId) },
  { key: 'roles', label: 'Roles', run: (projectId) => rolesApi.sync(projectId) },
  { key: 'assets', label: 'Assets', run: (projectId) => assetsApi.syncAll(projectId) },
  { key: 'files', label: 'Files', run: null, optional: true, skipMessage: 'Skipped in quick sync. Run file sync separately when needed.' },
]

function formatDateTime(value) {
  if (!value) return 'Never'
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

function getWeekLabel() {
  const now = new Date()
  const jan1 = new Date(now.getFullYear(), 0, 1)
  const week = Math.ceil(((now - jan1) / 86400000 + jan1.getDay() + 1) / 7)
  return `${now.getFullYear()}-W${String(week).padStart(2, '0')}`
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
    case 'SKIPPED':
      return { color: '#cbd5e1', border: 'rgba(148,163,184,0.22)', background: 'rgba(148,163,184,0.08)' }
    default:
      return { color: '#94a3b8', border: 'rgba(148,163,184,0.22)', background: 'rgba(148,163,184,0.08)' }
  }
}

function buildTableStatuses(projectName) {
  return SYNC_STEPS.map((step) => ({
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

function PrimaryTabLink({ tab }) {
  const Icon = tab.icon

  return (
    <NavLink
      to={tab.to}
      style={({ isActive }) => ({
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 8,
        minWidth: 0,
        flex: '1 1 0',
        height: 42,
        padding: '0 12px',
        borderRadius: 12,
        border: isActive ? '1px solid rgba(14,165,233,0.5)' : '1px solid transparent',
        background: isActive ? 'linear-gradient(180deg, rgba(14,165,233,0.28), rgba(14,165,233,0.14))' : 'transparent',
        color: isActive ? '#f8fafc' : '#8ea4c8',
        textDecoration: 'none',
        fontSize: 13,
        fontWeight: 700,
        transition: 'all 0.18s ease',
        boxShadow: isActive ? '0 10px 24px rgba(14,165,233,0.14)' : 'none',
      })}
    >
      <Icon size={15} />
      <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{tab.label}</span>
    </NavLink>
  )
}

function ActionIconButton({ icon: Icon, label, onClick, active, disabled, accent = '#8ea4c8', spinning = false }) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      title={label}
      style={{
        width: 38,
        height: 38,
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        borderRadius: 12,
        border: `1px solid ${active ? 'rgba(14,165,233,0.55)' : 'rgba(255,255,255,0.08)'}`,
        background: active ? 'rgba(14,165,233,0.16)' : 'rgba(255,255,255,0.03)',
        color: disabled ? '#4b5563' : accent,
        cursor: disabled ? 'not-allowed' : 'pointer',
        transition: 'all 0.18s ease',
        opacity: disabled ? 0.55 : 1,
      }}
    >
      <Icon size={16} style={spinning ? { animation: 'spin 1s linear infinite' } : undefined} />
    </button>
  )
}

export default function Navbar() {
  const { logout, isAdmin, provider } = useAuth()
  const { isDark, toggleTheme } = useTheme()
  const {
    activeProject,
    fetchProjects,
    projects,
    selectedProjects,
    setActiveProject,
    setSelectedProjects,
    toggleProject,
    period,
    setPeriod,
  } = useProject()
  const navigate = useNavigate()
  const location = useLocation()
  const closeTimerRef = useRef(null)
  const moreRef = useRef(null)
  const projectRef = useRef(null)
  const [syncStatus, setSyncStatus] = useState(null)
  const [syncWindowOpen, setSyncWindowOpen] = useState(false)
  const [syncStarting, setSyncStarting] = useState(false)
  const [lastUpdatedAt, setLastUpdatedAt] = useState('')
  const [moreOpen, setMoreOpen] = useState(false)
  const [projectOpen, setProjectOpen] = useState(false)
  const [search, setSearch] = useState('')

  const isPrimavera = provider === 'primavera'
  const visiblePrimaryTabs = (isPrimavera
    ? PRIMARY_TABS.filter((tab) => tab.to === '/reports')
    : PRIMARY_TABS
  ).filter((tab) => !tab.adminOnly || isAdmin)
  const visibleProjectMenuItems = isPrimavera ? [] : PROJECT_MENU_ITEMS
  const isProjectMenuRoute = visibleProjectMenuItems.some((tab) => tab.to === location.pathname)
  const count = selectedProjects.length
  const primaryProject = activeProject || selectedProjects[0] || null
  const secondaryLine = isPrimavera
    ? [primaryProject?.projectCode, primaryProject?.status].filter(Boolean).join(' | ')
    : [primaryProject?.client, primaryProject?.location].filter(Boolean).join(' | ')

  const filteredProjects = useMemo(
    () => projects.filter((project) =>
      !search ||
      (project.name || '').toLowerCase().includes(search.toLowerCase()) ||
      (project.projectCode || '').toLowerCase().includes(search.toLowerCase()) ||
      (project.externalId || '').toLowerCase().includes(search.toLowerCase()) ||
      (project.client || '').toLowerCase().includes(search.toLowerCase()) ||
      (project.location || '').toLowerCase().includes(search.toLowerCase())
    ),
    [projects, search]
  )

  useEffect(() => {
    const key = activeProject?.externalId ? `last_sync_completed_at:${activeProject.externalId}` : 'last_sync_completed_at'
    setLastUpdatedAt(localStorage.getItem(key) || '')
    return () => clearTimeout(closeTimerRef.current)
  }, [activeProject?.externalId])

  useEffect(() => {
    const onMouseDown = (event) => {
      if (moreRef.current && !moreRef.current.contains(event.target)) setMoreOpen(false)
      if (projectRef.current && !projectRef.current.contains(event.target)) setProjectOpen(false)
    }
    document.addEventListener('mousedown', onMouseDown)
    return () => document.removeEventListener('mousedown', onMouseDown)
  }, [])

  const handleSyncFinished = async (data) => {
    setSyncStarting(false)
    const completedAt = data?.completedAt || new Date().toISOString()
    const key = activeProject?.externalId ? `last_sync_completed_at:${activeProject.externalId}` : 'last_sync_completed_at'
    localStorage.setItem(key, completedAt)
    localStorage.setItem('last_sync_completed_at', completedAt)
    setLastUpdatedAt(completedAt)
    try {
      await fetchProjects()
    } catch {
      // best effort
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
        for (let index = 0; index < currentTables.length; index += 1) {
          if (currentTables[index].key === step.key) {
            currentTables[index] = {
              ...currentTables[index],
              status: 'SKIPPED',
              runningCount: 0,
              message: step.skipMessage || '',
              projectName: activeProject.name,
            }
          }
        }
        setSyncStatus((prev) => ({
          ...(prev || {}),
          running: completedSteps < SYNC_STEPS.length,
          progressPercent: Math.round((completedSteps / SYNC_STEPS.length) * 100),
          currentItem: step.skipMessage || `Skipped ${step.label}`,
          lastUpdatedAt: new Date().toISOString(),
          tableStatuses: [...currentTables],
        }))
        continue
      }

      setSyncStatus((prev) => ({
        ...(prev || {}),
        running: true,
        currentItem: `${step.label} - ${activeProject.name}`,
        lastUpdatedAt: new Date().toISOString(),
        tableStatuses: currentTables.map((item) =>
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

        for (let index = 0; index < currentTables.length; index += 1) {
          if (currentTables[index].key === step.key) {
            currentTables[index] = {
              ...currentTables[index],
              ...normalized,
              runningCount: 0,
            }
          }
        }

        setSyncStatus((prev) => ({
          ...(prev || {}),
          running: completedSteps < SYNC_STEPS.length,
          progressPercent: Math.round((completedSteps / SYNC_STEPS.length) * 100),
          currentItem: completedSteps < SYNC_STEPS.length ? `Completed ${step.label}` : `Completed ${activeProject.name}`,
          lastUpdatedAt: new Date().toISOString(),
          tableStatuses: [...currentTables],
        }))
      } catch (error) {
        anyFailures = true
        completedSteps += 1
        const message = error.response?.data?.message || `Failed to sync ${step.label.toLowerCase()}`
        for (let index = 0; index < currentTables.length; index += 1) {
          if (currentTables[index].key === step.key) {
            currentTables[index] = {
              ...currentTables[index],
              status: 'FAILED',
              runningCount: 0,
              failedProjects: 1,
              message,
            }
          }
        }
        setSyncStatus((prev) => ({
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
      currentItem: anyFailures
        ? `Selected project sync finished with warnings for ${activeProject.name}`
        : `Selected project sync completed for ${activeProject.name}`,
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
  const dateStr = new Date().toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })

  if (isPrimavera) {
    return (
      <header
        style={{
          background: 'linear-gradient(180deg, rgba(11,19,35,0.98), rgba(13,22,40,0.98))',
          borderBottom: '1px solid rgba(255,255,255,0.07)',
          position: 'sticky',
          top: 0,
          zIndex: 40,
          backdropFilter: 'blur(18px)',
        }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 16,
            minHeight: 68,
            padding: '12px 24px',
            borderBottom: '1px solid rgba(255,255,255,0.05)',
          }}
        >
          <ModumLogo
            label="MODUM IQ"
            sublabel="Primavera Report Workflow"
            size="sm"
          />

          <nav style={{ display: 'flex', alignItems: 'center', gap: 8, flex: 1, maxWidth: 240 }}>
            {visiblePrimaryTabs.map((tab) => (
              <PrimaryTabLink key={tab.to} tab={tab} />
            ))}
          </nav>

          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <ActionIconButton
              icon={isDark ? Sun : Moon}
              label={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
              onClick={toggleTheme}
              active={isDark}
              accent={isDark ? '#fbbf24' : '#cbd5e1'}
            />
            <ActionIconButton
              icon={LogOut}
              label="Sign out"
              onClick={async () => {
                await logout()
                navigate(PRIVATE_LOGIN_PATH)
              }}
              accent="#fda4af"
            />
          </div>
        </div>

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 16,
            minHeight: 74,
            padding: '14px 24px',
            background: 'rgba(255,255,255,0.02)',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 16, minWidth: 0, flex: 1 }}>
            <div style={{ minWidth: 320, maxWidth: 520, flex: '1 1 420px' }}>
              <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.12em', marginBottom: 8 }}>
                Primavera Project
              </div>
              <select
                value={activeProject?.id || ''}
                onChange={(event) => {
                  const nextProject = projects.find((project) => String(project.id) === event.target.value) || null
                  setActiveProject(nextProject)
                  setSelectedProjects(nextProject ? [nextProject] : [])
                }}
                style={{
                  width: '100%',
                  padding: '12px 14px',
                  borderRadius: 16,
                  border: '1px solid rgba(255,255,255,0.08)',
                  background: 'rgba(255,255,255,0.03)',
                  color: '#f8fafc',
                  fontSize: 14,
                  fontWeight: 700,
                  outline: 'none',
                }}
              >
                {!projects.length ? <option value="">No Primavera projects yet</option> : null}
                {projects.map((project) => (
                  <option key={project.id} value={project.id}>
                    {project.name}{project.projectCode ? ` (${project.projectCode})` : ''}
                  </option>
                ))}
              </select>
            </div>

            <div
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 8,
                padding: '10px 12px',
                borderRadius: 14,
                border: '1px solid rgba(255,255,255,0.08)',
                background: 'rgba(255,255,255,0.03)',
                color: '#a7b6cf',
                fontSize: 12,
                fontWeight: 700,
              }}
            >
              <CalendarDays size={15} />
              <span>{dateStr}</span>
              <span style={{ color: '#5e718f' }}>|</span>
              <span>{activeProject?.projectCode || 'Create a report project to begin'}</span>
            </div>
          </div>

          <div style={{ textAlign: 'right' }}>
            <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.12em' }}>Last Updated</div>
            <div style={{ fontSize: 12, color: '#dbe7fb', fontWeight: 700 }}>{formatDateTime(lastUpdatedAt)}</div>
          </div>
        </div>
      </header>
    )
  }

  return (
    <header
      style={{
        background: 'linear-gradient(180deg, rgba(11,19,35,0.98), rgba(13,22,40,0.98))',
        borderBottom: '1px solid rgba(255,255,255,0.07)',
        position: 'sticky',
        top: 0,
        zIndex: 40,
        backdropFilter: 'blur(18px)',
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 18,
          minHeight: 68,
          padding: '12px 24px',
          borderBottom: '1px solid rgba(255,255,255,0.05)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', minWidth: 260, maxWidth: 340 }}>
          <ModumLogo
            label="MODUM IQ"
            sublabel="Data-Driven Delivery"
            size="sm"
          />
        </div>

        <nav style={{ display: 'flex', alignItems: 'center', gap: 8, flex: 1, minWidth: 0 }}>
          {visiblePrimaryTabs.map((tab) => (
            <PrimaryTabLink key={tab.to} tab={tab} />
          ))}

          {!isPrimavera ? (
            <div ref={moreRef} style={{ position: 'relative', flex: '0 0 122px' }}>
            <button
              onClick={() => setMoreOpen((open) => !open)}
              style={{
                width: '100%',
                height: 42,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 8,
                borderRadius: 12,
                border: `1px solid ${isProjectMenuRoute || moreOpen ? 'rgba(14,165,233,0.5)' : 'rgba(255,255,255,0.08)'}`,
                background: isProjectMenuRoute || moreOpen ? 'rgba(14,165,233,0.14)' : 'rgba(255,255,255,0.03)',
                color: isProjectMenuRoute || moreOpen ? '#f8fafc' : '#8ea4c8',
                fontSize: 13,
                fontWeight: 700,
                cursor: 'pointer',
                transition: 'all 0.18s ease',
              }}
            >
              <FolderKanban size={15} />
              <span>More</span>
              <ChevronDown size={14} style={{ transform: moreOpen ? 'rotate(180deg)' : 'rotate(0deg)', transition: 'transform 0.18s ease' }} />
            </button>

            {moreOpen && (
              <div
                style={{
                  position: 'absolute',
                  top: 'calc(100% + 10px)',
                  right: 0,
                  width: 260,
                  background: '#101a2d',
                  border: '1px solid rgba(255,255,255,0.08)',
                  borderRadius: 16,
                  boxShadow: '0 24px 60px rgba(2,6,23,0.45)',
                  padding: 10,
                  zIndex: 70,
                }}
              >
                <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.12em', padding: '6px 8px 10px' }}>
                  Project Tools
                </div>
                <div style={{ display: 'grid', gap: 6 }}>
                  {visibleProjectMenuItems.map((item) => {
                    const Icon = item.icon
                    const active = item.to === location.pathname
                    return (
                      <NavLink
                        key={item.to}
                        to={item.to}
                        onClick={() => setMoreOpen(false)}
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: 10,
                          padding: '11px 12px',
                          borderRadius: 12,
                          textDecoration: 'none',
                          color: active ? '#f8fafc' : '#a7b6cf',
                          background: active ? 'rgba(14,165,233,0.16)' : 'transparent',
                          border: active ? '1px solid rgba(14,165,233,0.34)' : '1px solid transparent',
                          fontSize: 13,
                          fontWeight: 700,
                        }}
                      >
                        <Icon size={15} />
                        <span>{item.label}</span>
                      </NavLink>
                    )
                  })}
                </div>
              </div>
            )}
            </div>
          ) : null}
        </nav>
      </div>

      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 16,
          minHeight: 74,
          padding: '14px 24px',
          background: 'rgba(255,255,255,0.02)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, minWidth: 0, flex: 1 }}>
          <div ref={projectRef} style={{ position: 'relative', minWidth: 360, maxWidth: 560, flex: '1 1 420px' }}>
            <button
              onClick={() => setProjectOpen((open) => !open)}
              style={{
                width: '100%',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 12,
                padding: '12px 14px',
                borderRadius: 16,
                border: '1px solid rgba(255,255,255,0.08)',
                background: 'rgba(255,255,255,0.03)',
                cursor: 'pointer',
                textAlign: 'left',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, minWidth: 0 }}>
                <div
                  style={{
                    width: 42,
                    height: 42,
                    borderRadius: 14,
                    background: 'rgba(14,165,233,0.14)',
                    border: '1px solid rgba(14,165,233,0.26)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    color: '#38bdf8',
                    flexShrink: 0,
                  }}
                >
                  <FolderKanban size={18} />
                </div>
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.12em', marginBottom: 3 }}>
                    Scope
                  </div>
                  <div style={{ fontSize: 15, fontWeight: 800, color: '#f8fafc', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {primaryProject?.name || 'Select project'}
                  </div>
                  <div style={{ fontSize: 12, color: '#7f8ea8', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {secondaryLine || 'Choose a project to scope the dashboard and sync flow.'}
                  </div>
                </div>
              </div>
              <ChevronDown size={16} color="#7f8ea8" style={{ flexShrink: 0, transform: projectOpen ? 'rotate(180deg)' : 'rotate(0deg)', transition: 'transform 0.18s ease' }} />
            </button>

            {projectOpen && (
              <div
                style={{
                  position: 'absolute',
                  top: 'calc(100% + 10px)',
                  left: 0,
                  width: '100%',
                  background: '#101a2d',
                  border: '1px solid rgba(255,255,255,0.08)',
                  borderRadius: 18,
                  boxShadow: '0 24px 60px rgba(2,6,23,0.45)',
                  overflow: 'hidden',
                  zIndex: 70,
                }}
              >
                <div style={{ padding: '14px 16px 10px', borderBottom: '1px solid rgba(255,255,255,0.06)' }}>
                  <div style={{ position: 'relative' }}>
                    <Search size={14} style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: '#64748b' }} />
                    <input
                      autoFocus
                      type="text"
                      placeholder="Search project, client, location"
                      value={search}
                      onChange={(event) => setSearch(event.target.value)}
                      style={{
                        width: '100%',
                        padding: '10px 12px 10px 36px',
                        borderRadius: 12,
                        border: '1px solid rgba(255,255,255,0.08)',
                        background: 'rgba(255,255,255,0.04)',
                        color: '#f8fafc',
                        fontSize: 12,
                        outline: 'none',
                        fontFamily: 'inherit',
                      }}
                    />
                  </div>
                </div>

                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    padding: '10px 16px',
                    borderBottom: '1px solid rgba(255,255,255,0.06)',
                    fontSize: 11,
                    color: '#7f8ea8',
                  }}
                >
                  <span>{projects.length} projects loaded</span>
                  <span>{count > 0 ? 'Single-select scope' : 'Pick one project'}</span>
                </div>

                <div style={{ maxHeight: 360, overflowY: 'auto' }}>
                  {filteredProjects.length === 0 && (
                    <div style={{ padding: '24px 16px', textAlign: 'center', fontSize: 12, color: '#64748b' }}>
                      No projects match "{search}"
                    </div>
                  )}

                  {filteredProjects.map((project) => {
                    const selected = selectedProjects.some((item) => item.id === project.id)
                    const meta = isPrimavera
                      ? [project.projectCode, project.status].filter(Boolean).join(' | ')
                      : [project.client, project.location, project.status].filter(Boolean).join(' | ')
                    return (
                      <div
                        key={project.id}
                        onClick={() => {
                          toggleProject(project)
                          setProjectOpen(false)
                        }}
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'space-between',
                          gap: 12,
                          padding: '12px 16px',
                          cursor: 'pointer',
                          background: selected ? 'rgba(14,165,233,0.09)' : 'transparent',
                          borderLeft: selected ? '3px solid #38bdf8' : '3px solid transparent',
                          borderBottom: '1px solid rgba(255,255,255,0.05)',
                        }}
                      >
                        <div style={{ minWidth: 0 }}>
                          <div style={{ fontSize: 13, fontWeight: 700, color: '#f8fafc' }}>{project.name}</div>
                          {meta && <div style={{ fontSize: 11, color: '#7f8ea8', marginTop: 2 }}>{meta}</div>}
                        </div>
                        <span
                          style={{
                            flexShrink: 0,
                            padding: '5px 10px',
                            borderRadius: 999,
                            fontSize: 10,
                            fontWeight: 800,
                            letterSpacing: '0.06em',
                            color: selected ? '#38bdf8' : '#94a3b8',
                            background: selected ? 'rgba(14,165,233,0.18)' : 'rgba(255,255,255,0.04)',
                          }}
                        >
                          {selected ? 'IN SCOPE' : 'SELECT'}
                        </span>
                      </div>
                    )
                  })}
                </div>
              </div>
            )}
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <button
              onClick={startSync}
              disabled={syncRunning || !activeProject}
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 8,
                padding: '12px 20px',
                borderRadius: 16,
                border: `1px solid ${syncRunning ? 'rgba(125,211,252,0.45)' : 'rgba(255,255,255,0.10)'}`,
                background: syncRunning ? 'rgba(14,165,233,0.18)' : 'linear-gradient(135deg, rgba(59,130,246,0.18), rgba(14,165,233,0.12))',
                color: syncRunning ? '#7dd3fc' : (!activeProject ? '#4b5563' : '#e2e8f0'),
                fontSize: 13,
                fontWeight: 800,
                cursor: syncRunning || !activeProject ? 'not-allowed' : 'pointer',
                opacity: syncRunning || !activeProject ? 0.72 : 1,
                transition: 'all 0.18s ease',
                boxShadow: syncRunning ? '0 10px 24px rgba(14,165,233,0.18)' : '0 10px 24px rgba(37,99,235,0.14)',
              }}
              title={activeProject ? `Sync ${activeProject.name}` : 'Select a project first'}
            >
              <RefreshCw size={15} style={syncRunning ? { animation: 'spin 1s linear infinite' } : undefined} />
              {syncRunning ? 'Syncing Scope...' : 'Sync Scope'}
            </button>
            <div
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 8,
                padding: '10px 12px',
                borderRadius: 14,
                border: '1px solid rgba(255,255,255,0.08)',
                background: 'rgba(255,255,255,0.03)',
                color: '#a7b6cf',
                fontSize: 12,
                fontWeight: 700,
              }}
            >
              <CalendarDays size={15} />
              <span>{dateStr}</span>
              <span style={{ color: '#5e718f' }}>•</span>
              <span>{getWeekLabel()}</span>
            </div>

            <div
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 6,
                padding: 4,
                borderRadius: 14,
                border: '1px solid rgba(255,255,255,0.08)',
                background: 'rgba(255,255,255,0.03)',
              }}
            >
              {['Overall', 'D', 'W', 'M'].map((value) => {
                const active = period === value
                return (
                  <button
                    key={value}
                    onClick={() => setPeriod(value)}
                    title={{ Overall: 'All time', D: 'Daily', W: 'Weekly', M: 'Monthly' }[value]}
                    style={{
                      minWidth: value === 'Overall' ? 78 : 36,
                      height: 34,
                      padding: value === 'Overall' ? '0 14px' : '0 12px',
                      borderRadius: 10,
                      border: 'none',
                      background: active ? 'linear-gradient(180deg, #22c1ff, #0ea5e9)' : 'transparent',
                      color: active ? '#ffffff' : '#9fb1cd',
                      fontSize: 12,
                      fontWeight: 800,
                      cursor: 'pointer',
                      boxShadow: active ? '0 10px 24px rgba(14,165,233,0.24)' : 'none',
                    }}
                  >
                    {value}
                  </button>
                )
              })}
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexShrink: 0 }}>
          <div style={{ textAlign: 'right', marginRight: 4 }}>
            <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.12em' }}>Last Updated</div>
            <div style={{ fontSize: 12, color: '#dbe7fb', fontWeight: 700 }}>{formatDateTime(lastUpdatedAt)}</div>
          </div>
          <ActionIconButton
            icon={isDark ? Sun : Moon}
            label={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
            onClick={toggleTheme}
            active={isDark}
            accent={isDark ? '#fbbf24' : '#cbd5e1'}
          />
          <ActionIconButton
            icon={LogOut}
            label="Sign out"
            onClick={async () => {
              await logout()
              navigate(PRIVATE_LOGIN_PATH)
            }}
            accent="#fda4af"
          />
        </div>
      </div>

      {syncWindowOpen && (
        <div
          style={{
            position: 'fixed',
            top: 132,
            right: 20,
            width: 440,
            maxWidth: 'calc(100vw - 24px)',
            background: '#0f172a',
            border: '1px solid rgba(255,255,255,0.08)',
            borderRadius: 18,
            boxShadow: '0 22px 60px rgba(2,6,23,0.55)',
            zIndex: 80,
            overflow: 'hidden',
          }}
        >
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
                    <span
                      style={{
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
                      }}
                    >
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
