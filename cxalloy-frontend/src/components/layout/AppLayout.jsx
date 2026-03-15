import React, { useEffect } from 'react'
import { Outlet } from 'react-router-dom'
import Sidebar from './Sidebar'
import TopBar from './TopBar'
import { useProject } from '../../context/ProjectContext'
import { useLocation } from 'react-router-dom'

const pageTitles = {
  '/dashboard': { title: 'Overview', subtitle: '3-second pulse for execution health' },
  '/issues': { title: 'Tags & Issues', subtitle: 'Track and manage project issues' },
  '/tasks': { title: 'Tasks', subtitle: 'All project tasks' },
  '/checklists': { title: 'Checklists', subtitle: 'Inspection checklists' },
  '/assets': { title: 'Assets', subtitle: 'Equipment, buildings, systems' },
  '/persons': { title: 'People', subtitle: 'Project personnel' },
  '/companies': { title: 'Companies', subtitle: 'Organizations on the project' },
  '/roles': { title: 'Roles', subtitle: 'Roles and responsibilities' },
  '/sync': { title: 'Sync Center', subtitle: 'Manage data synchronization' },
  '/file-storage': { title: 'File Storage Analysis', subtitle: 'Analyze attachments, detect duplicates, optimize storage' },
}

export default function AppLayout() {
  const { fetchProjects } = useProject()
  const location = useLocation()
  const page = pageTitles[location.pathname] || { title: 'CxAlloy', subtitle: '' }

  useEffect(() => { fetchProjects() }, [])

  return (
    <div className="flex h-screen bg-dark-950 overflow-hidden">
      <Sidebar />
      <div className="flex-1 flex flex-col min-w-0 ml-64 transition-all duration-300">
        <TopBar title={page.title} subtitle={page.subtitle} />
        <main className="flex-1 overflow-y-auto p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
