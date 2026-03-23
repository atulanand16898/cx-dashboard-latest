import React, { useState, useEffect, useRef } from 'react'
import { RefreshCw, Play, Activity, CheckCircle2, AlertCircle, Clock, Database, Search, ChevronRight, Zap } from 'lucide-react'
import { syncApi, projectsApi, briefsApi, checklistsApi } from '../services/api'
import { SyncResultCard, Skeleton, StatCard, TabSwitcher } from '../components/ui'
import { useProject } from '../context/ProjectContext'
import toast from 'react-hot-toast'

const SYNC_TYPES = [
  { key: 'issues',    label: 'Issues',    color: 'text-red-400' },
  { key: 'tasks',     label: 'Tasks',     color: 'text-yellow-400' },
  { key: 'checklists',label: 'Checklists',color: 'text-green-400' },
  { key: 'equipment', label: 'Equipment', color: 'text-orange-400' },
  { key: 'persons',   label: 'Persons',   color: 'text-blue-400' },
  { key: 'companies', label: 'Companies', color: 'text-purple-400' },
  { key: 'roles',     label: 'Roles',     color: 'text-sky-400' },
  { key: 'assets',    label: 'Assets',    color: 'text-teal-400' },
  { key: 'files',     label: 'Files',     color: 'text-pink-400' },
]

