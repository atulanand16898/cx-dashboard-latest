import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { projectsApi, xerProcessingApi } from '../services/api'
import { useAuth } from './AuthContext'
import { useSyncRefreshSignal } from '../hooks/useSyncRefreshSignal'

const ProjectContext = createContext(null)

function getProjectIdentity(project) {
  return project?.id ?? project?.externalId ?? null
}

function dedupeProjects(projects) {
  return (projects || []).filter((project, index, list) => {
    const identity = getProjectIdentity(project)
    if (identity == null) return false
    return list.findIndex((candidate) => getProjectIdentity(candidate) === identity) === index
  })
}

function orderWithPrimary(projects, primaryProject) {
  const normalized = dedupeProjects(projects)
  if (!normalized.length) return []

  const primaryId = getProjectIdentity(primaryProject)
  if (!primaryId) return normalized

  const primary = normalized.find((project) => getProjectIdentity(project) === primaryId)
  if (!primary) return normalized

  return [primary, ...normalized.filter((project) => getProjectIdentity(project) !== primaryId)]
}

export function ProjectProvider({ children }) {
  const { isAuthenticated, provider } = useAuth()
  const refreshSignal = useSyncRefreshSignal()
  const [projects, setProjects] = useState([])
  const [activeProjectState, setActiveProjectState] = useState(null)
  const [selectedProjectsState, setSelectedProjectsState] = useState([])
  const [loading, setLoading] = useState(false)
  // D/W/M period toggle - shared across all pages
  const [period, setPeriod] = useState('Overall')
  const activeProjectRef = useRef(null)
  const selectedProjectsRef = useRef([])
  const fetchRequestRef = useRef(0)

  useEffect(() => {
    activeProjectRef.current = activeProjectState
  }, [activeProjectState])

  useEffect(() => {
    selectedProjectsRef.current = selectedProjectsState
  }, [selectedProjectsState])

  const clearProjects = useCallback(() => {
    setProjects([])
    setActiveProjectState(null)
    setSelectedProjectsState([])
  }, [])

  const setSelectedProjects = useCallback((nextProjects) => {
    const normalized = orderWithPrimary(nextProjects || [], activeProjectRef.current)
    setSelectedProjectsState(normalized)

    if (!normalized.length) {
      setActiveProjectState(null)
      return
    }

    const activeId = getProjectIdentity(activeProjectRef.current)
    const activeStillSelected = normalized.some((project) => getProjectIdentity(project) === activeId)
    if (!activeStillSelected) {
      setActiveProjectState(normalized[0])
    }
  }, [])

  const setActiveProject = useCallback((project) => {
    setActiveProjectState(project || null)

    if (!project) {
      setSelectedProjectsState([])
      return
    }

    setSelectedProjectsState((current) => {
      const projectId = getProjectIdentity(project)
      const exists = current.some((item) => getProjectIdentity(item) === projectId)
      const next = exists
        ? current.map((item) => getProjectIdentity(item) === projectId ? project : item)
        : [project, ...current]
      return orderWithPrimary(next, project)
    })
  }, [])

  const fetchProjects = useCallback(async () => {
    const requestId = ++fetchRequestRef.current
    setLoading(true)
    try {
      const response = provider === 'primavera'
        ? await xerProcessingApi.listProjects()
        : await projectsApi.getAll()
      if (requestId !== fetchRequestRef.current) return

      const list = response.data.data || []
      const previousActive = activeProjectRef.current
      const previousSelected = selectedProjectsRef.current
      setProjects(list)

      if (!list.length) {
        clearProjects()
        return
      }

      const projectById = new Map(list.map((project) => [getProjectIdentity(project), project]))
      const nextActive = projectById.get(getProjectIdentity(previousActive)) || list[0]
      const nextSelected = previousSelected
        .map((project) => projectById.get(getProjectIdentity(project)))
        .filter(Boolean)

      setActiveProjectState(nextActive)
      setSelectedProjectsState(orderWithPrimary(
        nextSelected.length ? nextSelected : [nextActive],
        nextActive
      ))
    } finally {
      if (requestId === fetchRequestRef.current) {
        setLoading(false)
      }
    }
  }, [clearProjects, provider])

  useEffect(() => {
    fetchRequestRef.current += 1
    clearProjects()
    if (!isAuthenticated) {
      setLoading(false)
      return
    }

    fetchProjects().catch(() => {
      clearProjects()
      setLoading(false)
    })
  }, [clearProjects, fetchProjects, isAuthenticated, provider])

  useEffect(() => {
    if (!isAuthenticated) return
    fetchProjects().catch(() => {})
  }, [fetchProjects, isAuthenticated, refreshSignal])

  const toggleProject = useCallback((project) => {
    if (!project) {
      setActiveProjectState(null)
      setSelectedProjectsState([])
      return
    }

    const projectId = getProjectIdentity(project)

    setSelectedProjectsState((current) => {
      const exists = current.some((item) => getProjectIdentity(item) === projectId)
      if (!exists) {
        const nextPrimary = activeProjectRef.current || project
        if (!activeProjectRef.current) {
          setActiveProjectState(project)
        }
        return orderWithPrimary([...current, project], nextPrimary)
      }

      const next = current.filter((item) => getProjectIdentity(item) !== projectId)
      if (!next.length) {
        setActiveProjectState(null)
        return []
      }

      if (getProjectIdentity(activeProjectRef.current) === projectId) {
        setActiveProjectState(next[0])
        return orderWithPrimary(next, next[0])
      }

      return orderWithPrimary(next, activeProjectRef.current)
    })
  }, [])

  const selectOnlyProject = useCallback((project) => {
    setActiveProject(project)
  }, [setActiveProject])

  const clearSelection = useCallback(() => {
    setSelectedProjectsState([])
    setActiveProjectState(null)
  }, [])

  const activeProject = activeProjectState
  const selectedProjects = selectedProjectsState
  const primaryProject = useMemo(() => activeProject || selectedProjects[0] || null, [activeProject, selectedProjects])
  const scopeProjects = useMemo(() => (
    selectedProjects.length > 0
      ? orderWithPrimary(selectedProjects, primaryProject)
      : (primaryProject ? [primaryProject] : [])
  ), [primaryProject, selectedProjects])
  const scopeProjectIds = useMemo(() => (
    scopeProjects
      .map((project) => project?.externalId || project?.id)
      .filter(Boolean)
  ), [scopeProjects])
  const isMultiProject = scopeProjects.length > 1
  const contextValue = useMemo(() => ({
    projects,
    activeProject,
    setActiveProject,
    selectedProjects,
    setSelectedProjects,
    primaryProject,
    scopeProjects,
    scopeProjectIds,
    isMultiProject,
    toggleProject,
    selectOnlyProject,
    clearSelection,
    loading,
    fetchProjects,
    period,
    setPeriod,
  }), [
    activeProject,
    clearSelection,
    fetchProjects,
    isMultiProject,
    loading,
    period,
    primaryProject,
    projects,
    scopeProjectIds,
    scopeProjects,
    selectOnlyProject,
    selectedProjects,
    setActiveProject,
    setSelectedProjects,
    toggleProject,
  ])

  return (
    <ProjectContext.Provider value={contextValue}>
      {children}
    </ProjectContext.Provider>
  )
}

export const useProject = () => useContext(ProjectContext)
