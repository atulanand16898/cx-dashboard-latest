import React, { useState, useEffect, useMemo } from 'react'
import { useProject } from '../context/ProjectContext'
import { checklistsApi } from '../services/api'
import { checklistTagDisplayLabel, deriveChecklistTag } from '../utils/checklistTagUtils'

const TAG_COLORS = {
  'level-2 yellow': { bg: '#eab308', label: 'Level-2 YELLOW Tag QA/QC/IVC' },
  'level-1 red':    { bg: '#ef4444', label: 'Level-1 RED Tag FAT' },
  'level-3 green':  { bg: '#22c55e', label: 'Level-3 GREEN Tag Start-Up/PFC' },
  'level-4 blue':   { bg: '#3b82f6', label: 'Level-4 BLUE Tag' },
}

function classifyType(c) {
  return checklistTagDisplayLabel(deriveChecklistTag(c))
}

function classifyStatus(c) {
  const s = (c.status || '').toLowerCase().replace(/ /g,'_').replace(/-/g,'_')
  if (['finished','complete','completed','done','closed','signed_off','approved','passed'].includes(s)) return 'Finished'
  if (['in_progress','inprogress','active','started'].includes(s)) return 'In Progress'
  if (['on_hold','onhold','hold','paused','deferred'].includes(s)) return 'On Hold'
  if (['cancelled','canceled'].includes(s)) return 'Cancelled'
  return 'Not Started'
}

// CxAlloy checklist statuses: not_started, in_progress, finished, on_hold, cancelled
// "Near Complete" does NOT exist in CxAlloy — replaced with On Hold + Cancelled
const STATUS_KEYS = ['Not Started', 'In Progress', 'On Hold', 'Finished', 'Cancelled']
const STATUS_COLORS = {
  'Not Started': '#64748b',
  'In Progress':  '#eab308',
  'On Hold':      '#f97316',
  'Finished':     '#22c55e',
  'Cancelled':    '#ef4444',
}

function computeFlow(checklists) {
  // Distribution by type x status
  const types = new Map()
  const statusCounts = { 'Not Started': 0, 'In Progress': 0, 'On Hold': 0, 'Finished': 0, 'Cancelled': 0 }
  let workflowAlerts = 0

  checklists.forEach(c => {
    const type = classifyType(c)
    const status = classifyStatus(c)
    statusCounts[status] = (statusCounts[status] || 0) + 1

    if (!types.has(type)) types.set(type, { type, 'Not Started': 0, 'In Progress': 0, 'Near Complete': 0, 'Finished': 0, total: 0 })
    const row = types.get(type)
    row[status] = (row[status] || 0) + 1
    row.total++

    // Workflow alert: in progress but old
    const updated = new Date(c.updatedAt || c.updated_at || c.created_at || c.createdDate || 0)
    const age = (new Date() - updated) / 86400000
    if (status === 'In Progress' && age > 14) workflowAlerts++
  })

  const typeRows = Array.from(types.values()).sort((a, b) => b.total - a.total)
  const largestBucket = typeRows[0]?.type || '-'
  const numTypes = typeRows.length

  // Outliers: items aging faster than baseline (completed in <1 day)
  const outliers = checklists.filter(c => {
    const created = new Date(c.createdDate || c.created_at || 0)
    const completed = new Date(c.completedDate || c.completed_date || c.date_completed || 0)
    if (!c.completedDate && !c.completed_date && !c.date_completed) return false
    return (completed - created) / 86400000 < 0.5
  }).length

  const approved = statusCounts['Finished']

  return { typeRows, statusCounts, numTypes, largestBucket, workflowAlerts, outliers, approved }
}

function useMultiProjectData() {
  const { selectedProjects, activeProject } = useProject()
  const targets = selectedProjects.length > 0 ? selectedProjects : (activeProject ? [activeProject] : [])
  const [checklists, setChecklists] = useState([])
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (!targets.length) return
    setLoading(true)
    Promise.all(targets.map(p => checklistsApi.getAll(p.externalId).then(r => r.data?.data || []).catch(() => [])))
      .then(results => setChecklists(results.flat()))
      .finally(() => setLoading(false))
  }, [targets.map(p => p.externalId).join(',')])

  return { checklists, loading }
}

const StatCard = ({ label, value, sub, color }) => (
  <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, padding: '20px 22px', flex: 1 }}>
    <div style={{ fontSize: 10, fontWeight: 600, color: '#475569', textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 8 }}>{label}</div>
    <div style={{ fontSize: 32, fontWeight: 800, color: color || '#38bdf8', lineHeight: 1, marginBottom: 6 }}>{value}</div>
    {sub && <div style={{ fontSize: 12, color: '#64748b' }}>{sub}</div>}
  </div>
)

