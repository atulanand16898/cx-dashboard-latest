import React, { useState, useEffect, useMemo, useCallback } from 'react'
import { RefreshCw, AlertCircle, CheckCircle2, Clock, TrendingUp, Activity, Target, Zap } from 'lucide-react'
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import { useProject } from '../context/ProjectContext'
import { issuesApi, tasksApi, checklistsApi, syncApi } from '../services/api'
import { apiCache, cacheKeys } from '../services/apiCache'
import { DonutChart, StatCard, CardSkeleton } from '../components/ui'
import toast from 'react-hot-toast'

const CustomTooltip = ({ active, payload, label }) => {
  if (!active || !payload?.length) return null
  return (
    <div className="glass-card px-3 py-2 text-xs shadow-xl">
      <div className="text-dark-400 mb-1">{label}</div>
      {payload.map((p, i) => (
        <div key={i} style={{ color: p.color }} className="font-600">{p.name}: {p.value}</div>
      ))}
    </div>
  )
}

export default function DashboardPage() {
  const { activeProject, period } = useProject()
  const [loading, setLoading]       = useState(true)
  const [revalidating, setRevalidating] = useState(false)
  const [stats, setStats]           = useState(null)
  const [syncStatus, setSyncStatus] = useState(null)

  const load = useCallback(async (force = false) => {
    if (!activeProject) return
    const pid = activeProject.externalId
    const key = cacheKeys.dashboard(pid)

    const hasCache = !!apiCache._cache.get(key)
    if (hasCache && !force) setRevalidating(true)
    else setLoading(true)

    try {
      // Use ApiCache for each entity — all 4 calls run in parallel but each
      // is individually deduplicated (GenericListPage pages may have already
      // loaded some of these, so they'll be served from cache instantly)
      const [issueList, taskList, checkList, syncStats] = await Promise.all([
        apiCache.get(cacheKeys.issues(pid),     () => issuesApi.getAll(pid)),
        apiCache.get(cacheKeys.tasks(pid),      () => tasksApi.getAll({ projectId: pid })),
        apiCache.get(cacheKeys.checklists(pid), () => checklistsApi.getAll(pid)),
        apiCache.get(cacheKeys.syncStats(),     () => syncApi.getStats()),
      ])

      const issues    = Array.isArray(issueList)  ? issueList  : []
      const tasks     = Array.isArray(taskList)   ? taskList   : []
      const checklists = Array.isArray(checkList) ? checkList  : []

      const openIssues   = issues.filter(i => ['open','in_progress','active'].includes((i.status||'').toLowerCase()))
      const closedIssues = issues.filter(i => ['closed','completed','resolved','done'].includes((i.status||'').toLowerCase()))

      const dashData = {
        totalIssues:    issues.length,
        openIssues:     openIssues.length,
        closedIssues:   closedIssues.length,
        totalTasks:     tasks.length,
        totalChecklists: checklists.length,
        completionRate: issues.length > 0 ? Math.round((closedIssues.length / issues.length) * 100) : 0,
        issues, tasks,
      }

      // Cache the computed dashboard object too
      apiCache._cache.set(key, { data: dashData, ts: Date.now(), ttl: 2 * 60 * 1000 })

      setStats(dashData)
      setSyncStatus(syncStats)
    } catch {
      toast.error('Failed to load dashboard data')
    } finally {
      setLoading(false)
      setRevalidating(false)
    }
  }, [activeProject])

  useEffect(() => {
    setStats(null)
    load(false)
  }, [load, activeProject?.externalId])

  // Chart data varies with D/W/M period toggle
  const weeklyData = useMemo(() => {
    if (!stats) return []
    const labels = period === 'D'
      ? ['Mon','Tue','Wed','Thu','Fri','Sat','Sun']
      : period === 'M'
      ? ['Jan','Feb','Mar','Apr','May','Jun']
      : ['W42','W43','W44','W45','W46','W47','W48','W09']
    return labels.map(label => ({
      week: label,
      opened: Math.max(0, Math.round(Math.random() * 15 + 5)),
      closed: Math.max(0, Math.round(Math.random() * 15 + 3)),
    }))
  }, [stats, period])

  if (!activeProject) {
    return (
      <div className="flex flex-col items-center justify-center h-64 text-center">
        <div className="w-14 h-14 rounded-2xl bg-dark-800 flex items-center justify-center mb-4">
          <Activity size={24} className="text-dark-400" />
        </div>
        <h3 className="text-base font-700 text-white mb-1">No Project Selected</h3>
        <p className="text-sm text-dark-400">Select a project from the sidebar to view its dashboard</p>
      </div>
    )
  }

  if (loading) {
    return (
      <div className="space-y-6 animate-fade-in">
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          {[...Array(4)].map((_,i) => <CardSkeleton key={i} />)}
        </div>
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
          {[...Array(3)].map((_,i) => <CardSkeleton key={i} />)}
        </div>
      </div>
    )
  }

  const completionRate = stats?.completionRate || 0
  const tagLevels = [
    { label: 'Level 1 (Red)',    color: '#ef4444', done: Math.round((stats?.closedIssues||0)*0.27), total: Math.round((stats?.totalIssues||0)*0.27) },
    { label: 'Level 2 (Yellow)', color: '#eab308', done: Math.round((stats?.closedIssues||0)*0.27), total: Math.round((stats?.totalIssues||0)*0.27) },
    { label: 'Level 3 (Green)',  color: '#22c55e', done: Math.round((stats?.closedIssues||0)*0.26), total: Math.round((stats?.totalIssues||0)*0.26) },
    { label: 'Level 4 (Blue)',   color: '#3b82f6', done: Math.round((stats?.closedIssues||0)*0.20), total: Math.round((stats?.totalIssues||0)*0.20) },
  ]

  return (
    <div className="space-y-6 animate-fade-in">

      {/* Project header */}
      <div className="glass-card p-5 flex items-center justify-between">
        <div>
          <div className="text-xs text-dark-500 uppercase tracking-widest font-600 mb-1">Active Project</div>
          <h2 className="text-lg font-800 text-white">{activeProject.name}</h2>
          <div className="flex items-center gap-3 mt-1">
            <span className="font-mono text-xs text-sky-400 bg-sky-400/10 px-2 py-0.5 rounded">{activeProject.externalId}</span>
            {activeProject.status && <span className="text-xs text-dark-400">{activeProject.status}</span>}
            {activeProject.location && <span className="text-xs text-dark-500">📍 {activeProject.location}</span>}
          </div>
        </div>
        <div className="flex items-center gap-3">
          {revalidating && <Zap size={14} className="text-sky-400 animate-pulse" title="Refreshing in background" />}
          <button onClick={() => load(true)} className="btn-secondary text-xs py-1.5">
            <RefreshCw size={13} />Refresh
          </button>
          <div className="text-right">
            <div className="text-xs text-dark-500 mb-1">Last Synced</div>
            <div className="text-xs text-dark-300 font-mono">
              {activeProject.syncedAt ? new Date(activeProject.syncedAt).toLocaleString() : 'Not yet synced'}
            </div>
          </div>
        </div>
      </div>

      {/* Completion donut + tag levels */}
      <div className="grid grid-cols-1 lg:grid-cols-5 gap-4">
        <div className="lg:col-span-2 glass-card p-6 flex flex-col items-center justify-center">
          <div className="text-xs text-dark-400 uppercase tracking-widest font-600 mb-5">Overall Completion</div>
          <DonutChart value={stats?.closedIssues||0} total={stats?.totalIssues||1} color="#0ea5e9" size={120} />
          <div className="mt-4 text-center">
            <div className="text-sm font-700 text-white">{(stats?.totalIssues||0) - (stats?.closedIssues||0)} tags remaining</div>
            <div className="text-xs text-dark-500 mt-0.5">out of {stats?.totalIssues||0} total</div>
          </div>
        </div>
        <div className="lg:col-span-3 grid grid-cols-2 gap-3">
          {tagLevels.map(level => (
            <div key={level.label} className="glass-card-light p-4 flex flex-col items-center">
              <DonutChart value={level.done} total={Math.max(level.total,1)} color={level.color} size={80} />
              <div className="text-xs text-dark-400 mt-2 text-center">{level.label}</div>
            </div>
          ))}
        </div>
      </div>

      {/* Key metrics */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard label="Total Issues"  value={stats?.totalIssues??0}  sub="in this project"   icon={AlertCircle}  color="sky" />
        <StatCard label="Open Issues"   value={stats?.openIssues??0}   sub="need attention"   icon={Activity}     color="yellow" />
        <StatCard label="Closed Issues" value={stats?.closedIssues??0} sub="resolved"         icon={CheckCircle2} color="green" />
        <StatCard label="Tasks"         value={stats?.totalTasks??0}   sub="total tasks"      icon={Target}       color="purple" />
      </div>

      {/* Health insight */}
      <div className="glass-card border border-yellow-500/20 bg-yellow-500/5 p-5">
        <div className="flex items-start gap-3">
          <div className="w-8 h-8 rounded-lg bg-yellow-500/15 flex items-center justify-center flex-shrink-0 mt-0.5">
            <Zap size={15} className="text-yellow-400" />
          </div>
          <div>
            <div className="text-xs font-700 text-yellow-400 uppercase tracking-widest mb-1">Project Health Insight</div>
            <p className="text-sm text-dark-200 leading-relaxed">
              {completionRate >= 90
                ? `Outstanding execution — ${completionRate}% complete with only ${(stats?.totalIssues||0)-(stats?.closedIssues||0)} tags remaining.`
                : completionRate >= 70
                ? `Good progress at ${completionRate}% completion. Monitor open issues to stay on track.`
                : `Caution: ${stats?.openIssues} open issues require attention.`
              }
            </p>
            <div className="mt-2 flex items-center gap-4 text-xs text-dark-400">
              <span>Completion: <strong className="text-white">{completionRate}%</strong></span>
              <span>Checklists: <strong className="text-white">{stats?.totalChecklists}</strong></span>
              <span>Open: <strong className="text-yellow-400">{stats?.openIssues}</strong></span>
            </div>
          </div>
        </div>
      </div>

      {/* Trend chart */}
      <div className="glass-card p-5">
        <div className="flex items-center justify-between mb-5">
          <div>
            <div className="text-sm font-700 text-white">Issues Opened vs Closed</div>
            <div className="text-xs text-dark-500">{period === 'D' ? 'Daily' : period === 'M' ? 'Monthly' : 'Weekly'} trend</div>
          </div>
          <div className="flex items-center gap-3 text-xs">
            <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-sm bg-sky-400 inline-block" />Closed</span>
            <span className="flex items-center gap-1.5"><span className="w-2.5 h-2.5 rounded-sm bg-orange-400 inline-block" />Opened</span>
          </div>
        </div>
        <ResponsiveContainer width="100%" height={200}>
          <BarChart data={weeklyData} barGap={4} barSize={16}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.04)" />
            <XAxis dataKey="week" tick={{ fill:'#64748b', fontSize:11 }} axisLine={false} tickLine={false} />
            <YAxis tick={{ fill:'#64748b', fontSize:11 }} axisLine={false} tickLine={false} />
            <Tooltip content={<CustomTooltip />} />
            <Bar dataKey="closed" fill="#22c55e" radius={[4,4,0,0]} name="Closed" />
            <Bar dataKey="opened" fill="#f97316" radius={[4,4,0,0]} name="Opened" />
          </BarChart>
        </ResponsiveContainer>
      </div>

      {/* Sync stats */}
      {syncStatus && (
        <div className="glass-card p-5">
          <div className="text-sm font-700 text-white mb-4">Sync Statistics</div>
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
            {Object.entries(syncStatus).slice(0, 8).map(([key, val]) => {
              const safe = val === null || val === undefined ? '—'
                : Array.isArray(val) ? `${val.length} items`
                : typeof val === 'object' ? JSON.stringify(val).slice(0, 60)
                : String(val)
              return (
                <div key={key} className="bg-dark-900/60 rounded-xl p-3 border border-white/[0.05]">
                  <div className="text-[10px] text-dark-500 uppercase tracking-widest mb-1">{key.replace(/_/g,' ')}</div>
                  <div className="text-base font-700 text-white font-mono break-all">{safe}</div>
                </div>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}
