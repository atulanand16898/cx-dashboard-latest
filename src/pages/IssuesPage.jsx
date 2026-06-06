import React, { useEffect, useMemo, useState } from 'react'
import { Download, Edit2, ExternalLink, Plus, RefreshCw, Search, Trash2, X, ChevronDown } from 'lucide-react'
import { useLocation } from 'react-router-dom'
import toast from 'react-hot-toast'
import { issuesApi } from '../services/api'
import { useProject } from '../context/ProjectContext'
import { DetailGrid, EmptyState, Modal, PriorityBadge, Skeleton, SyncResultCard, Table, StatusBadge } from '../components/ui'
import { AlertCircle } from 'lucide-react'
import { emitSyncRefresh, useSyncRefreshSignal } from '../hooks/useSyncRefreshSignal'

const PAGE_SIZE = 20
const defaultForm = { title: '', description: '', status: 'open', priority: 'medium', assignee: '', dueDate: '' }

function fmtDate(value) {
  if (!value) return '—'
  try {
    const parsed = new Date(value)
    if (Number.isNaN(parsed.getTime())) return value
    return parsed.toLocaleDateString('en-US', { month: '2-digit', day: '2-digit', year: 'numeric' })
  } catch {
    return value
  }
}

function normalizeIssueStatus(status) {
  switch ((status || '').toLowerCase().trim()) {
    case 'open':
    case 'issue_opened':
    case 'active':
    case 'new':
      return 'issue_opened'
    case 'correction_in_progress':
    case 'in_progress':
    case 'started':
      return 'correction_in_progress'
    case 'gc_to_verify':
    case 'gc_verify':
      return 'gc_to_verify'
    case 'ready_for_retest':
    case 'ready_for_verification':
    case 'cxa_to_verify':
    case 'cxa_verify':
      return 'cxa_to_verify'
    case 'issue_closed':
    case 'closed':
    case 'done':
    case 'resolved':
    case 'completed':
      return 'issue_closed'
    case 'accepted_by_owner':
    case 'accepted':
      return 'accepted_by_owner'
    case 'recommendation':
    case 'additional_information_needed':
      return 'recommendation'
    default:
      return 'issue_opened'
  }
}

function annotateIssue(issue, project) {
  return {
    ...issue,
    __sourceProjectName: project?.name || '',
    __sourceProjectExternalId: project?.externalId || '',
  }
}

