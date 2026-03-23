import React, { useEffect } from 'react'
import { Outlet } from 'react-router-dom'
import Navbar from './Navbar'
import { useProject } from '../../context/ProjectContext'

export default function AppLayout() {
  const { fetchProjects } = useProject()

  useEffect(() => { fetchProjects() }, [])

  return (
    <div style={{ minHeight: '100vh', background: 'var(--bg-base)', display: 'flex', flexDirection: 'column' }}>
      <Navbar />
      <main style={{ flex: 1, padding: '24px 24px', overflowY: 'auto' }}>
        <Outlet />
      </main>
    </div>
  )
}
