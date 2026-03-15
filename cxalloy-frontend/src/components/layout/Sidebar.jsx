import React, { useState } from 'react'
import { NavLink, useNavigate } from 'react-router-dom'
import {
  LayoutDashboard, Tag, AlertCircle, CheckSquare,
  Server, Users, Building2, UserCog, RefreshCw,
  ChevronDown, LogOut, Activity, Settings, Database,
  Menu, X, ChevronRight, HardDrive
} from 'lucide-react'
import { useAuth } from '../../context/AuthContext'
import { useProject } from '../../context/ProjectContext'

const navItems = [
  { label: 'Overview', icon: LayoutDashboard, to: '/dashboard' },
  { label: 'Tags & Issues', icon: AlertCircle, to: '/issues' },
  { label: 'Tasks', icon: CheckSquare, to: '/tasks' },
  { label: 'Checklists', icon: Tag, to: '/checklists' },
  { label: 'Assets', icon: Server, to: '/assets' },
  { label: 'People', icon: Users, to: '/persons' },
  { label: 'Companies', icon: Building2, to: '/companies' },
  { label: 'Roles', icon: UserCog, to: '/roles' },
  { label: 'File Storage', icon: HardDrive, to: '/file-storage' },
  { label: 'Sync Center', icon: RefreshCw, to: '/sync' },
]

export default function Sidebar() {
  const { user, logout } = useAuth()
  const { projects, activeProject, setActiveProject } = useProject()
  const [collapsed, setCollapsed] = useState(false)
  const [projectOpen, setProjectOpen] = useState(false)
  const navigate = useNavigate()

  const handleLogout = async () => {
    await logout()
    navigate('/login')
  }

  return (
    <>
      {/* Mobile overlay */}
      <div className={`fixed inset-0 bg-black/60 z-20 lg:hidden ${collapsed ? 'hidden' : ''}`}
        onClick={() => setCollapsed(true)} />

      <aside className={`
        fixed top-0 left-0 h-screen z-30
        flex flex-col
        bg-dark-950 border-r border-white/[0.06]
        transition-all duration-300 ease-in-out
        ${collapsed ? 'w-16' : 'w-64'}
      `}>
        {/* Logo */}
        <div className="flex items-center justify-between px-4 py-5 border-b border-white/[0.06]">
          {!collapsed && (
            <div className="flex items-center gap-2.5">
              <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-sky-400 to-blue-600 flex items-center justify-center shadow-lg shadow-sky-500/25">
                <Activity size={16} className="text-white" />
              </div>
              <div>
                <div className="text-sm font-700 text-white tracking-tight">CxAlloy</div>
                <div className="text-[10px] text-dark-400 tracking-widest uppercase">Project Track</div>
              </div>
            </div>
          )}
          {collapsed && (
            <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-sky-400 to-blue-600 flex items-center justify-center shadow-lg shadow-sky-500/25 mx-auto">
              <Activity size={16} className="text-white" />
            </div>
          )}
          {!collapsed && (
            <button onClick={() => setCollapsed(true)} className="text-dark-400 hover:text-white transition-colors p-1">
              <X size={16} />
            </button>
          )}
        </div>

        {!collapsed && (
          <div className="px-3 py-3 border-b border-white/[0.06]">
            <div className="text-[10px] text-dark-500 uppercase tracking-widest font-600 mb-1.5 px-2">Active Project</div>
            <button
              onClick={() => setProjectOpen(!projectOpen)}
              className="w-full flex items-center justify-between px-3 py-2.5 rounded-10 bg-dark-800/60 border border-white/[0.06] hover:border-white/[0.12] transition-all group"
            >
              <div className="text-left">
                <div className="text-xs font-600 text-white truncate max-w-[140px]">
                  {activeProject?.name || 'Select Project'}
                </div>
                {activeProject?.externalId && (
                  <div className="text-[10px] text-dark-400 font-mono">{activeProject.externalId}</div>
                )}
              </div>
              <ChevronDown size={14} className={`text-dark-400 transition-transform ${projectOpen ? 'rotate-180' : ''}`} />
            </button>

            {projectOpen && projects.length > 0 && (
              <div className="mt-1.5 max-h-48 overflow-y-auto rounded-10 bg-dark-900 border border-white/[0.08] shadow-xl">
                {projects.map(p => (
                  <button
                    key={p.id}
                    onClick={() => { setActiveProject(p); setProjectOpen(false) }}
                    className={`w-full text-left px-3 py-2 text-xs hover:bg-dark-800 transition-colors first:rounded-t-10 last:rounded-b-10
                      ${activeProject?.id === p.id ? 'text-sky-400 bg-sky-500/10' : 'text-dark-200'}`}
                  >
                    <div className="font-500 truncate">{p.name}</div>
                    <div className="text-dark-500 font-mono text-[10px]">{p.externalId}</div>
                  </button>
                ))}
              </div>
            )}
          </div>
        )}

        {/* Nav items */}
        <nav className="flex-1 px-2 py-3 overflow-y-auto">
          {!collapsed && (
            <div className="text-[10px] text-dark-500 uppercase tracking-widest font-600 mb-2 px-2">Navigation</div>
          )}
          <div className="space-y-0.5">
            {navItems.map(({ label, icon: Icon, to }) => (
              <NavLink
                key={to}
                to={to}
                className={({ isActive }) =>
                  `nav-link ${isActive ? 'active' : ''} ${collapsed ? 'justify-center px-0' : ''}`
                }
                title={collapsed ? label : undefined}
              >
                <Icon size={17} className="flex-shrink-0" />
                {!collapsed && <span>{label}</span>}
              </NavLink>
            ))}
          </div>
        </nav>

        {/* Bottom */}
        <div className="px-2 py-3 border-t border-white/[0.06] space-y-0.5">
          {collapsed && (
            <button
              onClick={() => setCollapsed(false)}
              className="w-full flex items-center justify-center p-2 rounded-lg text-dark-400 hover:text-white hover:bg-white/5 transition-colors"
            >
              <ChevronRight size={16} />
            </button>
          )}
          {!collapsed && (
            <div className="px-3 py-2.5 rounded-10 bg-dark-900/60 border border-white/[0.05] mb-2">
              <div className="text-xs font-600 text-white">{user?.username}</div>
              <div className="text-[10px] text-dark-500">Administrator</div>
            </div>
          )}
          <button
            onClick={handleLogout}
            className={`w-full flex items-center gap-2.5 px-3 py-2 rounded-lg text-dark-400 hover:text-red-400 hover:bg-red-500/5 transition-all text-sm font-500
              ${collapsed ? 'justify-center' : ''}`}
            title={collapsed ? 'Logout' : undefined}
          >
            <LogOut size={16} />
            {!collapsed && <span>Logout</span>}
          </button>
        </div>
      </aside>

      {/* Collapse toggle for mobile */}
      <button
        onClick={() => setCollapsed(!collapsed)}
        className="fixed top-4 left-4 z-40 lg:hidden w-9 h-9 rounded-lg bg-dark-900 border border-white/10 flex items-center justify-center text-white shadow-lg"
      >
        <Menu size={18} />
      </button>
    </>
  )
}
