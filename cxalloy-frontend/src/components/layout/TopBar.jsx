import React from 'react'
import { RefreshCw, Bell, Calendar, Clock } from 'lucide-react'
import { useProject } from '../../context/ProjectContext'
import { syncApi } from '../../services/api'
import toast from 'react-hot-toast'

function getWeek() {
  const now = new Date()
  const start = new Date(now.getFullYear(), 0, 1)
  const week = Math.ceil(((now - start) / 86400000 + start.getDay() + 1) / 7)
  return `WEEK ${now.getFullYear()}-W${String(week).padStart(2, '0')}`
}

export default function TopBar({ title, subtitle }) {
  const { activeProject, fetchProjects } = useProject()
  const now = new Date()
  const dateStr = now.toLocaleDateString('en-US', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })

  const handleSync = async () => {
    if (!activeProject) return toast.error('Select a project first')
    const toastId = toast.loading(`Syncing ${activeProject.name}...`)
    try {
      await syncApi.syncProject(activeProject.externalId)
      await fetchProjects()
      toast.success('Project synced!', { id: toastId })
    } catch (e) {
      toast.error(e.response?.data?.message || 'Sync failed', { id: toastId })
    }
  }

  return (
    <header className="h-16 flex items-center justify-between px-6 border-b border-white/[0.06] bg-dark-950/80 backdrop-blur-sm sticky top-0 z-10">
      <div>
        <h1 className="text-base font-700 text-white tracking-tight">{title}</h1>
        {subtitle && <p className="text-[11px] text-dark-400">{subtitle}</p>}
      </div>

      <div className="flex items-center gap-3">
        {/* Week & Date */}
        <div className="hidden md:flex items-center gap-4 text-xs text-dark-400">
          <div className="flex items-center gap-1.5">
            <Clock size={12} className="text-dark-500" />
            <span className="font-mono">{getWeek()}</span>
          </div>
          <div className="flex items-center gap-1.5">
            <Calendar size={12} className="text-dark-500" />
            <span>{dateStr}</span>
          </div>
        </div>

        <div className="h-5 w-px bg-white/[0.08]" />

        {/* Sync button */}
        {activeProject && (
          <button onClick={handleSync} className="btn-primary text-xs py-1.5 px-3">
            <RefreshCw size={13} />
            Sync Project
          </button>
        )}

        {/* Notifications */}
        <button className="relative w-9 h-9 rounded-lg border border-white/[0.08] bg-dark-900/60 flex items-center justify-center text-dark-400 hover:text-white hover:border-white/[0.15] transition-all">
          <Bell size={16} />
          <span className="absolute top-1.5 right-1.5 w-1.5 h-1.5 bg-sky-400 rounded-full" />
        </button>
      </div>
    </header>
  )
}
