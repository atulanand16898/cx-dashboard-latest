import React, { useState, useEffect, useCallback } from 'react'
import {
  HardDrive, RefreshCw, FileText, Copy, Trash2,
  Download, AlertCircle, CheckCircle2, BarChart2, Database,
  ChevronDown, ChevronUp, Search, X, FileIcon, Film,
  Image, Archive, File, Zap, TrendingDown
} from 'lucide-react'
import {
  PieChart, Pie, Cell, Tooltip, ResponsiveContainer,
  BarChart, Bar, XAxis, YAxis, CartesianGrid,
} from 'recharts'
import { useProject } from '../context/ProjectContext'
import { checklistsApi, assetsApi, fileStorageApi } from '../services/api'
import toast from 'react-hot-toast'

function formatBytes(bytes) {
  if (!bytes || bytes === 0) return '0 B'
  if (bytes >= 1e12) return `${(bytes / 1e12).toFixed(2)} TB`
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(2)} GB`
  if (bytes >= 1e6) return `${(bytes / 1e6).toFixed(2)} MB`
  if (bytes >= 1e3) return `${(bytes / 1e3).toFixed(2)} KB`
  return `${bytes} B`
}

const FILE_COLORS = {
  pdf: '#3b82f6', heic: '#6366f1', mov: '#f97316',
  jpg: '#eab308', jpeg: '#eab308', zip: '#ec4899',
  png: '#a855f7', mp4: '#f97316', xlsx: '#22c55e',
  docx: '#0ea5e9', other: '#64748b',
}

function getFileColor(ext) {
  return FILE_COLORS[(ext || '').toLowerCase()] || FILE_COLORS.other
}

function getFileIcon(ext) {
  const e = (ext || '').toLowerCase()
  if (['pdf', 'docx', 'xlsx', 'txt'].includes(e)) return FileText
  if (['mp4', 'mov', 'avi', 'mkv'].includes(e)) return Film
  if (['jpg', 'jpeg', 'png', 'heic', 'gif', 'webp'].includes(e)) return Image
  if (['zip', 'rar', '7z', 'tar'].includes(e)) return Archive
  return File
}

function MetricCard({ label, value, sub, icon: Icon, borderColor, iconColor, iconBg }) {
  return (
    <div className="glass-card p-5 relative overflow-hidden" style={{ borderColor }}>
      <div className="flex items-start justify-between">
        <div className="flex-1 min-w-0">
          <div className="text-[10px] text-dark-400 uppercase tracking-widest font-600 mb-2">{label}</div>
          <div className="text-3xl font-800 text-white truncate mb-1">{value}</div>
          {sub && <div className="text-xs text-dark-500 mt-1">{sub}</div>}
        </div>
        <div className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 ml-3" style={{ background: iconBg }}>
          <Icon size={20} style={{ color: iconColor }} />
        </div>
      </div>
    </div>
  )
}

function CustomPieTooltip({ active, payload }) {
  if (!active || !payload?.length) return null
  return (
    <div className="glass-card px-3 py-2 text-xs shadow-xl border border-white/10">
      <div className="font-700 text-white">{payload[0].name?.toUpperCase()}</div>
      <div className="text-dark-300">{formatBytes(payload[0].value)}</div>
      <div className="text-dark-500">{payload[0].payload.pct?.toFixed(1)}%</div>
    </div>
  )
}

function CustomBarTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null
  return (
    <div className="glass-card px-3 py-2 text-xs shadow-xl border border-white/10">
      <div className="text-dark-400 mb-1 truncate max-w-[160px]">{label}</div>
      <div className="text-sky-400 font-700">{formatBytes(payload[0]?.value)}</div>
    </div>
  )
}

async function buildFromExisting(activeProject) {
  // Return null — caller will show "backend not connected" state instead of synthetic data
  return null
}

function normalizeBackendReport(raw, lgData, haData) {
  const totalBytes = raw.totalSizeBytes || raw.totalBytes || 0
  const byExt = raw.byExtension || {}
  const byExtArr = Object.entries(byExt).map(([ext, count]) => ({
    ext, count,
    bytes: raw.byExtensionBytes?.[ext] ?? 0,
    pct: totalBytes > 0 ? ((raw.byExtensionBytes?.[ext] ?? 0) / totalBytes * 100) : 0,
  })).sort((a, b) => b.bytes - a.bytes)
  return {
    report: {
      checklistsScanned: raw.checklistsScanned ?? raw.totalFiles ?? 0,
      totalFiles: raw.totalFiles ?? 0,
      totalBytes,
      duplicateGroups: raw.duplicateGroups ?? raw.duplicateFileCount ?? 0,
      redundantBytes: raw.redundantBytes ?? raw.redundantSizeBytes ?? 0,
      uniqueContentRatio: raw.uniqueContentRatio ??
        (raw.totalFiles > 0 ? parseFloat(((raw.uniqueFileCount / raw.totalFiles) * 100).toFixed(1)) : 0),
      fileTypes: byExtArr,
    },
    largest: (lgData || []).map((f, i) => ({
      id: f.id ?? i + 1,
      fileName: f.fileName ?? f.name ?? 'Unknown',
      ext: f.ext ?? (f.name ? f.name.split('.').pop() : ''),
      asset: f.asset ?? f.asset_id ?? 'Unknown Asset',
      checklist: f.checklist ?? f.asset_type ?? '',
      size: f.size ?? f.file_size ?? 0,
    })),
    heaviest: (haData || []).map(a => ({
      name: a.name ?? a.asset_type ?? a.asset_id ?? 'Unknown',
      size: a.size ?? a.total_size_bytes ?? 0,
      fileCount: a.fileCount ?? a.file_count ?? 0,
    })),
  }
}

export default function FileStoragePage() {
  const { activeProject } = useProject()
  const [analyzing, setAnalyzing] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [report, setReport] = useState(null)
  const [largestFiles, setLargestFiles] = useState([])
  const [heaviestAssets, setHeaviestAssets] = useState([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [searchQuery, setSearchQuery] = useState('')
  const [sortField, setSortField] = useState('size')
  const [sortDir, setSortDir] = useState('desc')
  const [showDuplicates, setShowDuplicates] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [filesVisible, setFilesVisible] = useState(10)
  const hasData = !!report                                                                                          

  const loadReport = useCallback(async (forceAnalyze = false) => {
    if (!activeProject) return
    const projectId = activeProject.externalId
    setLoading(true)
    setError(null)
    try {
      let data
      try {
        // Single request: POST /analyze (force-refresh) or GET /report (uses backend cache).
        let repRes
        if (forceAnalyze) {
          repRes = await fileStorageApi.analyze(projectId)
        } else {
          repRes = await fileStorageApi.getReport(projectId)
        }
        const raw = repRes.data.data
        const lgData = raw.largestFiles || []
        const haData = raw.heaviestAssets || []
        data = normalizeBackendReport(raw, lgData, haData)
        setReport(data.report)
        setLargestFiles(data.largest)
        setHeaviestAssets(data.heaviest)
      } catch {
        // Backend not reachable — leave report as null so "No Analysis Yet" state shows.
        // We do NOT display synthetic/estimated data as it would be misleading.
      }
    } catch (err) {
      setError('Failed to load file storage analysis')
      toast.error('Failed to load analysis')
    } finally {
      setLoading(false)
    }
  }, [activeProject])

  useEffect(() => { if (activeProject) loadReport(false) }, [activeProject])

  const handleAnalyze = async () => {
    setAnalyzing(true)
    const toastId = toast.loading('Analyzing file attachments...')
    try {
      await loadReport(true)
      toast.success(`Analysis complete! Found ${report?.duplicateGroups || 0} duplicate groups.`, { id: toastId })
    } catch { toast.error('Analysis failed', { id: toastId }) }
    finally { setAnalyzing(false) }
  }

  const handleRefresh = async () => {
    setRefreshing(true)
    await loadReport(false)
    setRefreshing(false)
    toast.success('Data refreshed')
  }

  const handleExportPdf = async () => {
    setExporting(true)
    try {
      const res = await fileStorageApi.exportPdf(activeProject?.externalId)
      const url = URL.createObjectURL(new Blob([res.data]))
      const a = document.createElement('a')
      a.href = url; a.download = `file-storage-report-${activeProject?.externalId || 'project'}.pdf`
      a.click(); URL.revokeObjectURL(url)
      toast.success('PDF exported')
    } catch { toast.error('PDF export not available — use Download JSON instead') }
    finally { setExporting(false) }
  }

  const handleDownloadJson = async () => {
    try {
      const blob = new Blob([JSON.stringify({ report, largestFiles, heaviestAssets, project: activeProject?.externalId, generatedAt: new Date().toISOString() }, null, 2)], { type: 'application/json' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url; a.download = `file-storage-${activeProject?.externalId || 'project'}.json`
      a.click(); URL.revokeObjectURL(url)
      toast.success('JSON downloaded')
    } catch { toast.error('Download failed') }
  }

  const filteredFiles = largestFiles
    .filter(f => !searchQuery || f.fileName?.toLowerCase().includes(searchQuery.toLowerCase()) || f.asset?.toLowerCase().includes(searchQuery.toLowerCase()))
    .sort((a, b) => {
      const mul = sortDir === 'desc' ? -1 : 1
      if (sortField === 'size') return mul * (a.size - b.size)
      if (sortField === 'name') return mul * (a.fileName || '').localeCompare(b.fileName || '')
      return 0
    })

  const visibleFiles = filteredFiles.slice(0, filesVisible)
  const filesHasMore = filesVisible < filteredFiles.length

  const toggleSort = (field) => {
    setFilesVisible(10)
    if (sortField === field) setSortDir(d => d === 'desc' ? 'asc' : 'desc')
    else { setSortField(field); setSortDir('desc') }
  }

  const pieData = (report?.fileTypes || []).map(ft => ({
    name: ft.ext?.toUpperCase(), value: ft.bytes, pct: ft.pct, fill: getFileColor(ft.ext),
  }))

  const heaviestBarData = heaviestAssets.map(a => ({
    name: (a.name || '').length > 14 ? (a.name || '').slice(0, 14) + '…' : (a.name || ''),
    fullName: a.name, size: a.size,
  }))

  if (!activeProject) {
    return (
      <div className="flex flex-col items-center justify-center h-64 text-center">
        <div className="w-14 h-14 rounded-2xl bg-dark-800 flex items-center justify-center mb-4">
          <HardDrive size={24} className="text-dark-400" />
        </div>
        <h3 className="text-base font-700 text-white mb-1">No Project Selected</h3>
        <p className="text-sm text-dark-400">Select a project to analyze file storage</p>
      </div>
    )
  }

  return (
    <div className="space-y-5 animate-fade-in">
      {/* Header */}
      <div className="flex items-start justify-between gap-4">
        <div>
          <h2 className="text-xl font-800 text-white">File Storage Analysis</h2>
          <p className="text-xs text-dark-400 mt-1">
            Analyze file attachments from checklists, detect duplicates, and identify storage optimization opportunities.
          </p>
        </div>
        <div className="flex items-center gap-2 flex-shrink-0 flex-wrap justify-end">
          <button onClick={handleAnalyze} disabled={analyzing} className="btn-primary text-xs py-2 px-4 flex items-center gap-1.5" style={{ background: 'linear-gradient(135deg,#10b981,#0d9488)' }}>
            {analyzing ? <><RefreshCw size={13} className="animate-spin" /> Analyzing...</> : <><Zap size={13} /> Analyze Files</>}
          </button>
          <button onClick={handleRefresh} disabled={refreshing} className="btn-secondary text-xs py-2 px-3 flex items-center gap-1.5">
            <RefreshCw size={13} className={refreshing ? 'animate-spin' : ''} /> Refresh
          </button>
          <button onClick={handleExportPdf} disabled={exporting} className="btn-secondary text-xs py-2 px-3 flex items-center gap-1.5" style={{ color: '#38bdf8', borderColor: 'rgba(56,189,248,0.3)', background: 'rgba(14,165,233,0.08)' }}>
            <Download size={13} /> Export PDF
          </button>
          <button onClick={handleDownloadJson} className="btn-secondary text-xs py-2 px-3 flex items-center gap-1.5">
            <Download size={13} /> Download JSON
          </button>
        </div>
      </div>

      {/* Analysis complete banner */}
      {hasData && !loading && (
        <div className="flex items-center gap-3 px-4 py-3 rounded-xl border border-emerald-500/30 bg-emerald-500/10">
          <CheckCircle2 size={16} className="text-emerald-400 flex-shrink-0" />
          <span className="text-sm text-emerald-300 font-500">
            Analysis complete! Found <strong className="text-emerald-200">{report?.duplicateGroups?.toLocaleString()}</strong> duplicate groups.
          </span>
        </div>
      )}

      {/* Loading shimmer */}
      {loading && (
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          {[...Array(4)].map((_, i) => (
            <div key={i} className="glass-card p-5 space-y-3 animate-pulse">
              <div className="h-2 w-20 bg-white/5 rounded" />
              <div className="h-8 w-16 bg-white/5 rounded" />
              <div className="h-2 w-28 bg-white/5 rounded" />
            </div>
          ))}
        </div>
      )}

      {/* Metric Cards */}
      {!loading && hasData && (
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
          <MetricCard label="Checklists Scanned" value={report.checklistsScanned?.toLocaleString()} sub={`${report.totalFiles?.toLocaleString()} Files Found`} icon={CheckCircle2} borderColor="rgba(56,189,248,0.25)" iconColor="#38bdf8" iconBg="rgba(56,189,248,0.08)" />
          <MetricCard label="Total Volume" value={formatBytes(report.totalBytes)} sub="Storage Used" icon={Database} borderColor="rgba(99,102,241,0.25)" iconColor="#818cf8" iconBg="rgba(99,102,241,0.08)" />
          <MetricCard label="Project Uniqueness" value={`${report.uniqueContentRatio}%`} sub="Unique Content Ratio" icon={BarChart2} borderColor="rgba(34,197,94,0.25)" iconColor="#4ade80" iconBg="rgba(34,197,94,0.08)" />
          <MetricCard label="Storage Waste" value={formatBytes(report.redundantBytes)} sub={`${report.duplicateGroups?.toLocaleString()} Redundant Copies`} icon={TrendingDown} borderColor="rgba(234,179,8,0.25)" iconColor="#fbbf24" iconBg="rgba(234,179,8,0.08)" />
        </div>
      )}

      {/* Charts */}
      {!loading && hasData && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-5">
          <div className="glass-card p-6">
            <div className="text-[10px] text-dark-400 uppercase tracking-widest font-600 mb-5">Storage by File Type</div>
            <div className="flex items-center gap-4">
              <div className="flex-shrink-0">
                <ResponsiveContainer width={200} height={200}>
                  <PieChart>
                    <Pie data={pieData} cx="50%" cy="50%" innerRadius={58} outerRadius={90} paddingAngle={2} dataKey="value">
                      {pieData.map((entry, i) => <Cell key={i} fill={entry.fill} stroke="transparent" />)}
                    </Pie>
                    <Tooltip content={<CustomPieTooltip />} />
                  </PieChart>
                </ResponsiveContainer>
              </div>
              <div className="space-y-2.5 flex-1">
                {pieData.map((d, i) => (
                  <div key={i} className="flex items-center justify-between gap-2">
                    <div className="flex items-center gap-2">
                      <div className="w-2.5 h-2.5 rounded-sm flex-shrink-0" style={{ background: d.fill }} />
                      <span className="text-xs text-dark-300 font-500">{d.name}</span>
                    </div>
                    <span className="text-xs text-dark-500 font-mono tabular-nums">{d.pct?.toFixed(1)}%</span>
                  </div>
                ))}
              </div>
            </div>
          </div>

          <div className="glass-card p-6">
            <div className="text-[10px] text-dark-400 uppercase tracking-widest font-600 mb-5">Heaviest Assets (Top 5)</div>
            <ResponsiveContainer width="100%" height={200}>
              <BarChart data={heaviestBarData} layout="vertical" barSize={14} margin={{ left: 0, right: 16, top: 0, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="var(--divider)" horizontal={false} />
                <XAxis type="number" tick={{ fill: '#64748b', fontSize: 10 }} axisLine={false} tickLine={false} tickFormatter={v => formatBytes(v).replace(' ', '')} />
                <YAxis type="category" dataKey="name" tick={{ fill: '#94a3b8', fontSize: 10 }} axisLine={false} tickLine={false} width={100} />
                <Tooltip content={<CustomBarTooltip />} />
                <Bar dataKey="size" fill="#3b82f6" radius={[0, 4, 4, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </div>
      )}

      {/* Duplicates */}
      {!loading && hasData && report.duplicateGroups > 0 && (
        <div className="glass-card overflow-hidden">
          <button onClick={() => setShowDuplicates(d => !d)} className="w-full flex items-center justify-between px-5 py-4 hover:bg-white/[0.02] transition-colors">
            <div className="flex items-center gap-3">
              <Copy size={16} className="text-orange-400" />
              <span className="text-sm font-700 text-white">Duplicate File Groups</span>
              <span className="text-xs bg-orange-500/15 text-orange-400 border border-orange-500/25 rounded-md px-2 py-0.5 font-600">
                {report.duplicateGroups?.toLocaleString()} groups · {formatBytes(report.redundantBytes)} reclaimable
              </span>
            </div>
            {showDuplicates ? <ChevronUp size={16} className="text-dark-400" /> : <ChevronDown size={16} className="text-dark-400" />}
          </button>
          {showDuplicates && (
            <div className="px-5 pb-4 border-t border-white/[0.05]">
              <div className="pt-4 text-sm text-dark-400 text-center py-6">
                <Copy size={32} className="text-dark-600 mx-auto mb-2" />
                <p>Duplicate analysis requires the <code className="text-sky-400 text-xs bg-sky-500/10 px-1 py-0.5 rounded">/api/files/duplicates</code> endpoint.</p>
                <p className="text-xs mt-1 text-dark-500">Connect the backend to see file hash groups and deduplication suggestions.</p>
              </div>
            </div>
          )}
        </div>
      )}

      {/* Files Table */}
      {!loading && hasData && largestFiles.length > 0 && (
        <div className="glass-card overflow-hidden">
          <div className="flex items-center justify-between px-5 py-4 border-b border-white/[0.05]">
            <div className="flex items-center gap-3">
              <FileIcon size={16} className="text-sky-400" />
              <span className="text-sm font-700 text-white">Largest Single Files (Top 10)</span>
            </div>
            <div className="relative">
              <Search size={13} className="absolute left-3 top-1/2 -translate-y-1/2 text-dark-500" />
              <input type="text" placeholder="Search files..." value={searchQuery} onChange={e => { setSearchQuery(e.target.value); setFilesVisible(10) }} className="pl-8 pr-8 py-1.5 text-xs rounded-lg bg-dark-900 border border-white/[0.08] text-white placeholder-dark-500 focus:outline-none focus:border-sky-500/50 w-48" />
              {searchQuery && <button onClick={() => { setSearchQuery(''); setFilesVisible(10) }} className="absolute right-2.5 top-1/2 -translate-y-1/2 text-dark-500 hover:text-white"><X size={12} /></button>}
            </div>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-white/[0.04]">
                  {[
                    { label: 'File Name', field: 'name', cls: 'w-[30%]' },
                    { label: 'Asset / Location', field: null, cls: 'w-[22%]' },
                    { label: 'Checklist', field: null, cls: 'w-[30%]' },
                    { label: 'Size', field: 'size', cls: 'w-[18%] text-right' },
                  ].map(col => (
                    <th key={col.label} onClick={col.field ? () => toggleSort(col.field) : undefined} className={`px-5 py-3 text-[10px] text-dark-500 uppercase tracking-widest font-600 ${col.cls} ${col.field ? 'cursor-pointer hover:text-dark-300 select-none' : ''}`}>
                      <div className={`flex items-center gap-1 ${col.cls.includes('text-right') ? 'justify-end' : ''}`}>
                        {col.label}
                        {col.field && sortField === col.field && (sortDir === 'desc' ? <ChevronDown size={11} /> : <ChevronUp size={11} />)}
                      </div>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {visibleFiles.map((file, i) => {
                  const FileIco = getFileIcon(file.ext)
                  const color = getFileColor(file.ext)
                  return (
                    <tr key={file.id || i} className="border-b border-white/[0.03] hover:bg-white/[0.02] transition-colors group">
                      <td className="px-5 py-3">
                        <div className="flex items-center gap-2.5">
                          <div className="w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0" style={{ background: `${color}18` }}>
                            <FileIco size={13} style={{ color }} />
                          </div>
                          <div className="min-w-0">
                            <div className="text-xs font-600 text-white truncate max-w-[200px] group-hover:text-sky-300 transition-colors" title={file.fileName}>{file.fileName}</div>
                            <div className="text-[10px] text-dark-500 uppercase">{file.ext}</div>
                          </div>
                        </div>
                      </td>
                      <td className="px-5 py-3"><div className="text-xs text-dark-300 truncate max-w-[180px]" title={file.asset}>{file.asset}</div></td>
                      <td className="px-5 py-3"><div className="text-xs text-dark-400 truncate max-w-[240px]" title={file.checklist}>{file.checklist}</div></td>
                      <td className="px-5 py-3 text-right"><span className="text-xs font-700 text-white font-mono">{formatBytes(file.size)}</span></td>
                    </tr>
                  )
                })}
                {visibleFiles.length === 0 && (
                  <tr><td colSpan={4} className="px-5 py-12 text-center"><Search size={24} className="text-dark-600 mx-auto mb-2" /><p className="text-xs text-dark-500">No files match your search</p></td></tr>
                )}
              </tbody>
            </table>
          </div>
          <div className="px-5 py-3 border-t border-white/[0.04]">
            <div className="flex items-center justify-between mb-2">
              <span className="text-xs text-dark-500">Showing <strong className="text-white">{visibleFiles.length}</strong> of <strong className="text-white">{filteredFiles.length}</strong> files</span>
              <span className="text-xs text-dark-500 font-mono">Total: <strong className="text-white">{formatBytes(largestFiles.reduce((a, f) => a + (f.size || 0), 0))}</strong></span>
            </div>
            {filesHasMore && (
              <div style={{ textAlign: 'center', paddingTop: 8 }}>
                <button
                  onClick={() => setFilesVisible(v => v + 10)}
                  style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 28px', background: 'rgba(56,189,248,0.08)', border: '1px solid rgba(56,189,248,0.25)', borderRadius: 8, cursor: 'pointer', color: '#38bdf8', fontSize: 12, fontWeight: 600, transition: 'all 0.15s' }}
                  onMouseEnter={e => { e.currentTarget.style.background = 'rgba(56,189,248,0.18)'; e.currentTarget.style.borderColor = 'rgba(56,189,248,0.55)' }}
                  onMouseLeave={e => { e.currentTarget.style.background = 'rgba(56,189,248,0.08)'; e.currentTarget.style.borderColor = 'rgba(56,189,248,0.25)' }}
                >
                  <ChevronDown size={13} /> Load {Math.min(10, filteredFiles.length - filesVisible)} more
                </button>
              </div>
            )}
          </div>
        </div>
      )}

      {!loading && !hasData && !error && (
        <div className="glass-card flex flex-col items-center justify-center py-20 text-center">
          <div className="w-16 h-16 rounded-2xl bg-dark-800 flex items-center justify-center mb-4"><HardDrive size={28} className="text-dark-400" /></div>
          <h3 className="text-base font-700 text-white mb-1">No Analysis Yet</h3>
          <p className="text-sm text-dark-400 max-w-xs mb-5">Click <strong className="text-emerald-400">Analyze Files</strong> to scan checklist attachments and detect duplicate files.</p>
          <button onClick={handleAnalyze} disabled={analyzing} className="btn-primary flex items-center gap-2" style={{ background: 'linear-gradient(135deg,#10b981,#0d9488)' }}>
            <Zap size={14} /> {analyzing ? 'Analyzing...' : 'Analyze Files'}
          </button>
        </div>
      )}

      {error && (
        <div className="glass-card p-10 text-center">
          <AlertCircle size={28} className="text-red-400 mx-auto mb-3" />
          <p className="text-sm text-white font-600 mb-1">Analysis Failed</p>
          <p className="text-xs text-dark-400 mb-4">{error}</p>
          <button onClick={() => loadReport(false)} className="btn-secondary text-xs flex items-center gap-1.5 mx-auto"><RefreshCw size={12} /> Retry</button>
        </div>
      )}
    </div>
  )
}
