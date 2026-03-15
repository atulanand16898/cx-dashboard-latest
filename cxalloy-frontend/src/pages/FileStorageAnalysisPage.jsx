import React, { useState, useCallback, useMemo, useRef, useEffect } from 'react'
import {
  HardDrive, RefreshCw, FileText, Copy, Trash2, FolderSearch,
  BarChart2, Download, AlertTriangle, CheckCircle2, XCircle,
  ChevronDown, ChevronUp, Search, Filter, FileType, Archive,
  Film, Image, File, Database, Clock, Zap
} from 'lucide-react'
import {
  PieChart, Pie, Cell, Tooltip, ResponsiveContainer,
  BarChart, Bar, XAxis, YAxis, CartesianGrid
} from 'recharts'
import { useProject } from '../context/ProjectContext'
import { fileStorageApi } from '../services/api'
import toast from 'react-hot-toast'

// ── Cache TTL: 10 minutes (matches backend) ────────────────────────────────────
const CACHE_TTL_MS = 10 * 60 * 1000

// ── Helpers ────────────────────────────────────────────────────────────────────
function formatBytes(bytes) {
  if (!bytes || bytes === 0) return '0 B'
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return `${(bytes / Math.pow(1024, i)).toFixed(2)} ${sizes[i]}`
}

function getFileIcon(ext) {
  const e = (ext || '').toUpperCase()
  if (e === 'PDF') return FileText
  if (['HEIC', 'JPG', 'JPEG', 'PNG', 'GIF', 'WEBP'].includes(e)) return Image
  if (['MOV', 'MP4', 'AVI'].includes(e)) return Film
  if (['ZIP', 'RAR', '7Z'].includes(e)) return Archive
  return File
}

const TYPE_COLORS = {
  PDF: '#3b82f6', HEIC: '#22c55e', MOV: '#f97316', JPG: '#eab308',
  ZIP: '#a855f7', PNG: '#ec4899', MSG: '#06b6d4', MP4: '#f97316',
  PPTX: '#ff6b35', XLSX: '#10b981', TXT: '#94a3b8', DOC: '#6366f1', OTHER: '#64748b',
}

function getTypeColor(ext) {
  return TYPE_COLORS[(ext || '').toUpperCase()] || TYPE_COLORS.OTHER
}