export default function IssuesPage() {
  const { isMultiProject, primaryProject, scopeProjects } = useProject()
  const location = useLocation()
  const scopeProjectIds = scopeProjects.map((project) => project?.externalId || project?.id).filter(Boolean)
  const scopeKey = scopeProjectIds.join(',')
  const refreshSignal = useSyncRefreshSignal(scopeProjectIds)
  const [issues, setIssues] = useState([])
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState('all')
  const [modalOpen, setModalOpen] = useState(false)
  const [editIssue, setEditIssue] = useState(null)
  const [form, setForm] = useState(defaultForm)
  const [syncResult, setSyncResult] = useState(null)
  const [syncing, setSyncing] = useState(false)
  const [detailIssue, setDetailIssue] = useState(null)
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE)

  const load = async () => {
    if (!scopeProjects.length) {
      setIssues([])
      setLoading(false)
      return
    }

    setLoading(true)
    try {
      const results = await Promise.all(
        scopeProjects.map(async (project) => {
          const response = await issuesApi.getAll(project.externalId)
          const records = response.data?.data || []
          return records.map((issue) => annotateIssue(issue, project))
        })
      )
      setIssues(results.flat())
    } catch {
      toast.error('Failed to load issues')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
  }, [refreshSignal, scopeKey])

  useEffect(() => {
    setVisibleCount(PAGE_SIZE)
  }, [scopeKey, search, statusFilter])

  useEffect(() => {
    const preset = location.state?.listPreset
    if (preset?.entityType === 'issues') {
      setSearch(preset.search || '')
      setStatusFilter(preset.filters?.status || 'all')
      return
    }
    setSearch('')
    setStatusFilter('all')
  }, [location.key, location.state, scopeKey])

  const openCreate = () => {
    setForm(defaultForm)
    setEditIssue(null)
    setModalOpen(true)
  }

  const openEdit = (issue) => {
    setForm({
      title: issue.title || '',
      description: issue.description || '',
      status: issue.status || 'open',
      priority: issue.priority || 'medium',
      assignee: issue.assignee || '',
      dueDate: issue.dueDate || '',
    })
    setEditIssue(issue)
    setModalOpen(true)
  }

  const handleSubmit = async (event) => {
    event.preventDefault()
    const payload = { ...form, projectId: primaryProject?.externalId }
    try {
      if (editIssue) {
        await issuesApi.update(editIssue.externalId, payload)
        toast.success('Issue updated')
      } else {
        await issuesApi.create(payload)
        toast.success('Issue created')
      }
      setModalOpen(false)
      await load()
    } catch (error) {
      toast.error(error.response?.data?.message || 'Operation failed')
    }
  }

  const handleDelete = async (issue) => {
    if (!window.confirm(`Delete issue "${issue.title}"?`)) return
    try {
      await issuesApi.delete(issue.externalId, issue.__sourceProjectExternalId || primaryProject?.externalId)
      toast.success('Issue deleted')
      await load()
    } catch (error) {
      toast.error(error.response?.data?.message || 'Delete failed')
    }
  }

  const handleSync = async () => {
    if (!scopeProjects.length) return
    setSyncing(true)
    setSyncResult(null)
    try {
      const responses = await Promise.all(scopeProjects.map((project) => issuesApi.syncAll(project.externalId)))
      const recordsSynced = responses.reduce((sum, response) => sum + Number(response.data?.data?.recordsSynced || 0), 0)
      setSyncResult({
        status: 'SUCCESS',
        recordsSynced,
        projectCount: scopeProjects.length,
      })
      toast.success('Issues synced!')
      await load()
      emitSyncRefresh({ projectId: primaryProject?.externalId, scope: 'issues-sync' })
    } catch {
      toast.error('Sync failed')
    } finally {
      setSyncing(false)
    }
  }

  const CLOSED_STATUSES = ['issue_closed', 'accepted_by_owner']
  const ACTIVE_STATUSES = ['issue_opened', 'correction_in_progress', 'gc_to_verify', 'cxa_to_verify']

  const filtered = useMemo(() => issues.filter((issue) => {
    const matchSearch = !search
      || (issue.title || '').toLowerCase().includes(search.toLowerCase())
      || (issue.__sourceProjectName || '').toLowerCase().includes(search.toLowerCase())
      || (issue.__sourceProjectExternalId || '').toLowerCase().includes(search.toLowerCase())
    const normalizedStatus = normalizeIssueStatus(issue.status)
    return matchSearch && (statusFilter === 'all' || normalizedStatus === statusFilter)
  }), [issues, search, statusFilter])

  const visibleItems = useMemo(() => filtered.slice(0, visibleCount), [filtered, visibleCount])
  const hasMore = visibleCount < filtered.length
  const remaining = Math.min(PAGE_SIZE, filtered.length - visibleCount)

  const handleExportCsv = () => {
    const exportColumns = [
      ...(isMultiProject ? [['Project', (row) => row.__sourceProjectName || row.__sourceProjectExternalId]] : []),
      ['ID', (row) => row.externalId],
      ['Title', (row) => row.title],
      ['Status', (row) => row.status],
      ['Priority', (row) => row.priority],
      ['Assignee', (row) => row.assignee],
      ['Created', (row) => fmtDate(row.createdAt)],
      ['Last Updated', (row) => fmtDate(row.updatedAt)],
      ['Actual Finish', (row) => fmtDate(row.actualFinishDate)],
      ['Due Date', (row) => row.dueDate],
    ]
    const escapeCsv = (value) => `"${String(value ?? '').replace(/"/g, '""')}"`
    const header = exportColumns.map(([label]) => escapeCsv(label)).join(',')
    const rows = filtered.map((row) => exportColumns.map(([, getter]) => escapeCsv(getter(row))).join(','))
    const blob = new Blob([[header, ...rows].join('\r\n')], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `issues-${isMultiProject ? 'multi-project-scope' : (primaryProject?.externalId || 'workspace')}.csv`
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
  }

  const columns = [
    ...(isMultiProject ? [{
      key: '__sourceProjectName',
      label: 'Project',
      render: (_, row) => (
        <div>
          <div className="font-500 text-white">{row.__sourceProjectName || row.__sourceProjectExternalId || '—'}</div>
          {row.__sourceProjectExternalId ? <div className="font-mono text-[10px] text-dark-500">{row.__sourceProjectExternalId}</div> : null}
        </div>
      ),
    }] : []),
    { key: 'externalId', label: 'ID', render: (value) => <span className="font-mono text-xs text-dark-400">{value || '—'}</span> },
    { key: 'title', label: 'Title', render: (value) => <span className="font-500 text-white max-w-xs truncate block">{value || '—'}</span> },
    { key: 'status', label: 'Status', render: (value) => <StatusBadge status={value} /> },
    { key: 'priority', label: 'Priority', render: (value) => <PriorityBadge priority={value} /> },
    { key: 'assignee', label: 'Assignee', render: (value) => value || '—' },
    { key: 'createdAt', label: 'Created', render: (value) => <span className="font-mono text-xs text-dark-400">{fmtDate(value)}</span> },
    { key: 'updatedAt', label: 'Last Updated', render: (value) => <span className="font-mono text-xs text-dark-400">{fmtDate(value)}</span> },
    { key: 'actualFinishDate', label: 'Actual Finish', render: (value) => <span className="font-mono text-xs text-dark-400">{fmtDate(value)}</span> },
    { key: 'dueDate', label: 'Due Date', render: (value) => value || '—' },
    {
      key: '_actions',
      label: '',
      render: (_, row) => (
        <div className="flex items-center gap-2" onClick={(event) => event.stopPropagation()}>
          <button onClick={() => openEdit(row)} className="p-1.5 rounded-lg text-dark-400 hover:text-sky-400 hover:bg-sky-400/10 transition-all">
            <Edit2 size={13} />
          </button>
          <button onClick={() => handleDelete(row)} className="p-1.5 rounded-lg text-dark-400 hover:text-red-400 hover:bg-red-400/10 transition-all">
            <Trash2 size={13} />
          </button>
        </div>
      ),
    },
  ]

  return (
    <div className="space-y-5 animate-fade-in">
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative flex-1 min-w-48">
          <Search size={14} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-dark-500" />
          <input
            type="text"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            className="input-field pl-9"
            placeholder={isMultiProject ? 'Search issues or project...' : 'Search issues...'}
          />
        </div>

        <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)} className="input-field w-auto">
          <option value="all">All Status</option>
          <option value="issue_opened">Issue Opened</option>
          <option value="correction_in_progress">Correction in Progress</option>
          <option value="gc_to_verify">GC to Verify</option>
          <option value="cxa_to_verify">CxA to verify</option>
          <option value="issue_closed">Issue Closed</option>
          <option value="accepted_by_owner">Accepted by Owner</option>
          <option value="recommendation">Recommendation</option>
        </select>

        <button onClick={handleSync} disabled={syncing || !scopeProjects.length} className="btn-secondary">
          <RefreshCw size={14} className={syncing ? 'animate-spin' : ''} />
          {syncing ? 'Syncing...' : isMultiProject ? 'Sync Scope' : 'Sync Issues'}
        </button>

        <button onClick={handleExportCsv} className="btn-secondary">
          <Download size={14} />
          Export CSV
        </button>

        <button onClick={openCreate} disabled={!primaryProject} className="btn-primary">
          <Plus size={14} />
          {isMultiProject ? 'New Issue in Primary' : 'New Issue'}
        </button>
      </div>

      <div className="grid grid-cols-4 gap-3">
        {[
          { label: 'Total', value: issues.length, cls: 'text-white' },
          { label: 'Active Workflow', value: issues.filter((issue) => ACTIVE_STATUSES.includes(normalizeIssueStatus(issue.status))).length, cls: 'text-yellow-400' },
          { label: 'Closed / Accepted', value: issues.filter((issue) => CLOSED_STATUSES.includes(normalizeIssueStatus(issue.status))).length, cls: 'text-green-400' },
          { label: 'Recommendation', value: issues.filter((issue) => normalizeIssueStatus(issue.status) === 'recommendation').length, cls: 'text-sky-400' },
        ].map((stat) => (
          <div key={stat.label} className="glass-card-light p-4 text-center">
            <div className={`text-2xl font-800 ${stat.cls}`}>{stat.value}</div>
            <div className="text-xs text-dark-500 mt-0.5 uppercase tracking-widest">{stat.label}</div>
          </div>
        ))}
      </div>

      {syncResult ? (
        <div className="relative">
          <button onClick={() => setSyncResult(null)} className="absolute top-2 right-2 text-dark-400 hover:text-white z-10">
            <X size={14} />
          </button>
          <SyncResultCard result={syncResult} />
        </div>
      ) : null}

      {loading ? (
        <div className="space-y-2">
          {[...Array(5)].map((_, index) => <Skeleton key={index} className="h-12" />)}
        </div>
      ) : filtered.length === 0 ? (
        <EmptyState icon={AlertCircle} title="No Issues Found" description="Try adjusting your search or sync to pull latest data" />
      ) : (
        <>
          <Table columns={columns} data={visibleItems} onRowClick={setDetailIssue} />
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10, padding: '4px 0 8px' }}>
            <div style={{ fontSize: 12, color: '#475569' }}>
              Showing <span style={{ color: '#cbd5e1', fontWeight: 700 }}>{visibleItems.length}</span> of <span style={{ color: '#cbd5e1', fontWeight: 700 }}>{filtered.length}</span> issues
            </div>
            {hasMore ? (
              <button
                onClick={() => setVisibleCount((current) => current + PAGE_SIZE)}
                style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '10px 32px', background: 'rgba(56,189,248,0.08)', border: '1px solid rgba(56,189,248,0.3)', borderRadius: 10, cursor: 'pointer', color: '#38bdf8', fontSize: 13, fontWeight: 600, transition: 'all 0.15s' }}
                onMouseEnter={(event) => { event.currentTarget.style.background = 'rgba(56,189,248,0.18)'; event.currentTarget.style.borderColor = 'rgba(56,189,248,0.6)' }}
                onMouseLeave={(event) => { event.currentTarget.style.background = 'rgba(56,189,248,0.08)'; event.currentTarget.style.borderColor = 'rgba(56,189,248,0.3)' }}
              >
                <ChevronDown size={15} /> Load {remaining} more
              </button>
            ) : null}
          </div>
        </>
      )}

      <Modal open={modalOpen} onClose={() => setModalOpen(false)} title={editIssue ? 'Edit Issue' : 'New Issue'}>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-xs font-600 text-dark-400 mb-1.5">Title</label>
            <input className="input-field" value={form.title} onChange={(event) => setForm((current) => ({ ...current, title: event.target.value }))} required />
          </div>
          <div>
            <label className="block text-xs font-600 text-dark-400 mb-1.5">Description</label>
            <textarea className="input-field resize-none" rows={3} value={form.description} onChange={(event) => setForm((current) => ({ ...current, description: event.target.value }))} />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-600 text-dark-400 mb-1.5">Status</label>
              <select className="input-field" value={form.status} onChange={(event) => setForm((current) => ({ ...current, status: event.target.value }))}>
                <option value="open">Open</option>
                <option value="in_progress">In Progress</option>
                <option value="pending">Pending</option>
                <option value="closed">Closed</option>
              </select>
            </div>
            <div>
              <label className="block text-xs font-600 text-dark-400 mb-1.5">Priority</label>
              <select className="input-field" value={form.priority} onChange={(event) => setForm((current) => ({ ...current, priority: event.target.value }))}>
                <option value="low">Low</option>
                <option value="medium">Medium</option>
                <option value="high">High</option>
                <option value="critical">Critical</option>
              </select>
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-600 text-dark-400 mb-1.5">Assignee</label>
              <input className="input-field" value={form.assignee} onChange={(event) => setForm((current) => ({ ...current, assignee: event.target.value }))} />
            </div>
            <div>
              <label className="block text-xs font-600 text-dark-400 mb-1.5">Due Date</label>
              <input type="date" className="input-field" value={form.dueDate} onChange={(event) => setForm((current) => ({ ...current, dueDate: event.target.value }))} />
            </div>
          </div>
          <div className="flex gap-3 pt-2">
            <button type="button" onClick={() => setModalOpen(false)} className="btn-secondary flex-1 justify-center">Cancel</button>
            <button type="submit" className="btn-primary flex-1 justify-center">{editIssue ? 'Update Issue' : 'Create Issue'}</button>
          </div>
        </form>
      </Modal>

      <Modal open={!!detailIssue} onClose={() => setDetailIssue(null)} title="Issue Details">
        {detailIssue ? (
          <>
            <DetailGrid data={detailIssue} />
            {detailIssue.externalId && (detailIssue.__sourceProjectExternalId || primaryProject?.externalId) ? (
              <div style={{ marginTop: 16, paddingTop: 14, borderTop: '1px solid rgba(255,255,255,0.07)', textAlign: 'center' }}>
                <a
                  href={`https://tq.cxalloy.com/project/${detailIssue.__sourceProjectExternalId || primaryProject?.externalId}/issues/${detailIssue.externalId}`}
                  target="_blank"
                  rel="noopener noreferrer"
                  style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '9px 22px', background: 'rgba(56,189,248,0.08)', border: '1px solid rgba(56,189,248,0.25)', borderRadius: 10, color: '#38bdf8', fontSize: 13, fontWeight: 600, textDecoration: 'none', transition: 'all 0.15s' }}
                >
                  <ExternalLink size={13} /> View full record in CxAlloy →
                </a>
              </div>
            ) : null}
          </>
        ) : null}
      </Modal>
    </div>
  )
}
