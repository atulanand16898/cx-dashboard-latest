import React, { createContext, useContext, useState, useCallback, useEffect, useRef } from 'react'
import { projectsApi } from '../services/api'
import { apiCache, cacheKeys } from '../services/apiCache'

const ProjectContext = createContext(null)

export function ProjectProvider({ children }) {
  const [projects, setProjects]         = useState([])
  const [activeProject, setActiveProject] = useState(null)
  const [loading, setLoading]           = useState(false)
  const [period, setPeriod]             = useState('W')
  const hasFetchedRef                   = useRef(false)

  const fetchProjects = useCallback(async (force = false) => {
    setLoading(true)
    try {
      const list = await apiCache.get(
        cacheKeys.projects(),
        () => projectsApi.getAll(),
        { ttl: 15 * 60 * 1000, swr: true }
      )
      const arr = Array.isArray(list) ? list : []
      setProjects(arr)
      if (!activeProject && arr.length > 0) setActiveProject(arr[0])
    } catch (err) {
      console.error('Failed to fetch projects', err)
    } finally {
      setLoading(false)
    }
  }, [activeProject])

  // Fetch once on mount
  useEffect(() => {
    if (!hasFetchedRef.current) {
      hasFetchedRef.current = true
      fetchProjects()
    }
  }, [fetchProjects])

  // When active project changes, pre-warm all entity caches for that project
  useEffect(() => {
    if (!activeProject?.externalId) return
    const pid = activeProject.externalId
    // Fire-and-forget prefetch — results will be ready by the time user navigates
    import('../services/api').then(({ checklistsApi, tasksApi, issuesApi }) => {
      apiCache.prefetch([
        [cacheKeys.checklists(pid), () => checklistsApi.getAll(pid)],
        [cacheKeys.tasks(pid),      () => tasksApi.getAll({ projectId: pid })],
        [cacheKeys.issues(pid),     () => issuesApi.getAll(pid)],
      ]).catch(() => {}) // silent — just warming
    })
  }, [activeProject?.externalId])

  const invalidateProject = useCallback((pid) => {
    if (pid) {
      apiCache.invalidate(`checklists-${pid}`)
      apiCache.invalidate(`tasks-${pid}`)
      apiCache.invalidate(`issues-${pid}`)
      apiCache.invalidate(`assets-${pid}`)
      apiCache.invalidate(`persons-${pid}`)
      apiCache.invalidate(`companies-${pid}`)
      apiCache.invalidate(`roles-${pid}`)
      apiCache.invalidate(`dashboard-${pid}`)
    } else {
      apiCache.invalidateAll()
    }
  }, [])

  return (
    <ProjectContext.Provider value={{
      projects,
      activeProject,
      setActiveProject,
      loading,
      fetchProjects,
      period,
      setPeriod,
      invalidateProject,
    }}>
      {children}
    </ProjectContext.Provider>
  )
}

export const useProject = () => useContext(ProjectContext)
