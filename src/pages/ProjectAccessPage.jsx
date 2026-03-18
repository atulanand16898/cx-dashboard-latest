import React, { useEffect, useMemo, useState } from 'react'
import { CheckSquare, Eye, FolderLock, Mail, Plus, RefreshCw, Shield, Square, Trash2, Users } from 'lucide-react'
import toast from 'react-hot-toast'
import { useAuth } from '../context/AuthContext'
import { useProject } from '../context/ProjectContext'
import { personsApi, projectAccessApi } from '../services/api'

function StatCard({ label, value, sub, color }) {
  return (
    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 14, padding: '16px 18px' }}>
      <div style={{ fontSize: 10, fontWeight: 700, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.08em' }}>{label}</div>
      <div style={{ fontSize: 28, fontWeight: 800, color: color || 'var(--text-primary)', lineHeight: 1.1, marginTop: 8 }}>{value}</div>
      <div style={{ fontSize: 12, color: '#64748b', marginTop: 6 }}>{sub}</div>
    </div>
  )
}

export default function ProjectAccessPage() {
  const { isAdmin, refreshUser } = useAuth()
  const { fetchProjects } = useProject()
  const [projects, setProjects] = useState([])
  const [people, setPeople] = useState([])
  const [assignments, setAssignments] = useState([])
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [savingVisibility, setSavingVisibility] = useState(false)
  const [search, setSearch] = useState('')
  const [visibilitySearch, setVisibilitySearch] = useState('')
  const [selectedProjectId, setSelectedProjectId] = useState('')
  const [selectedPersonKey, setSelectedPersonKey] = useState('')
  const [visibleProjectIds, setVisibleProjectIds] = useState([])

  const dedupedPeople = useMemo(() => {
    const map = new Map()
    people.forEach(person => {
      const email = (person.email || '').trim().toLowerCase()
      if (!email || map.has(email)) return
      map.set(email, {
        email,
        personExternalId: person.externalId,
        personName: `${person.firstName || ''} ${person.lastName || ''}`.trim() || email,
        company: person.company || '',
        role: person.role || '',
      })
    })
    return Array.from(map.values()).sort((left, right) => left.personName.localeCompare(right.personName))
  }, [people])

  const assignmentsByProject = useMemo(() => {
    const grouped = new Map()
    assignments.forEach(item => {
      const bucket = grouped.get(item.projectId) || []
      bucket.push(item)
      grouped.set(item.projectId, bucket)
    })
    return grouped
  }, [assignments])

  const filteredProjects = useMemo(() => {
    const q = search.trim().toLowerCase()
    if (!q) return projects
    return projects.filter(project =>
      (project.name || '').toLowerCase().includes(q) ||
      (project.externalId || '').toLowerCase().includes(q) ||
      (project.client || '').toLowerCase().includes(q) ||
      (project.location || '').toLowerCase().includes(q)
    )
  }, [projects, search])

  const visibilityFilteredProjects = useMemo(() => {
    const q = visibilitySearch.trim().toLowerCase()
    if (!q) return projects
    return projects.filter(project =>
      (project.name || '').toLowerCase().includes(q) ||
      (project.externalId || '').toLowerCase().includes(q) ||
      (project.client || '').toLowerCase().includes(q) ||
      (project.location || '').toLowerCase().includes(q)
    )
  }, [projects, visibilitySearch])

  const visibleProjectIdSet = useMemo(() => new Set(visibleProjectIds), [visibleProjectIds])

  const visibleProjects = useMemo(
    () => projects.filter(project => visibleProjectIdSet.has(project.externalId)),
    [projects, visibleProjectIdSet]
  )

  const selectedPerson = dedupedPeople.find(person => person.email === selectedPersonKey)

  async function loadAll() {
    setLoading(true)
    try {
      const [projectsRes, peopleRes, assignmentsRes, visibilityRes] = await Promise.all([
        projectAccessApi.getProjectCatalog(),
        personsApi.getAll(),
        projectAccessApi.getAll(),
        projectAccessApi.getVisibility(),
      ])

      const projectList = [...(projectsRes.data.data || [])].sort((left, right) =>
        (left.name || '').localeCompare(right.name || '')
      )
      const peopleList = peopleRes.data.data || []
      const assignmentList = assignmentsRes.data.data || []
      const visibility = visibilityRes.data.data || {}

      setProjects(projectList)
      setPeople(peopleList)
      setAssignments(assignmentList)
      setVisibleProjectIds(visibility.selectedProjectIds || [])
      if (!selectedProjectId && projectList.length > 0) {
        setSelectedProjectId(projectList[0].externalId)
      }
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to load project access data')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (isAdmin) loadAll()
  }, [isAdmin])

  function toggleVisibleProject(projectId) {
    setVisibleProjectIds(prev => (
      prev.includes(projectId)
        ? prev.filter(id => id !== projectId)
        : [...prev, projectId]
    ))
  }

  function handleShowAllProjects() {
    setVisibleProjectIds([])
  }

  function handleVisibleFilteredSelection() {
    const matchingIds = visibilityFilteredProjects.map(project => project.externalId)
    setVisibleProjectIds(prev => {
      const next = new Set(prev)
      matchingIds.forEach(id => next.add(id))
      return Array.from(next)
    })
  }

  async function handleSaveVisibility() {
    setSavingVisibility(true)
    try {
      const res = await projectAccessApi.saveVisibility({ projectIds: visibleProjectIds })
      const data = res.data.data || {}
      setVisibleProjectIds(data.selectedProjectIds || [])
      await refreshUser()
      await fetchProjects()
      toast.success(
        data.isFiltered
          ? `Admin filters limited to ${data.selectedCount} projects`
          : 'Admin filters reset to all projects'
      )
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to save visible projects')
    } finally {
      setSavingVisibility(false)
    }
  }

  async function handleAssign() {
    if (!selectedProjectId || !selectedPerson) {
      return toast.error('Choose a project and a person first')
    }
    setSaving(true)
    try {
      await projectAccessApi.assign({
        projectId: selectedProjectId,
        personExternalId: selectedPerson.personExternalId,
        personEmail: selectedPerson.email,
        personName: selectedPerson.personName,
      })
      setSelectedPersonKey('')
      await loadAll()
      toast.success('Project access assigned')
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to assign access')
    } finally {
      setSaving(false)
    }
  }

  async function handleRemove(id) {
    try {
      await projectAccessApi.remove(id)
      setAssignments(prev => prev.filter(item => item.id !== id))
      toast.success('Access removed')
    } catch (err) {
      toast.error(err.response?.data?.message || 'Failed to remove access')
    }
  }

  if (!isAdmin) {
    return (
      <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 16, padding: '48px 32px', textAlign: 'center' }}>
        <Shield size={34} style={{ color: '#64748b', marginBottom: 14 }} />
        <div style={{ fontSize: 18, fontWeight: 800, color: 'var(--text-primary)' }}>Admin access required</div>
        <div style={{ fontSize: 13, color: '#64748b', marginTop: 8 }}>Only the admin account can manage project-level authorization.</div>
      </div>
    )
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
      <div>
        <h2 style={{ fontSize: 22, fontWeight: 800, color: 'var(--text-primary)', margin: 0 }}>Project Access</h2>
        <p style={{ fontSize: 13, color: '#64748b', marginTop: 4 }}>
          Assign synced people to projects. `admin / admin123` can also be limited to a saved subset of visible projects, while email users only see projects explicitly assigned to their email.
        </p>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', gap: 14 }}>
        <StatCard label="Projects" value={projects.length} sub="Available in the workspace" color="#60a5fa" />
        <StatCard label="People" value={dedupedPeople.length} sub="Unique emails from synced People" color="#22c55e" />
        <StatCard label="Assignments" value={assignments.length} sub="Active project/email mappings" color="#a78bfa" />
        <StatCard label="Visible To Admin" value={visibleProjectIds.length || 'All'} sub={visibleProjectIds.length ? 'Projects shown across admin filters' : 'All projects currently visible'} color="#f59e0b" />
      </div>

      <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 16, padding: 18, display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 16, flexWrap: 'wrap' }}>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--text-primary)', fontSize: 16, fontWeight: 800 }}>
              <Eye size={16} />
              Admin Project Visibility
            </div>
            <div style={{ fontSize: 12, color: '#64748b', marginTop: 6 }}>
              Choose the projects the admin account should see across the main project selector and filters. Leave this empty to keep admin access open to all projects.
            </div>
          </div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <button onClick={handleVisibleFilteredSelection} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '10px 12px', borderRadius: 10, border: '1px solid var(--border)', background: 'var(--bg-base)', color: '#94a3b8', cursor: 'pointer' }}>
              <CheckSquare size={14} />
              Add Search Results
            </button>
            <button onClick={handleShowAllProjects} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '10px 12px', borderRadius: 10, border: '1px solid var(--border)', background: 'var(--bg-base)', color: '#94a3b8', cursor: 'pointer' }}>
              <Square size={14} />
              Show All
            </button>
            <button onClick={handleSaveVisibility} disabled={savingVisibility} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '10px 14px', borderRadius: 10, border: 'none', background: '#2563eb', color: '#fff', fontSize: 12, fontWeight: 700, cursor: 'pointer', opacity: savingVisibility ? 0.7 : 1 }}>
              <Eye size={14} />
              Save Visible Projects
            </button>
          </div>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1.25fr 0.75fr', gap: 14 }}>
          <div style={{ background: 'var(--bg-base)', border: '1px solid var(--border)', borderRadius: 14, padding: 14, maxHeight: 320, overflowY: 'auto' }}>
            <div style={{ marginBottom: 10 }}>
              <input
                value={visibilitySearch}
                onChange={e => setVisibilitySearch(e.target.value)}
                placeholder="Search projects to add to admin view"
                style={{ width: '100%', padding: '10px 12px', borderRadius: 10, background: 'rgba(15,23,42,0.75)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, outline: 'none' }}
              />
            </div>
            <div style={{ display: 'grid', gap: 8 }}>
              {visibilityFilteredProjects.map(project => {
                const isVisible = visibleProjectIdSet.has(project.externalId)
                return (
                  <label key={project.id} style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'center', padding: '11px 12px', borderRadius: 12, border: `1px solid ${isVisible ? 'rgba(37,99,235,0.35)' : 'var(--border)'}`, background: isVisible ? 'rgba(37,99,235,0.08)' : 'transparent', cursor: 'pointer' }}>
                    <div style={{ minWidth: 0 }}>
                      <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)' }}>{project.name}</div>
                      <div style={{ fontSize: 11, color: '#64748b', marginTop: 4 }}>
                        {project.externalId} • {project.client || 'No client'} • {project.location || 'No location'}
                      </div>
                    </div>
                    <input
                      type="checkbox"
                      checked={isVisible}
                      onChange={() => toggleVisibleProject(project.externalId)}
                      style={{ width: 16, height: 16, accentColor: '#2563eb', flexShrink: 0 }}
                    />
                  </label>
                )
              })}
            </div>
          </div>

          <div style={{ background: 'var(--bg-base)', border: '1px solid var(--border)', borderRadius: 14, padding: 14, display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.08em' }}>Saved Visibility</div>
              <div style={{ fontSize: 26, fontWeight: 800, color: '#f59e0b', marginTop: 8 }}>{visibleProjectIds.length || 'All'}</div>
              <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>
                {visibleProjectIds.length ? 'Projects currently visible to admin' : 'No saved limit. Admin can see every project.'}
              </div>
            </div>

            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignContent: 'flex-start' }}>
              {visibleProjects.length ? visibleProjects.map(project => (
                <button key={project.id} onClick={() => toggleVisibleProject(project.externalId)} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '8px 10px', borderRadius: 999, border: '1px solid rgba(37,99,235,0.22)', background: 'rgba(37,99,235,0.12)', color: '#93c5fd', fontSize: 11, fontWeight: 700, cursor: 'pointer' }}>
                  {project.name}
                </button>
              )) : (
                <div style={{ fontSize: 12, color: '#64748b' }}>
                  All projects are currently visible to admin. Tick a few projects on the left, then save to reduce the global project list.
                </div>
              )}
            </div>
          </div>
        </div>
      </div>

      <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 16, padding: '16px 18px', display: 'grid', gridTemplateColumns: '1.1fr 1fr 1fr auto', gap: 10, alignItems: 'end' }}>
        <div>
          <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 6 }}>Project</div>
          <select value={selectedProjectId} onChange={e => setSelectedProjectId(e.target.value)} style={{ width: '100%', padding: '10px 12px', borderRadius: 10, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12 }}>
            <option value="">Select project</option>
            {projects.map(project => (
              <option key={project.id} value={project.externalId}>
                {project.name} ({project.externalId})
              </option>
            ))}
          </select>
        </div>
        <div>
          <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 6 }}>Person</div>
          <select value={selectedPersonKey} onChange={e => setSelectedPersonKey(e.target.value)} style={{ width: '100%', padding: '10px 12px', borderRadius: 10, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12 }}>
            <option value="">Select person</option>
            {dedupedPeople.map(person => (
              <option key={person.email} value={person.email}>
                {person.personName} - {person.email}
              </option>
            ))}
          </select>
        </div>
        <div>
          <div style={{ fontSize: 11, fontWeight: 700, color: '#64748b', marginBottom: 6 }}>Search projects</div>
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search by name, client, location"
            style={{ width: '100%', padding: '10px 12px', borderRadius: 10, background: 'var(--bg-base)', border: '1px solid var(--border)', color: 'var(--text-primary)', fontSize: 12, outline: 'none' }}
          />
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <button onClick={loadAll} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '10px 12px', borderRadius: 10, border: '1px solid var(--border)', background: 'var(--bg-base)', color: '#94a3b8', cursor: 'pointer' }}>
            <RefreshCw size={14} />
          </button>
          <button onClick={handleAssign} disabled={saving} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '10px 14px', borderRadius: 10, border: 'none', background: '#2563eb', color: '#fff', fontSize: 12, fontWeight: 700, cursor: 'pointer', opacity: saving ? 0.7 : 1 }}>
            <Plus size={14} />
            Assign
          </button>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 16 }}>
        {loading ? (
          [...Array(4)].map((_, index) => (
            <div key={index} style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 16, height: 180 }} />
          ))
        ) : filteredProjects.map(project => {
          const projectAssignments = assignmentsByProject.get(project.externalId) || []
          return (
            <div key={project.id} style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 16, padding: '18px 18px 16px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'flex-start', marginBottom: 14 }}>
                <div>
                  <div style={{ fontSize: 16, fontWeight: 800, color: 'var(--text-primary)' }}>{project.name}</div>
                  <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>
                    {project.client || 'No client'} • {project.location || 'No location'} • {project.externalId}
                  </div>
                </div>
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
                  {visibleProjectIdSet.has(project.externalId) ? (
                    <div style={{ padding: '6px 10px', borderRadius: 999, background: 'rgba(245,158,11,0.12)', border: '1px solid rgba(245,158,11,0.22)', color: '#fcd34d', fontSize: 11, fontWeight: 700 }}>
                      Visible to admin
                    </div>
                  ) : null}
                  <div style={{ padding: '6px 10px', borderRadius: 999, background: 'rgba(37,99,235,0.12)', border: '1px solid rgba(37,99,235,0.22)', color: '#93c5fd', fontSize: 11, fontWeight: 700 }}>
                    {projectAssignments.length} assigned
                  </div>
                </div>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, minmax(0, 1fr))', gap: 10, marginBottom: 14 }}>
                <div style={{ padding: '12px 14px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 7, color: '#64748b', fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.08em' }}>
                    <FolderLock size={13} /> Access
                  </div>
                  <div style={{ fontSize: 26, fontWeight: 800, color: '#60a5fa', marginTop: 8 }}>{projectAssignments.length}</div>
                  <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>Emails allowed into this project</div>
                </div>
                <div style={{ padding: '12px 14px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 7, color: '#64748b', fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.08em' }}>
                    <Users size={13} /> Source
                  </div>
                  <div style={{ fontSize: 26, fontWeight: 800, color: '#22c55e', marginTop: 8 }}>{people.filter(person => person.projectId === project.externalId && person.email).length}</div>
                  <div style={{ fontSize: 12, color: '#64748b', marginTop: 4 }}>People synced on this project</div>
                </div>
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {projectAssignments.length ? projectAssignments.map(item => (
                  <div key={item.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, padding: '10px 12px', borderRadius: 12, background: 'var(--bg-base)', border: '1px solid var(--border)' }}>
                    <div style={{ minWidth: 0 }}>
                      <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-primary)' }}>{item.personName || item.personEmail}</div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11, color: '#64748b', marginTop: 3 }}>
                        <Mail size={11} />
                        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{item.personEmail}</span>
                      </div>
                    </div>
                    <button onClick={() => handleRemove(item.id)} style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: 34, height: 34, borderRadius: 10, border: '1px solid rgba(239,68,68,0.2)', background: 'rgba(239,68,68,0.08)', color: '#f87171', cursor: 'pointer', flexShrink: 0 }}>
                      <Trash2 size={14} />
                    </button>
                  </div>
                )) : (
                  <div style={{ padding: '16px 12px', borderRadius: 12, border: '1px dashed var(--border)', color: '#64748b', fontSize: 12 }}>
                    No one assigned yet. Choose this project above and assign a person to enable email login access.
                  </div>
                )}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