export default function SyncPage() {
  const { activeProject } = useProject()
  const [stats, setStats] = useState(null)
  const [status, setStatus] = useState(null)
  const [statsLoading, setStatsLoading] = useState(true)
  const [syncingAll, setSyncingAll] = useState(false)
  const [results, setResults] = useState({})
  const [syncing, setSyncing] = useState({})
  const [tab, setTab] = useState('individual')
  const [briefsGenerating, setBriefsGenerating] = useState(false)
  const [briefsResult, setBriefsResult] = useState(null)
  const [discoverProjectId, setDiscoverProjectId] = useState('')
  const [discoverResults, setDiscoverResults] = useState(null)
  const [discovering, setDiscovering] = useState(false)
  const [rawEndpoint, setRawEndpoint] = useState('/issue')
  const [rawProjectId, setRawProjectId] = useState('')
  const [rawResult, setRawResult] = useState(null)
  const [rawLoading, setRawLoading] = useState(false)
  const pollRef = useRef(null)

  const loadStats = async () => {
    try {
      const [statsRes, statusRes] = await Promise.all([syncApi.getStats(), syncApi.getStatus()])
      setStats(statsRes.data.data)
      setStatus(statusRes.data.data)
    } catch {}
    finally { setStatsLoading(false) }
  }

  useEffect(() => {
    loadStats()
    return () => clearInterval(pollRef.current)
  }, [])

  const startFullSync = async () => {
    setSyncingAll(true)
    try {
      await syncApi.syncAll()
      toast.success('Full sync started — polling status...')
      pollRef.current = setInterval(async () => {
        const res = await syncApi.getStatus()
        setStatus(res.data.data)
        const s = res.data.data
        if (!s?.running) {
          clearInterval(pollRef.current)
          setSyncingAll(false)
          loadStats()
          toast.success('Full sync complete!')
        }
      }, 2000)
    } catch (err) {
      toast.error(err.response?.data?.message || 'Sync failed')
      setSyncingAll(false)
    }
  }

  const syncType = async (type) => {
    setSyncing(s => ({ ...s, [type]: true }))
    setResults(r => ({ ...r, [type]: null }))
    try {
      const projectIds = activeProject ? [activeProject.externalId] : undefined
      const res = type === 'checklists'
        ? await checklistsApi.syncWithStatusDates(activeProject?.externalId)
        : await syncApi[`sync${type.charAt(0).toUpperCase() + type.slice(1)}`](projectIds)
      setResults(r => ({ ...r, [type]: res.data.data }))
      toast.success(`${type} synced!`)
    } catch (err) {
      toast.error(`${type} sync failed`)
    } finally {
      setSyncing(s => ({ ...s, [type]: false }))
    }
  }

  const syncProject = async () => {
    if (!activeProject) return toast.error('Select a project first')
    setSyncing(s => ({ ...s, _project: true }))
    try {
      const res = await syncApi.syncProject(activeProject.externalId)
      setResults(r => ({ ...r, _project: res.data.data }))
      toast.success('Project synced!')
    } catch (err) {
      toast.error('Project sync failed')
    } finally {
      setSyncing(s => ({ ...s, _project: false }))
    }
  }

  // Generate tracker brief snapshots from DB data for current project
  const handleGenerateBriefs = async () => {
    if (!activeProject) return toast.error('Select a project first')
    setBriefsGenerating(true)
    setBriefsResult(null)
    try {
      const allPeriods = ['Overall', 'W', 'M', 'D']
      const responses = await Promise.all(allPeriods.map(p => briefsApi.generate(activeProject.externalId, p).catch(() => null)))
      const res = responses.find(r => r?.data?.data) || responses[0]
      setBriefsResult(res.data?.data)
      toast.success('Tracker brief snapshots generated (4 periods)')
    } catch (err) {
      toast.error('Brief generation failed — sync checklists & issues first')
      console.error(err)
    } finally {
      setBriefsGenerating(false)
    }
  }

  const handleDiscover = async () => {
    if (!discoverProjectId.trim()) return toast.error('Enter a project ID')
    setDiscovering(true)
    setDiscoverResults(null)
    try {
      const res = await syncApi.discover(discoverProjectId)
      setDiscoverResults(res.data.data)
    } catch { toast.error('Discovery failed') }
    finally { setDiscovering(false) }
  }

  const handleRawPreview = async () => {
    setRawLoading(true)
    setRawResult(null)
    try {
      const res = await syncApi.rawPreview(rawEndpoint, rawProjectId || undefined)
      setRawResult(res.data.data)
    } catch { toast.error('Raw preview failed') }
    finally { setRawLoading(false) }
  }

  const isRunning = status?.running
  const progress = status?.progressPercent || 0

  return (
    <div className="space-y-6 animate-fade-in">
      {/* DB Stats */}
      {statsLoading ? (
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">{[...Array(4)].map((_, i) => <Skeleton key={i} className="h-24 rounded-xl" />)}</div>
      ) : stats && (
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          {Object.entries(stats).slice(0, 8).map(([key, val]) => {
            // Render arrays as a count + collapsible list; objects as JSON; primitives as-is
            let display
            if (val === null || val === undefined) {
              display = '—'
            } else if (Array.isArray(val)) {
              display = null // rendered specially below
            } else if (typeof val === 'object') {
              display = JSON.stringify(val)
            } else {
              display = String(val)
            }

            return (
              <div key={key} className="metric-card p-4">
                <div className="text-[10px] text-dark-400 uppercase tracking-widest mb-2 truncate">{key.replace(/_/g,' ').replace(/([A-Z])/g,' $1').trim()}</div>
                {Array.isArray(val) ? (
                  val.length === 0 ? (
                    <div className="text-sm text-dark-500 font-mono">empty</div>
                  ) : (
                    <>
                      <div className="text-2xl font-800 text-white font-mono mb-2">{val.length}</div>
                      <div className="space-y-1 max-h-32 overflow-y-auto">
                        {val.slice(0, 6).map((item, i) => (
                          <div key={i} className="text-[10px] text-dark-400 font-mono truncate bg-white/[0.03] rounded px-2 py-1">
                            {typeof item === 'object' && item !== null
                              ? (item.message || item.level || item.type || item.status || JSON.stringify(item).slice(0, 60))
                              : String(item)}
                          </div>
                        ))}
                        {val.length > 6 && <div className="text-[10px] text-dark-500 px-2">+{val.length - 6} more…</div>}
                      </div>
                    </>
                  )
                ) : (
                  <div className="text-2xl font-800 text-white font-mono break-all">{display}</div>
                )}
              </div>
            )
          })}
        </div>
      )}

      {/* Background sync status */}
      {isRunning && (
        <div className="glass-card border border-sky-500/20 bg-sky-500/5 p-5">
          <div className="flex items-center justify-between mb-3">
            <div className="flex items-center gap-2 text-sky-400 font-600 text-sm">
              <Activity size={16} className="animate-pulse" />
              Full Sync Running...
            </div>
            <span className="text-xs text-dark-400 font-mono">{Math.round(progress)}%</span>
          </div>
          <div className="w-full bg-dark-800 rounded-full h-1.5">
            <div className="bg-gradient-to-r from-sky-400 to-blue-500 h-1.5 rounded-full transition-all duration-300" style={{ width: `${progress}%` }} />
          </div>
          {status?.currentItem && (
            <div className="text-xs text-dark-400 mt-2 font-mono">{status.currentItem}</div>
          )}
        </div>
      )}

      {/* Tabs */}
      <TabSwitcher
        tabs={[
          { label: 'Individual Sync', value: 'individual' },
          { label: 'Full Sync', value: 'full' },
          { label: 'Diagnostics', value: 'diagnostics' },
        ]}
        active={tab}
        onChange={setTab}
      />

      {tab === 'individual' && (
        <div className="space-y-4">
          {/* Per-project sync */}
          {activeProject && (
            <div className="glass-card p-5">
              <div className="flex items-center justify-between mb-3">
                <div>
                  <div className="text-sm font-700 text-white">Deep Sync: {activeProject.name}</div>
                  <div className="text-xs text-dark-400 font-mono">{activeProject.externalId}</div>
                </div>
                <button onClick={syncProject} disabled={syncing._project} className="btn-primary">
                  <RefreshCw size={14} className={syncing._project ? 'animate-spin' : ''} />
                  Sync All Data Types
                </button>
              </div>
              {results._project && <SyncResultCard result={results._project} />}
            </div>
          )}

          {/* Per-type syncs */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            {SYNC_TYPES.map(({ key, label, color }) => (
              <div key={key} className="glass-card-light p-4 flex items-center justify-between">
                <div>
                  <div className={`text-sm font-600 ${color}`}>{label}</div>
                  <div className="text-xs text-dark-500">
                    {activeProject ? `Filter: ${activeProject.name}` : 'All projects'}
                  </div>
                  {results[key] && (
                    <div className="text-xs text-green-400 mt-1 font-mono">
                      ✓ {results[key].recordsSynced ?? results[key].totalRecordsSynced ?? 0} records synced
                    </div>
                  )}
                </div>
                <button onClick={() => syncType(key)} disabled={syncing[key]} className="btn-secondary text-xs py-1.5">
                  <RefreshCw size={12} className={syncing[key] ? 'animate-spin' : ''} />
                  {syncing[key] ? 'Syncing...' : 'Sync'}
                </button>
              </div>
            ))}
          </div>

          {/* Tracker Briefs — generates snapshot from DB (not a CxAlloy sync) */}
          <div style={{
            background: 'var(--bg-card-light)', border: '1px solid rgba(34,197,94,0.20)',
            borderRadius: 12, padding: '16px 18px',
          }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                  <Zap size={14} style={{ color: '#22c55e' }} />
                  <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' }}>Tracker Briefs</span>
                  <span style={{
                    fontSize: 10, fontWeight: 600, color: '#22c55e',
                    background: 'rgba(34,197,94,0.12)', padding: '1px 8px', borderRadius: 4,
                  }}>DB Snapshot</span>
                </div>
                <div style={{ fontSize: 12, color: 'var(--text-muted)', lineHeight: 1.5 }}>
                  Generates a brief snapshot from synced checklist + issue counts in the local DB.
                  <br />
                  <span style={{ color: '#f59e0b' }}>⚠ Sync checklists &amp; issues first before generating.</span>
                </div>
                {briefsResult && (
                  <div style={{ fontSize: 12, color: '#22c55e', marginTop: 6, fontFamily: 'monospace' }}>
                    ✓ Snapshot saved — {briefsResult.items} checklists · {briefsResult.issues} issues · {briefsResult.period}
                  </div>
                )}
              </div>
              <button
                onClick={handleGenerateBriefs}
                disabled={briefsGenerating || !activeProject}
                style={{
                  display: 'flex', alignItems: 'center', gap: 6,
                  padding: '8px 16px',
                  background: briefsGenerating ? 'rgba(34,197,94,0.10)' : 'rgba(34,197,94,0.12)',
                  border: '1px solid rgba(34,197,94,0.25)',
                  borderRadius: 8, fontSize: 12, fontWeight: 600,
                  color: '#22c55e',
                  cursor: (!activeProject || briefsGenerating) ? 'not-allowed' : 'pointer',
                  opacity: !activeProject ? 0.5 : 1,
                  flexShrink: 0, marginLeft: 20,
                  transition: 'all 0.15s',
                }}
              >
                {briefsGenerating
                  ? <><RefreshCw size={12} style={{ animation: 'spin 1s linear infinite' }} />Generating…</>
                  : <><Zap size={12} />Generate</>}
              </button>
            </div>
          </div>
        </div>
      )}

      {tab === 'full' && (
        <div className="glass-card p-6">
          <div className="text-sm font-700 text-white mb-1">Full Background Sync</div>
          <p className="text-xs text-dark-400 mb-5">
            Launches a background sync across ALL projects for all data types.
            Returns immediately — poll status to track progress.
          </p>
          <div className="flex items-center gap-3">
            <button
              onClick={startFullSync}
              disabled={syncingAll || isRunning}
              className="btn-primary"
            >
              <Play size={14} />
              {syncingAll || isRunning ? 'Running...' : 'Start Full Sync'}
            </button>
            <button onClick={loadStats} className="btn-secondary">
              <RefreshCw size={14} />
              Refresh Stats
            </button>
          </div>

          {status && (
            <div className="mt-5 grid grid-cols-2 lg:grid-cols-4 gap-3">
              {Object.entries(status).map(([k, v]) => {
                const safeVal = v === null || v === undefined ? '—'
                  : Array.isArray(v) ? `${v.length} items`
                  : typeof v === 'object' ? JSON.stringify(v).slice(0, 80)
                  : String(v)
                return (
                  <div key={k} className="bg-dark-900/60 rounded-xl p-3 border border-white/[0.05]">
                    <div className="text-[10px] text-dark-500 uppercase tracking-widest mb-1">{k.replace(/([A-Z])/g, ' $1').trim()}</div>
                    <div className="text-sm font-700 text-white font-mono truncate">{safeVal}</div>
                  </div>
                )
              })}
            </div>
          )}
        </div>
      )}

      {tab === 'diagnostics' && (
        <div className="space-y-4">
          {/* Raw preview */}
          <div className="glass-card p-5">
            <div className="text-sm font-700 text-white mb-1">Raw API Preview</div>
            <p className="text-xs text-dark-500 mb-4">Hit CxAlloy directly. No DB writes.</p>
            <div className="flex gap-2 mb-3">
              <input
                className="input-field"
                value={rawEndpoint}
                onChange={e => setRawEndpoint(e.target.value)}
                placeholder="/issue"
              />
              <input
                className="input-field"
                value={rawProjectId}
                onChange={e => setRawProjectId(e.target.value)}
                placeholder="project_id (optional)"
              />
              <button onClick={handleRawPreview} disabled={rawLoading} className="btn-primary whitespace-nowrap">
                <ChevronRight size={14} />
                Preview
              </button>
            </div>
            {rawResult && (
              <pre className="text-xs text-dark-300 font-mono bg-dark-950 rounded-xl p-4 max-h-64 overflow-auto border border-white/[0.06] whitespace-pre-wrap break-all">
                {rawResult}
              </pre>
            )}
          </div>

          {/* Discover */}
          <div className="glass-card p-5">
            <div className="text-sm font-700 text-white mb-1">Endpoint Discovery</div>
            <p className="text-xs text-dark-500 mb-4">Probe all known CxAlloy endpoints for a project.</p>
            <div className="flex gap-2 mb-3">
              <input
                className="input-field"
                value={discoverProjectId}
                onChange={e => setDiscoverProjectId(e.target.value)}
                placeholder="project_id"
              />
              <button onClick={handleDiscover} disabled={discovering} className="btn-primary whitespace-nowrap">
                <Search size={14} />
                {discovering ? 'Discovering...' : 'Discover'}
              </button>
            </div>
            {discoverResults && (
              <div className="space-y-1.5 max-h-96 overflow-y-auto">
                {discoverResults.map((row, i) => (
                  <div key={i} className={`flex items-center gap-3 p-2.5 rounded-lg border ${
                    row.status === 'SUCCESS'
                      ? 'border-green-500/20 bg-green-500/5'
                      : 'border-red-500/20 bg-red-500/5'
                  }`}>
                    {row.status === 'SUCCESS'
                      ? <CheckCircle2 size={14} className="text-green-400 flex-shrink-0" />
                      : <AlertCircle size={14} className="text-red-400 flex-shrink-0" />
                    }
                    <span className="font-mono text-xs text-dark-300 w-24 flex-shrink-0">{row.endpoint}</span>
                    <span className={`text-xs font-600 w-16 ${row.status === 'SUCCESS' ? 'text-green-400' : 'text-red-400'}`}>{row.status}</span>
                    <span className="text-xs text-dark-500 truncate">{row.preview || row.error}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