export default function ChecklistFlowPage() {
  const { checklists, loading } = useMultiProjectData()
  const { period } = useProject()

  const flow = useMemo(() => computeFlow(checklists), [checklists])

  if (loading) return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 14 }}>
        {[...Array(4)].map((_, i) => (
          <div key={i} style={{ background: 'var(--bg-card)', borderRadius: 12, height: 100, border: '1px solid var(--border)' }} />
        ))}
      </div>
    </div>
  )

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <div>
        <h2 style={{ fontSize: 22, fontWeight: 800, color: 'var(--text-primary)', margin: 0 }}>Checklist Flow</h2>
        <p style={{ fontSize: 13, color: '#64748b', marginTop: 4 }}>Checklist closure mix, workflow drag, and alert concentration.</p>
      </div>

      {/* 4 KPI cards */}
      <div style={{ display: 'flex', gap: 14 }}>
        <StatCard label="Checklist Types" value={flow.numTypes} sub={`${flow.largestBucket} is the largest bucket`} color="#38bdf8" />
        <StatCard label="Workflow Alerts" value={flow.workflowAlerts} sub="Highest age pressure rows" color="#f59e0b" />
        <StatCard label="Outliers" value={flow.outliers} sub="Rows aging faster than baseline" color="#a78bfa" />
        <StatCard label="Approved Flow" value={flow.approved.toLocaleString()} sub="Completed workflow exits" color="#22c55e" />
      </div>

      {/* Two panel layout */}
      <div style={{ display: 'grid', gridTemplateColumns: '1.2fr 1fr', gap: 16 }}>

        {/* Checklist Distribution table */}
        <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 14, overflow: 'hidden' }}>
          <div style={{ padding: '18px 20px 14px' }}>
            <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)' }}>Checklist Distribution</div>
            <div style={{ fontSize: 12, color: '#64748b', marginTop: 2 }}>Checklist mix for {period === 'D' ? 'Daily' : period === 'W' ? 'Weekly' : 'Monthly'} — {['Not Started', 'In Progress', 'Near Complete', 'Finished', 'Total'].join(' / ')}</div>
          </div>
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr style={{ borderTop: '1px solid var(--border)' }}>
                  <th style={{ padding: '10px 20px', textAlign: 'left', fontSize: 11, fontWeight: 600, color: '#475569', textTransform: 'uppercase', letterSpacing: '0.06em' }}>TYPE</th>
                  {[...STATUS_KEYS, 'TOTAL'].map(s => (
                    <th key={s} style={{ padding: '10px 12px', textAlign: 'right', fontSize: 11, fontWeight: 600, color: '#475569', textTransform: 'uppercase', letterSpacing: '0.04em', whiteSpace: 'nowrap' }}>
                      {s.replace(' ', '\u00a0')}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {flow.typeRows.map((row, i) => (
                  <tr key={row.type} style={{ borderTop: '1px solid var(--divider)', background: i % 2 === 1 ? 'var(--row-alt)' : 'transparent' }}>
                    <td style={{ padding: '12px 20px', fontSize: 13, color: 'var(--text-primary)', fontWeight: 500, maxWidth: 220 }}>
                      <div style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{row.type}</div>
                    </td>
                    {STATUS_KEYS.map(s => (
                      <td key={s} style={{ padding: '12px', textAlign: 'right', fontSize: 13, color: row[s] > 0 ? 'var(--text-primary)' : '#334155', fontWeight: row[s] > 0 ? 600 : 400 }}>
                        {row[s] || 0}
                      </td>
                    ))}
                    <td style={{ padding: '12px', textAlign: 'right', fontSize: 13, fontWeight: 700, color: 'var(--text-primary)' }}>{row.total}</td>
                  </tr>
                ))}
                {!flow.typeRows.length && (
                  <tr><td colSpan={6} style={{ padding: '32px', textAlign: 'center', color: '#475569', fontSize: 13 }}>No checklist data. Sync a project.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>

        {/* Checklist Flow States */}
        <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 14, padding: '20px' }}>
          <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)', marginBottom: 4 }}>Checklist Flow States</div>
          <div style={{ fontSize: 12, color: '#64748b', marginBottom: 20 }}>Current workflow accumulation</div>

          <div style={{ display: 'flex', flexDirection: 'column', gap: 0 }}>
            {STATUS_KEYS.map((s) => {
              const count = flow.statusCounts[s] || 0
              const color = STATUS_COLORS[s]
              const total = Object.values(flow.statusCounts).reduce((a, b) => a + b, 0)
              const pct = total > 0 ? +(count / total * 100).toFixed(0) : 0
              return (
                <div key={s} style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '14px 0', borderBottom: '1px solid var(--divider)' }}>
                  <div style={{ width: 11, height: 11, borderRadius: '50%', background: color, flexShrink: 0 }} />
                  <div style={{ flex: 1 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 5 }}>
                      <span style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{s}</span>
                      <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' }}>{count.toLocaleString()}</span>
                    </div>
                    <div style={{ height: 5, background: 'var(--progress-track)', borderRadius: 2.5 }}>
                      <div style={{ height: '100%', borderRadius: 2.5, background: color, width: `${pct}%`, opacity: 0.7, transition: 'width 0.8s ease' }} />
                    </div>
                  </div>
                </div>
              )
            })}
          </div>

          {/* Workflow Alert rows */}
          {flow.workflowAlerts > 0 && (
            <div style={{ marginTop: 18, padding: '12px 14px', background: 'rgba(234,179,8,0.08)', border: '1px solid rgba(234,179,8,0.2)', borderRadius: 8 }}>
              <div style={{ fontSize: 11, fontWeight: 600, color: '#fbbf24', marginBottom: 4 }}>⚠ Workflow Alerts</div>
              <div style={{ fontSize: 12, color: '#94a3b8' }}>
                {flow.workflowAlerts} in-progress checklist{flow.workflowAlerts !== 1 ? 's' : ''} haven't been updated in 14+ days — investigate stalls.
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
