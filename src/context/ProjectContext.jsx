import React, { createContext, useContext, useState, useCallback } from 'react'
import { projectsApi } from '../services/api'

const ProjectContext = createContext(null)

export function ProjectProvider({ children }) {
  const [projects, setProjects] = useState([])
  const [activeProject, setActiveProject] = useState(null)
  const [selectedProjects, setSelectedProjects] = useState([])
  const [loading, setLoading] = useState(false)
  // D/W/M period toggle — shared across all pages
  const [period, setPeriod] = useState('Overall')

  const fetchProjects = useCallback(async () => {
    setLoading(true)
    try {
      const res = await projectsApi.getAll()
      const list = res.data.data || []
      setProjects(list)
      if (list.length > 0 && !activeProject) {
        setActiveProject(list[0])
        setSelectedProjects([list[0]])
      }
    } finally {
      setLoading(false)
    }
  }, [activeProject])

  const toggleProject = useCallback((project) => {
    setSelectedProjects(prev => {
      const isSelected = prev.some(p => p.id === project.id)
      const next = isSelected ? prev.filter(p => p.id !== project.id) : [...prev, project]
      if (!isSelected) setActiveProject(project)
      else if (next.length > 0) setActiveProject(next[next.length - 1])
      return next
    })
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