// ── Sub-components ─────────────────────────────────────────────────────────────
function SummaryCard({ label, value, sub, icon: Icon, accent, trend }) {
  const accentMap = {
    blue:  { card: 'rgba(14,165,233,0.08)',  border: 'rgba(14,165,233,0.2)',  text: '#38bdf8' },
    sky:   { card: 'rgba(6,182,212,0.08)',   border: 'rgba(6,182,212,0.2)',   text: '#22d3ee' },
    teal:  { card: 'rgba(20,184,166,0.08)',  border: 'rgba(20,184,166,0.2)',  text: '#2dd4bf' },
    amber: { card: 'rgba(245,158,11,0.08)',  border: 'rgba(245,158,11,0.2)',  text: '#fbbf24' },
  }
  const a = accentMap[accent] || accentMap.blue
  return (
    <div style={{ background: a.card, border: `1px solid ${a.border}`, borderRadius: 14, padding: '18px 20px' }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 10 }}>
        <div style={{ fontSize: 10, fontWeight: 700, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.08em' }}>{label}</div>
        {Icon && (
          <div style={{ width: 32, height: 32, borderRadius: 10, background: `${a.text}18`, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Icon size={15} style={{ color: a.text }} />
          </div>
        )}
      </div>
      <div style={{ fontSize: 30, fontWeight: 800, color: '#f8fafc', letterSpacing: '-0.02em', marginBottom: 4 }}>{value}</div>
      <div style={{ fontSize: 11, color: '#64748b' }}>{sub}</div>
    </div>
  )
}

const CustomPieTooltip = ({ active, payload }) => {
  if (!active || !payload?.length) return null
  return (
    <div style={{ background: '#1e293b', border: '1px solid rgba(255,255,255,0.1)', borderRadius: 8, padding: '8px 12px', fontSize: 12 }}>
      <div style={{ fontWeight: 700, color: '#f8fafc' }}>{payload[0].name}</div>
      <div style={{ color: '#94a3b8' }}>{formatBytes(payload[0].value)}</div>
    </div>
  )
}

const CustomBarTooltip = ({ active, payload, label }) => {
  if (!active || !payload?.length) return null
  return (
    <div style={{ background: '#1e293b', border: '1px solid rgba(255,255,255,0.1)', borderRadius: 8, padding: '8px 12px', fontSize: 12 }}>
      <div style={{ color: '#94a3b8', marginBottom: 2, maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{label}</div>
      <div style={{ color: '#38bdf8', fontWeight: 700 }}>{formatBytes(payload[0]?.value)}</div>
    </div>
  )
}

function CacheIndicator({ servedFrom, analyzedAt, cacheAge }) {
  const fromCache = servedFrom === 'cache' || (cacheAge !== null && cacheAge < CACHE_TTL_MS)
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 10, color: fromCache ? '#22d3ee' : '#94a3b8' }}>
      {fromCache ? <Zap size={11} /> : <Database size={11} />}
      <span>{fromCache ? 'Served from cache' : 'Live from DB'}</span>
      {analyzedAt && (
        <span style={{ color: '#475569' }}>
          · {new Date(analyzedAt).toLocaleTimeString()}
        </span>
      )}
    </div>
  )
}

// ── Main Page ──────────────────────────────────────────────────────────────────
export default function FileStorageAnalysisPage() {
  const { activeProject } = useProject()
  const [status, setStatus] = useState('idle') // idle | loading | syncing | done | error
  const [result, setResult] = useState(null)
  const [search, setSearch] = useState('')
  const [sortCol, setSortCol] = useState('sizeBytes')
  const [sortDir, setSortDir] = useState('desc')
  const [filterExt, setFilterExt] = useState('ALL')
  const [exporting, setExporting] = useState(false)
  const [cacheAge, setCacheAge] = useState(null)

  // ── Frontend in-memory cache (keyed by projectId, TTL 10 min) ───────────────
  const cache = useRef({})

  const getCached = (pid) => {
    const entry = cache.current[pid]
    if (!entry) return null
    if (Date.now() - entry.ts > CACHE_TTL_MS) {
      delete cache.current[pid]
      return null
    }
    return entry
  }

  const setCache = (pid, data) => {
    cache.current[pid] = { data, ts: Date.now() }
  }

  // ── Auto-load report when project changes ─────────────────────────────────
  useEffect(() => {
    if (!activeProject) return
    const pid = activeProject.externalId
    const cached = getCached(pid)
    if (cached) {
      setResult(cached.data)
      setCacheAge(Date.now() - cached.ts)
      setStatus('done')
      return
    }
    // Auto-load from DB (fast — does NOT hit CxAlloy unless DB is empty)
    loadReport(pid)
  }, [activeProject?.externalId])

  const loadReport = async (pid) => {
    setStatus('loading')
    try {
      const res = await fileStorageApi.getReport(pid)
      const data = res.data?.data || {}
      setResult(data)
      setCache(pid, data)
      setCacheAge(0)
      setStatus('done')
    } catch (err) {
      setStatus('error')
      toast.error(err?.response?.data?.message || 'Failed to load report')
    }
  }

  // ── Analyze: evict backend cache → re-analyze from DB ────────────────────
  const handleAnalyze = useCallback(async () => {
    if (!activeProject) return toast.error('Select a project first')
    const pid = activeProject.externalId
    delete cache.current[pid]    // Bust frontend cache
    setStatus('loading')
    setResult(null)
    const tid = toast.loading('Analyzing files from database…')
    try {
      const res = await fileStorageApi.analyze(pid)
      const data = res.data?.data || {}
      setResult(data)
      setCache(pid, data)
      setCacheAge(0)
      setStatus('done')
      toast.success(`Analysis complete! Found ${data.dupGroups ?? 0} duplicate groups.`, { id: tid })
    } catch (err) {
      setStatus('error')
      toast.error(err?.response?.data?.message || 'Analysis failed', { id: tid })
    }
  }, [activeProject])

  // ── Sync: fetch from CxAlloy → DB → re-analyze ────────────────────────────
  const handleSync = useCallback(async () => {
    if (!activeProject) return toast.error('Select a project first')
    const pid = activeProject.externalId
    delete cache.current[pid]
    setStatus('syncing')
    setResult(null)
    const tid = toast.loading('Syncing files from CxAlloy…')
    try {
      const res = await fileStorageApi.sync(pid)
      const data = res.data?.data || {}
      const synced = data.syncResult?.totalSynced ?? '?'
      setResult(data)
      setCache(pid, data)
      setCacheAge(0)
      setStatus('done')
      toast.success(`Synced ${synced} files from CxAlloy!`, { id: tid })
    } catch (err) {
      setStatus('error')
      toast.error(err?.response?.data?.message || 'Sync failed', { id: tid })
    }
  }, [activeProject])

  // ── Export PDF ───────────────────────────────────────────────────────────────
  const handleExportPDF = useCallback(async () => {
    if (!result || !activeProject) return
    setExporting(true)
    try {
      const res = await fileStorageApi.exportPdf(activeProject.externalId)
      const url = URL.createObjectURL(new Blob([res.data]))
      const a = document.createElement('a')
      a.href = url; a.download = `file-analysis-${activeProject.externalId}.json`
      a.click(); URL.revokeObjectURL(url)
      toast.success('Export downloaded')
    } catch { toast.error('Export failed') }
    finally { setExporting(false) }
  }, [result, activeProject])

  // ── Download JSON ──────────────────────────────────────────────────────────
  const handleDownloadJSON = useCallback(async () => {
    if (!result) return
    if (activeProject) {
      try {
        const res = await fileStorageApi.downloadJson(activeProject.externalId)
        const url = URL.createObjectURL(new Blob([res.data]))
        const a = document.createElement('a')
        a.href = url; a.download = `file-analysis-${activeProject.externalId}.json`
        a.click(); URL.revokeObjectURL(url)
        toast.success('JSON downloaded')
        return
      } catch {}
    }
    // Fallback: download from in-memory result
    const blob = new Blob([JSON.stringify(result, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url; a.download = `file-analysis.json`; a.click()
    URL.revokeObjectURL(url)
    toast.success('JSON downloaded')
  }, [result, activeProject])

  // ── Derived chart data ─────────────────────────────────────────────────────
  const byTypeChartData = useMemo(() => {
    if (!result) return []
    // Prefer byExtensionBytes (real sizes), fall back to byExtension (counts)
    const src = result.byExtensionBytes || result.byExtension || {}
    return Object.entries(src)
      .map(([ext, val]) => ({ ext: ext.toUpperCase(), bytes: Number(val) }))
      .filter(d => d.bytes > 0)
      .sort((a, b) => b.bytes - a.bytes)
      .slice(0, 10)
  }, [result])

  const top5AssetsData = useMemo(() => {
    if (!result) return []
    const src = result.top5Assets || result.heaviestAssets || []
    return src.map(a => ({
      name: (a.name || a.asset_type || 'Asset').substring(0, 22),
      bytes: Number(a.bytes || a.total_size_bytes || 0),
    })).filter(d => d.bytes > 0)
  }, [result])

  // ── Largest files table data ───────────────────────────────────────────────
  const largestFiles = useMemo(() => {
    if (!result?.largestFiles) return []
    return result.largestFiles.map(f => ({
      name: f.name || '—',
      ext: (f.ext || f.mime_type || 'FILE').toUpperCase().split('/').pop().split(';')[0].trim(),
      sizeBytes: Number(f.sizeBytes || f.file_size || 0),
      asset: f.asset_type || '—',
      assetId: f.asset_id || '—',
    }))
  }, [result])

  const displayFiles = useMemo(() => {
    let list = [...largestFiles]
    if (filterExt !== 'ALL') list = list.filter(f => f.ext === filterExt)
    if (search.trim()) list = list.filter(f =>
      f.name.toLowerCase().includes(search.toLowerCase()) ||
      (f.asset || '').toLowerCase().includes(search.toLowerCase())
    )
    return list.sort((a, b) => {
      let av = a[sortCol], bv = b[sortCol]
      if (typeof av === 'string') { av = av.toLowerCase(); bv = bv.toLowerCase() }
      return sortDir === 'asc' ? (av > bv ? 1 : -1) : (av < bv ? 1 : -1)
    })
  }, [largestFiles, search, filterExt, sortCol, sortDir])

  const extOptions = useMemo(() => {
    const exts = [...new Set(largestFiles.map(f => f.ext).filter(Boolean))]
    return ['ALL', ...exts]
  }, [largestFiles])

  const toggleSort = (col) => {
    if (sortCol === col) setSortDir(d => d === 'asc' ? 'desc' : 'asc')
    else { setSortCol(col); setSortDir('desc') }
  }

  // ── No project ────────────────────────────────────────────────────────────────
  if (!activeProject) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: 260, textAlign: 'center' }}>
        <div style={{ width: 56, height: 56, borderRadius: 18, background: 'rgba(255,255,255,0.04)', display: 'flex', alignItems: 'center', justifyContent: 'center', marginBottom: 16 }}>
          <HardDrive size={24} color="#475569" />
        </div>
        <h3 style={{ fontSize: 15, fontWeight: 700, color: '#f8fafc', marginBottom: 6 }}>No Project Selected</h3>
        <p style={{ fontSize: 13, color: '#64748b' }}>Select a project to view file storage analysis</p>
      </div>
    )
  }

  const isLoading = status === 'loading' || status === 'syncing'

  return (
    <div className="space-y-6 animate-fade-in">

      {/* ── Page Header ─────────────────────────────────────────────────── */}
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
        <div>
          <h2 style={{ fontSize: 20, fontWeight: 800, color: '#f8fafc', display: 'flex', alignItems: 'center', gap: 8, margin: 0 }}>
            <HardDrive size={20} color="#38bdf8" />
            File Storage Analysis
          </h2>
          <p style={{ fontSize: 12, color: '#64748b', margin: '4px 0 0 0' }}>
            Analyze file attachments from checklists, detect duplicates, and identify storage optimization opportunities.
          </p>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          {/* Sync from CxAlloy */}
          <button onClick={handleSync} disabled={isLoading}
            style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '8px 14px', borderRadius: 10, background: '#0f4c81', border: '1px solid #1e6bb5', color: '#93c5fd', fontSize: 12, fontWeight: 600, cursor: 'pointer', opacity: isLoading ? 0.5 : 1 }}>
            <Database size={13} />
            {status === 'syncing' ? 'Syncing…' : 'Sync from CxAlloy'}
          </button>
          {/* Analyze from DB */}
          <button onClick={handleAnalyze} disabled={isLoading}
            style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '8px 14px', borderRadius: 10, background: '#065f46', border: '1px solid #059669', color: '#6ee7b7', fontSize: 12, fontWeight: 600, cursor: 'pointer', opacity: isLoading ? 0.5 : 1 }}>
            {isLoading
              ? <><div style={{ width: 13, height: 13, border: '2px solid rgba(110,231,183,0.3)', borderTopColor: '#6ee7b7', borderRadius: '50%', animation: 'spin 0.8s linear infinite' }} />Analyzing…</>
              : <><FolderSearch size={13} />Analyze Files</>
            }
          </button>
          {/* Refresh (reload from cache/DB) */}
          <button onClick={() => loadReport(activeProject.externalId)} disabled={isLoading}
            style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '8px 12px', borderRadius: 10, background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.1)', color: '#94a3b8', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>
            <RefreshCw size={13} className={isLoading ? 'animate-spin' : ''} />Refresh
          </button>
          <button onClick={handleExportPDF} disabled={!result || exporting}
            style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '8px 12px', borderRadius: 10, background: '#1e40af', border: '1px solid #3b82f6', color: '#93c5fd', fontSize: 12, fontWeight: 600, cursor: 'pointer', opacity: (!result || exporting) ? 0.4 : 1 }}>
            <FileText size={13} />Export PDF
          </button>
          <button onClick={handleDownloadJSON} disabled={!result}
            style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '8px 12px', borderRadius: 10, background: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.1)', color: '#94a3b8', fontSize: 12, fontWeight: 600, cursor: 'pointer', opacity: !result ? 0.4 : 1 }}>
            <Download size={13} />Download JSON
          </button>
        </div>
      </div>

      {/* ── Status Banner ───────────────────────────────────────────────── */}
      {status === 'idle' && (
        <div style={{ padding: '12px 16px', borderRadius: 10, background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.06)', display: 'flex', alignItems: 'center', gap: 10 }}>
          <div style={{ width: 8, height: 8, borderRadius: '50%', background: '#475569' }} />
          <span style={{ fontSize: 13, color: '#64748b' }}>
            Loading report for <strong style={{ color: '#f8fafc' }}>{activeProject.name}</strong>…
          </span>
        </div>
      )}
      {isLoading && (
        <div style={{ padding: '12px 16px', borderRadius: 10, background: 'rgba(56,189,248,0.06)', border: '1px solid rgba(56,189,248,0.2)', display: 'flex', alignItems: 'center', gap: 10 }}>
          <div style={{ width: 16, height: 16, border: '2px solid rgba(56,189,248,0.3)', borderTopColor: '#38bdf8', borderRadius: '50%', animation: 'spin 0.8s linear infinite', flexShrink: 0 }} />
          <span style={{ fontSize: 13, color: '#7dd3fc' }}>
            {status === 'syncing' ? 'Fetching all files from CxAlloy and saving to database…' : 'Analyzing file attachments from database…'}
          </span>
        </div>
      )}
      {status === 'done' && result && (
        <div style={{ padding: '12px 16px', borderRadius: 10, background: 'rgba(34,197,94,0.06)', border: '1px solid rgba(34,197,94,0.2)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <CheckCircle2 size={16} color="#4ade80" />
            <span style={{ fontSize: 13, color: '#86efac' }}>
              Analysis complete! Found <strong style={{ color: '#f8fafc' }}>{result.dupGroups ?? 0}</strong> duplicate groups.
            </span>
          </div>
          <CacheIndicator servedFrom={result.servedFrom} analyzedAt={result.analyzedAt} cacheAge={cacheAge} />
        </div>
      )}
      {status === 'error' && (
        <div style={{ padding: '12px 16px', borderRadius: 10, background: 'rgba(239,68,68,0.06)', border: '1px solid rgba(239,68,68,0.2)', display: 'flex', alignItems: 'center', gap: 10 }}>
          <XCircle size={16} color="#f87171" />
          <span style={{ fontSize: 13, color: '#fca5a5' }}>Analysis failed. Try <strong style={{ color: '#f8fafc' }}>Sync from CxAlloy</strong> first, then Analyze.</span>
        </div>
      )}

      {/* ── Summary Cards ───────────────────────────────────────────────── */}
      {result && (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4,1fr)', gap: 14 }}>
            <SummaryCard label="Files Found"       value={(result.totalFiles || 0).toLocaleString()}         sub={`${(result.checklistsScanned || 0).toLocaleString()} Records Scanned`}  icon={BarChart2}   accent="blue" />
            <SummaryCard label="Total Volume"      value={formatBytes(result.totalSizeBytes || 0)}            sub="Storage Used"                                                           icon={HardDrive}   accent="sky" />
            <SummaryCard label="Project Uniqueness" value={`${result.uniquenessPercent ?? 100}%`}             sub="Unique Content Ratio"                                                   icon={Copy}        accent="teal" />
            <SummaryCard label="Storage Waste"     value={formatBytes(result.wasteBytes || 0)}                sub={`${(result.redundantCopies || 0).toLocaleString()} Redundant Copies`}  icon={Trash2}      accent="amber" />
          </div>

          {/* ── Charts ─────────────────────────────────────────────────── */}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>

            {/* Storage by file type */}
            <div style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.07)', borderRadius: 14, padding: 20 }}>
              <div style={{ fontSize: 10, fontWeight: 700, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 16 }}>
                Storage By File Type
              </div>
              {byTypeChartData.length > 0 ? (
                <div style={{ display: 'flex', alignItems: 'center', gap: 20 }}>
                  <ResponsiveContainer width={180} height={180}>
                    <PieChart>
                      <Pie data={byTypeChartData} dataKey="bytes" nameKey="ext"
                        cx="50%" cy="50%" innerRadius={55} outerRadius={85} paddingAngle={2}>
                        {byTypeChartData.map(entry => (
                          <Cell key={entry.ext} fill={getTypeColor(entry.ext)} />
                        ))}
                      </Pie>
                      <Tooltip content={<CustomPieTooltip />} />
                    </PieChart>
                  </ResponsiveContainer>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                    {byTypeChartData.slice(0, 8).map(({ ext, bytes }) => (
                      <div key={ext} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11 }}>
                        <span style={{ width: 10, height: 10, borderRadius: 3, background: getTypeColor(ext), flexShrink: 0 }} />
                        <span style={{ color: '#94a3b8', width: 40 }}>{ext}</span>
                        <span style={{ color: '#64748b' }}>{formatBytes(bytes)}</span>
                      </div>
                    ))}
                  </div>
                </div>
              ) : (
                /* Fallback: extension list when no size data */
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {Object.entries(result.byExtension || {}).slice(0, 10).map(([ext, count]) => (
                    <div key={ext} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', fontSize: 12 }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <span style={{ width: 8, height: 8, borderRadius: 2, background: getTypeColor(ext), flexShrink: 0 }} />
                        <span style={{ color: '#94a3b8' }}>{ext.toUpperCase()}</span>
                      </div>
                      <span style={{ color: '#475569', fontVariantNumeric: 'tabular-nums' }}>{Number(count).toLocaleString()} files</span>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* Heaviest Assets */}
            <div style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.07)', borderRadius: 14, padding: 20 }}>
              <div style={{ fontSize: 10, fontWeight: 700, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 16 }}>
                Heaviest Assets (Top 5)
              </div>
              {top5AssetsData.length > 0 ? (
                <ResponsiveContainer width="100%" height={180}>
                  <BarChart data={top5AssetsData} layout="vertical" barSize={12}>
                    <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.04)" horizontal={false} />
                    <XAxis type="number" tickFormatter={v => formatBytes(v)} tick={{ fill: '#64748b', fontSize: 10 }} axisLine={false} tickLine={false} />
                    <YAxis type="category" dataKey="name" tick={{ fill: '#94a3b8', fontSize: 10 }} axisLine={false} tickLine={false} width={110} />
                    <Tooltip content={<CustomBarTooltip />} />
                    <Bar dataKey="bytes" fill="#3b82f6" radius={[0, 4, 4, 0]} name="Size" />
                  </BarChart>
                </ResponsiveContainer>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: 160, color: '#475569', fontSize: 12 }}>
                  No asset size data available
                </div>
              )}
            </div>
          </div>

          {/* ── Largest Files Table ─────────────────────────────────────── */}
          <div style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid rgba(255,255,255,0.07)', borderRadius: 14, padding: 20 }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16, flexWrap: 'wrap', gap: 10 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <FileType size={14} color="#38bdf8" />
                <span style={{ fontSize: 14, fontWeight: 700, color: '#f8fafc' }}>Largest Files (Top 10)</span>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                {/* Ext filter */}
                <div style={{ display: 'flex', gap: 4, padding: 4, background: 'rgba(0,0,0,0.3)', borderRadius: 10, border: '1px solid rgba(255,255,255,0.06)' }}>
                  {extOptions.map(ext => (
                    <button key={ext} onClick={() => setFilterExt(ext)}
                      style={{ padding: '4px 10px', borderRadius: 7, fontSize: 10, fontWeight: 600, border: 'none', cursor: 'pointer',
                        background: filterExt === ext ? 'rgba(56,189,248,0.15)' : 'transparent',
                        color: filterExt === ext ? '#38bdf8' : '#64748b' }}>
                      {ext}
                    </button>
                  ))}
                </div>
                {/* Search */}
                <div style={{ position: 'relative' }}>
                  <Search size={12} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: '#475569' }} />
                  <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Search files…"
                    style={{ paddingLeft: 30, paddingRight: 10, paddingTop: 6, paddingBottom: 6, borderRadius: 8, background: 'rgba(255,255,255,0.05)', border: '1px solid rgba(255,255,255,0.08)', color: '#f8fafc', fontSize: 12, width: 160, outline: 'none' }} />
                </div>
              </div>
            </div>

            {largestFiles.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '40px 0', color: '#475569', fontSize: 13 }}>
                No file data yet. Click <strong style={{ color: '#38bdf8' }}>Sync from CxAlloy</strong> to load file records.
              </div>
            ) : (
              <div style={{ overflowX: 'auto', borderRadius: 10, border: '1px solid rgba(255,255,255,0.06)' }}>
                <table style={{ width: '100%', fontSize: 12, borderCollapse: 'collapse' }}>
                  <thead>
                    <tr style={{ borderBottom: '1px solid rgba(255,255,255,0.06)', background: 'rgba(0,0,0,0.2)' }}>
                      {[
                        { key: 'name', label: 'File Name' },
                        { key: 'asset', label: 'Asset Type' },
                        { key: 'sizeBytes', label: 'Size' },
                      ].map(col => (
                        <th key={col.key} onClick={() => toggleSort(col.key)}
                          style={{ padding: '10px 14px', textAlign: 'left', fontSize: 10, fontWeight: 700, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.08em', cursor: 'pointer', userSelect: 'none', whiteSpace: 'nowrap' }}>
                          <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                            {col.label}
                            {sortCol === col.key
                              ? (sortDir === 'asc' ? <ChevronUp size={11} /> : <ChevronDown size={11} />)
                              : <ChevronDown size={11} style={{ opacity: 0.2 }} />}
                          </span>
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {displayFiles.length === 0 ? (
                      <tr><td colSpan={3} style={{ padding: '32px 14px', textAlign: 'center', color: '#475569' }}>No files match filters</td></tr>
                    ) : displayFiles.map((file, i) => {
                      const FIcon = getFileIcon(file.ext)
                      const color = getTypeColor(file.ext)
                      return (
                        <tr key={i} style={{ borderBottom: '1px solid rgba(255,255,255,0.04)' }}
                          onMouseEnter={e => e.currentTarget.style.background = 'rgba(255,255,255,0.02)'}
                          onMouseLeave={e => e.currentTarget.style.background = 'transparent'}>
                          <td style={{ padding: '10px 14px', color: '#f1f5f9' }}>
                            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                              <FIcon size={13} style={{ color, flexShrink: 0 }} />
                              <span style={{ maxWidth: 300, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={file.name}>{file.name}</span>
                            </div>
                          </td>
                          <td style={{ padding: '10px 14px', color: '#94a3b8', maxWidth: 160, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{file.asset}</td>
                          <td style={{ padding: '10px 14px', textAlign: 'right', fontFamily: 'monospace', fontWeight: 600, color: '#38bdf8', whiteSpace: 'nowrap' }}>{formatBytes(file.sizeBytes)}</td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            )}

            <div style={{ marginTop: 12, display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 11, color: '#475569' }}>
              <span>Showing {displayFiles.length} of {largestFiles.length} largest files</span>
              {(result.redundantCopies > 0) && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: '#fbbf24' }}>
                  <AlertTriangle size={11} />
                  <span>{(result.redundantCopies).toLocaleString()} duplicate files — potential savings of <strong style={{ color: '#f8fafc' }}>{formatBytes(result.wasteBytes)}</strong></span>
                </div>
              )}
            </div>
          </div>

          {/* ── Optimization Banner (only if duplicates found) ─────────── */}
          {(result.dupGroups > 0) && (
            <div style={{ padding: 20, borderRadius: 14, background: 'rgba(245,158,11,0.06)', border: '1px solid rgba(245,158,11,0.2)' }}>
              <div style={{ display: 'flex', gap: 14 }}>
                <div style={{ width: 36, height: 36, borderRadius: 10, background: 'rgba(245,158,11,0.15)', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
                  <Trash2 size={16} color="#fbbf24" />
                </div>
                <div>
                  <div style={{ fontSize: 10, fontWeight: 700, color: '#fbbf24', textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 4 }}>Storage Optimization Opportunity</div>
                  <p style={{ fontSize: 13, color: '#cbd5e1', margin: '0 0 8px 0', lineHeight: 1.6 }}>
                    {result.dupGroups} duplicate file groups detected. Deduplicating would recover approximately{' '}
                    <strong style={{ color: '#f8fafc' }}>{formatBytes(result.wasteBytes)}</strong> of storage.
                  </p>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16, fontSize: 12, color: '#64748b' }}>
                    <span>Duplicate Groups: <strong style={{ color: '#f8fafc' }}>{result.dupGroups}</strong></span>
                    <span>Redundant Copies: <strong style={{ color: '#f8fafc' }}>{(result.redundantCopies || 0).toLocaleString()}</strong></span>
                    <span>Uniqueness: <strong style={{ color: '#2dd4bf' }}>{result.uniquenessPercent}%</strong></span>
                  </div>
                </div>
              </div>
            </div>
          )}
        </>
      )}

      <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  )
}
