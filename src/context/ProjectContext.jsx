import React, { createContext, useContext, useState, useCallback, useEffect, useRef } from 'react'
import { projectsApi, xerProcessingApi } from '../services/api'
import { useAuth } from './AuthContext'

const ProjectContext = createContext(null)

export function ProjectProvider({ children }) {
  const { isAuthenticated, provider } = useAuth()
  const [projects, setProjects] = useState([])
  const [activeProject, setActiveProject] = useState(null)
  const [selectedProjects, setSelectedProjects] = useState([])
  const [loading, setLoading] = useState(false)
  // D/W/M period toggle — shared across all pages
  const [period, setPeriod] = useState('Overall')
  const activeProjectRef = useRef(null)
  const selectedProjectsRef = useRef([])
  const fetchRequestRef = useRef(0)

  useEffect(() => {
    activeProjectRef.current = activeProject
  }, [activeProject])

  useEffect(() => {
    selectedProjectsRef.current = selectedProjects
  }, [selectedProjects])

  const clearProjects = useCallback(() => {
    setProjects([])
    setActiveProject(null)
    setSelectedProjects([])
  }, [])

  const fetchProjects = useCallback(async () => {
    const requestId = ++fetchRequestRef.current
    setLoading(true)
    try {
      const res = provider === 'primavera'
        ? await xerProcessingApi.listProjects()
        : await projectsApi.getAll()
      if (requestId !== fetchRequestRef.current) return
      const list = res.data.data || []
      const previousActive = activeProjectRef.current
      const previousSelected = selectedProjectsRef.current
      setProjects(list)
      if (list.length === 0) {
        clearProjects()
        return
      }

      const stillActive = previousActive && list.some(project => project.id === previousActive.id)
      if (!stillActive) {
        setActiveProject(list[0])
      }

      setSelectedProjects(() => {
        const filtered = previousSelected.filter(selected => list.some(project => project.id === selected.id))
        if (filtered.length > 0) return [filtered[0]]
        return stillActive ? [previousActive] : [list[0]]
      })
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

  const toggleProject = useCallback((project) => {
    setActiveProject(project)
    setSelectedProjects(project ? [project] : [])
  }, [])

  const clearSelection = useCallback(() => {
    setSelectedProjects([])
    setActiveProject(null)
  }, [])

  return (
    <ProjectContext.Provider value={{
      projects, activeProject, setActiveProject,
      selectedProjects, setSelectedProjects,
      toggleProject, clearSelection,
      loading, fetchProjects,
      period, setPeriod,
    }}>
      {children}
    </ProjectContext.Provider>
  )
}

export const useProject = () => useContext(ProjectContext)
