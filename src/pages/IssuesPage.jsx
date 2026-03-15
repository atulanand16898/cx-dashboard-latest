import React, { useState, useEffect, useMemo } from 'react'
import { Plus, Search, Filter, RefreshCw, Trash2, Edit2, X, ExternalLink, ChevronDown } from 'lucide-react'
import { issuesApi } from '../services/api'
import { useProject } from '../context/ProjectContext'
import { Table, StatusBadge, PriorityBadge, Modal, SyncResultCard, EmptyState, Skeleton, DetailGrid } from '../components/ui'
import toast from 'react-hot-toast'
import { AlertCircle } from 'lucide-react'

const PAGE_SIZE = 20
const defaultForm = { title: '', description: '', status: 'open', priority: 'medium', assignee: '', dueDate: '' }

export default function IssuesPage() {
  const { activeProject } = useProject()
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
    setLoading(true)
    try {
      const res = await issuesApi.getAll(activeProject?.externalId)
      setIssues(res.data.data || [])
    } catch { toast.error('Failed to load issues') }
    finally { setLoading(false) }
  }

  useEffect(() => { load() }, [activeProject])
  useEffect(() => { setVisibleCount(PAGE_SIZE) }, [search, statusFilter, activeProject])

  const openCreate = () => { setForm(defaultForm); setEditIssue(null); setModalOpen(true) }
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

  const handleSubmit = async (e) => {
    e.preventDefault()
    const data = { ...form, projectId: activeProject?.externalId }
    try {
      if (editIssue) {
        await issuesApi.update(editIssue.externalId, data)
        toast.success('Issue updated')
      } else {
        await issuesApi.create(data)
        toast.success('Issue created')
      }
      setModalOpen(false)
      load()
    } catch (err) { toast.error(err.response?.data?.message || 'Operation failed') }
  }

  const handleDelete = async (issue) => {
    if (!confirm(`Delete issue "${issue.title}"?`)) return
    try {
      await issuesApi.delete(issue.externalId, activeProject?.externalId)
      toast.success('Issue deleted')
      load()
    } catch (err) { toast.error(err.response?.data?.message || 'Delete failed') }
  }

  const handleSync = async () => {
    setSyncing(true)
    setSyncResult(null)
    try {
      const res = await issuesApi.syncAll(activeProject?.externalId)
      setSyncResult(res.data.data)
      toast.success('Issues synced!')
      load()
    } catch (err) { toast.error('Sync failed') }
    finally { setSyncing(false) }
  }

  const CLOSED_STATUSES = ['issue_closed', 'accepted_by_owner', 'closed', 'done', 'resolved', 'completed']
  const OPEN_STATUSES   = ['open', 'issue_opened', 'correction_in_progress', 'in_progress', 'active']
  const REVIEW_STATUSES = ['gc_to_verify', 'cxa_to_verify', 'ready_for_retest', 'additional_information_needed']

  const filtered = issues.filter(i => {
    const matchSearch = !search || (i.title || '').toLowerCase().includes(search.toLowerCase())
    const s = (i.status || '').toLowerCase()
    let matchStatus = true
    if (statusFilter === 'open')   matchStatus = OPEN_STATUSES.includes(s)
    else if (statusFilter === 'closed') matchStatus = CLOSED_STATUSES.includes(s)
    else if (statusFilter === 'in_review') matchStatus = REVIEW_STATUSES.includes(s)
    else if (statusFilter !== 'all') matchStatus = s === statusFilter
    return matchSearch && matchStatus
  })

  const visibleItems = useMemo(() => filtered.slice(0, visibleCount), [filtered, visibleCount])
  const hasMore = visibleCount < filtered.length
  const remaining = Math.min(PAGE_SIZE, filtered.length - visibleCount)

  const columns = [
    { key: 'externalId', label: 'ID', render: v => <span className="font-mono text-xs text-dark-400">{v || '—'}</span> },
    { key: 'title', label: 'Title', render: v => <span className="font-500 text-white max-w-xs truncate block">{v || '—'}</span> },
    { key: 'status', label: 'Status', render: v => <StatusBadge status={v} /> },
    { key: 'priority', label: 'Priority', render: v => <PriorityBadge priority={v} /> },
    { key: 'assignee', label: 'Assignee', render: v => v || '—' },
    { key: 'dueDate', label: 'Due Date', render: v => v || '—' },
    {
      key: '_actions', label: '', render: (_, row) => (
        <div className="flex items-center gap-2" onClick={e => e.stopPropagation()}>
          <button onClick={() => openEdit(row)} className="p-1.5 rounded-lg text-dark-400 hover:text-sky-400 hover:bg-sky-400/10 transition-all">
            <Edit2 size={13} />
          </button>
          <button onClick={() => handleDelete(row)} className="p-1.5 rounded-lg text-dark-400 hover:text-red-400 hover:bg-red-400/10 transition-all">
            <Trash2 size={13} />
          </button>
        </div>
      )
    },
  ]

  return (
    <div className="space-y-5 animate-fade-in">
      {/* Actions bar */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative flex-1 min-w-48">
          <Search size={14} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-dark-500" />
          <input
            type="text"
            value={search}
            onChange={e => setSearch(e.target.value)}
            className="input-field pl-9"
            placeholder="Search issues..."
          />
        </div>

        <select
          value={statusFilter}
          onChange={e => setStatusFilter(e.target.value)}
          className="input-field w-auto"
        >
          <option value="all">All Status</option>
          <option value="open">Open / Active</option>
          <option value="in_review">Pending Review</option>
          <option value="closed">Closed / Accepted</option>
          <option value="issue_closed">Issue Closed</option>
          <option value="accepted_by_owner">Accepted By Owner</option>
          <option value="correction_in_progress">Correction In Progress</option>
          <option value="gc_to_verify">GC To Verify</option>
          <option value="cxa_to_verify">CXA To Verify</option>
          <option value="ready_for_retest">Ready For Retest</option>
        </select>

        <button onClick={handleSync} disabled={syncing} className="btn-secondary">
          <RefreshCw size={14} className={syncing ? 'animate-spin' : ''} />
          {syncing ? 'Syncing...' : 'Sync Issues'}
        </button>

        <button onClick={openCreate} className="btn-primary">
          <Plus size={14} />
          New Issue
        </button>
      </div>

      {/* Stats row */}
      <div className="grid grid-cols-4 gap-3">
        {[
          { label: 'Total', value: issues.length, cls: 'text-white' },
          { label: 'Open / Active', value: issues.filter(i => OPEN_STATUSES.includes((i.status || '').toLowerCase())).length, cls: 'text-yellow-400' },
          { label: 'Closed', value: issues.filter(i => CLOSED_STATUSES.includes((i.status || '').toLowerCase())).length, cls: 'text-green-400' },
          { label: 'Pending Review', value: issues.filter(i => REVIEW_STATUSES.includes((i.status || '').toLowerCase())).length, cls: 'text-sky-400' },
        ].map(s => (
          <div key={s.label} className="glass-card-light p-4 text-center">
            <div className={`text-2xl font-800 ${s.cls}`}>{s.value}</div>
            <div className="text-xs text-dark-500 mt-0.5 uppercase tracking-widest">{s.label}</div>
          </div>
        ))}
      </div>

      {/* Sync result */}
      {syncResult && (
        <div className="relative">
          <button onClick={() => setSyncResult(null)} className="absolute top-2 right-2 text-dark-400 hover:text-white z-10">
            <X size={14} />
          </button>
          <SyncResultCard result={syncResult} />
        </div>
      )}

      {/* Table */}
      {loading ? (
        <div className="space-y-2">
          {[...Array(5)].map((_, i) => <Skeleton key={i} className="h-12" />)}
        </div>
      ) : filtered.length === 0 ? (
        <EmptyState icon={AlertCircle} title="No Issues Found" description="Try adjusting your search or sync to pull latest data" />
      ) : (
        <>
          <Table
            columns={columns}
            data={visibleItems}
            onRowClick={setDetailIssue}
          />
          {/* Count + Load More */}
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10, padding: '4px 0 8px' }}>
            <div style={{ fontSize: 12, color: '#475569' }}>
              Showing <span style={{ color: '#cbd5e1', fontWeight: 700 }}>{visibleItems.length}</span> of <span style={{ color: '#cbd5e1', fontWeight: 700 }}>{filtered.length}</span> issues
            </div>
            {hasMore && (
              <button
                onClick={() => setVisibleCount(c => c + PAGE_SIZE)}
                style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '10px 32px', background: 'rgba(56,189,248,0.08)', border: '1px solid rgba(56,189,248,0.3)', borderRadius: 10, cursor: 'pointer', color: '#38bdf8', fontSize: 13, fontWeight: 600, transition: 'all 0.15s' }}
                onMouseEnter={e => { e.currentTarget.style.background = 'rgba(56,189,248,0.18)'; e.currentTarget.style.borderColor = 'rgba(56,189,248,0.6)' }}
                onMouseLeave={e => { e.currentTarget.style.background = 'rgba(56,189,248,0.08)'; e.currentTarget.style.borderColor = 'rgba(56,189,248,0.3)' }}
              >
                <ChevronDown size={15} /> Load {remaining} more
              </button>
            )}
          </div>
        </>
      )}

      {/* Create/Edit Modal */}
      <Modal open={modalOpen} onClose={() => setModalOpen(false)} title={editIssue ? 'Edit Issue' : 'New Issue'}>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-xs font-600 text-dark-400 mb-1.5">Title</label>
            <input className="input-field" value={form.title} onChange={e => setForm(f => ({ ...f, title: e.target.value }))} required />
          </div>
          <div>
            <label className="block text-xs font-600 text-dark-400 mb-1.5">Description</label>
            <textarea className="input-field resize-none" rows={3} value={form.description} onChange={e => setForm(f => ({ ...f, description: e.target.value }))} />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-600 text-dark-400 mb-1.5">Status</label>
              <select className="input-field" value={form.status} onChange={e => setForm(f => ({ ...f, status: e.target.value }))}>
                <option value="open">Open</option>
                <option value="in_progress">In Progress</option>
                <option value="pending">Pending</option>
                <option value="closed">Closed</option>
              </select>
            </div>
            <div>
              <label className="block text-xs font-600 text-dark-400 mb-1.5">Priority</label>
              <select className="input-field" value={form.priority} onChange={e => setForm(f => ({ ...f, priority: e.target.value }))}>
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
              <input className="input-field" value={form.assignee} onChange={e => setForm(f => ({ ...f, assignee: e.target.value }))} />
            </div>
            <div>
              <label className="block text-xs font-600 text-dark-400 mb-1.5">Due Date</label>
              <input type="date" className="input-field" value={form.dueDate} onChange={e => setForm(f => ({ ...f, dueDate: e.target.value }))} />
            </div>
          </div>
          <div className="flex gap-3 pt-2">
            <button type="button" onClick={() => setModalOpen(false)} className="btn-secondary flex-1 justify-center">Cancel</button>
            <button type="submit" className="btn-primary flex-1 justify-center">{editIssue ? 'Update Issue' : 'Create Issue'}</button>
          </div>
        </form>
      </Modal>

      {/* Detail Modal */}
      <Modal open={!!detailIssue} onClose={() => setDetailIssue(null)} title="Issue Details">
        {detailIssue && (
          <>
            <DetailGrid data={detailIssue} />
            {detailIssue.externalId && activeProject?.externalId && (
              <div style={{ marginTop: 16, paddingTop: 14, borderTop: '1px solid rgba(255,255,255,0.07)', textAlign: 'center' }}>
                <a
                  href={`https://tq.cxalloy.com/project/${activeProject.externalId}/issues/${detailIssue.externalId}`}
                  target="_blank" rel="noopener noreferrer"
                  style={{ display:'inline-flex', alignItems:'center', gap:8, padding:'9px 22px', background:'rgba(56,189,248,0.08)', border:'1px solid rgba(56,189,248,0.25)', borderRadius:10, color:'#38bdf8', fontSize:13, fontWeight:600, textDecoration:'none', transition:'all 0.15s' }}
                  onMouseEnter={e => { e.currentTarget.style.background='rgba(56,189,248,0.18)'; e.currentTarget.style.borderColor='rgba(56,189,248,0.6)' }}
                  onMouseLeave={e => { e.currentTarget.style.background='rgba(56,189,248,0.08)'; e.currentTarget.style.borderColor='rgba(56,189,248,0.25)' }}
                >
                  <ExternalLink size={13} /> View full record in CxAlloy →
                </a>
              </div>
            )}
          </>
        )}
      </Modal>
    </div>
  )
}
