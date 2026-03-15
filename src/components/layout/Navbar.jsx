import React, { useState, useRef, useEffect } from 'react'
import { NavLink, useNavigate, useLocation } from 'react-router-dom'
import { Activity, ChevronDown } from 'lucide-react'
import { useAuth } from '../../context/AuthContext'
import { useTheme } from '../../context/ThemeContext'
import { useProject } from '../../context/ProjectContext'

// Primary landing-page tabs — always visible in main navbar
// Order matches modum.me exactly
const PRIMARY_TABS = [
  { label: 'Tracker Pulse',    to: '/tracker-pulse' },
  { label: 'Planned vs Actual', to: '/planned-vs-actual' },
  { label: 'Checklist Flow',   to: '/checklist-flow' },
  { label: 'Issue Radar',      to: '/issue-radar' },
  { label: 'Asset Readiness',  to: '/asset-readiness' },
  { label: 'Tracker Briefs',   to: '/tracker-briefs' },
]

// Project-context tabs — shown in secondary bar only when a project is selected
const PROJECT_TABS = [
  { label: 'Tasks',      to: '/tasks' },
  { label: 'Checklists', to: '/checklists' },
  { label: 'Equipment',  to: '/equipment' },
  { label: 'People',     to: '/persons' },
  { label: 'Companies',  to: '/companies' },
  { label: 'Roles',      to: '/roles' },
  { label: 'Sync',       to: '/sync' },
]

function TabLink({ to, label, compact }) {
  return (
    <NavLink
      to={to}
      style={({ isActive }) => ({
        padding: compact ? '5px 10px' : '6px 14px',
        borderRadius: 8,
        fontSize: compact ? 12 : 13,
        fontWeight: 500,
        color: isActive ? 'white' : '#64748b',
        background: isActive ? '#0ea5e9' : 'transparent',
        textDecoration: 'none',
        transition: 'all 0.18s ease',
        whiteSpace: 'nowrap',
        flexShrink: 0,
      })}
      onMouseEnter={e => {
        if (!e.currentTarget.style.background.includes('0ea5e9')) {
          e.currentTarget.style.color = '#cbd5e1'
        }
      }}
      onMouseLeave={e => {
        if (!e.currentTarget.style.background.includes('0ea5e9')) {
          e.currentTarget.style.color = '#64748b'
        }
      }}
    >
      {label}
    </NavLink>
  )
}

export default function Navbar() {
  const { logout } = useAuth()
  const { isDark, toggleTheme } = useTheme()
  const { activeProject } = useProject()
  const navigate = useNavigate()
  const location = useLocation()

  // Detect if current page is a project-context page
  const isProjectPage = PROJECT_TABS.some(t => location.pathname === t.to)
  const hasProject = !!activeProject

  return (
    <header style={{
      background: '#0d1628',
      borderBottom: '1px solid rgba(255,255,255,0.07)',
      position: 'sticky', top: 0, zIndex: 40,
    }}>
      {/* ── Row 1: Logo + Primary Tabs + Controls ── */}
      <div style={{ display: 'flex', alignItems: 'center', height: 54, padding: '0 20px', gap: 0 }}>

        {/* Logo */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginRight: 24, flexShrink: 0 }}>
          <div style={{
            width: 34, height: 34, borderRadius: 9,
            background: 'linear-gradient(135deg, #0ea5e9, #2563eb)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            boxShadow: '0 2px 12px rgba(14,165,233,0.35)',
          }}>
            <Activity size={16} color="white" />
          </div>
          <div>
            <div style={{ fontSize: 13, fontWeight: 700, color: 'white', lineHeight: 1.2 }}>MODEM IQ</div>
            <div style={{ fontSize: 9.5, color: '#475569', lineHeight: 1.2 }}>Data-Driven Project Decisions</div>
          </div>
        </div>

        {/* Primary nav tabs */}
        <nav style={{ display: 'flex', alignItems: 'center', gap: 2, flex: 1, overflow: 'hidden' }}>
          {PRIMARY_TABS.map(tab => (
            <TabLink key={tab.to} to={tab.to} label={tab.label} />
          ))}
        </nav>

        {/* Right controls: theme toggle + sign out */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexShrink: 0, marginLeft: 12 }}>
          {/* Theme toggle pill */}
          <button
            onClick={toggleTheme}
            title={`Switch to ${isDark ? 'light' : 'dark'} mode`}
            style={{
              display: 'flex', alignItems: 'center', gap: 6,
              background: 'none', border: 'none', cursor: 'pointer', padding: '4px 8px',
              borderRadius: 8,
              transition: 'background 0.15s',
            }}
            onMouseEnter={e => e.currentTarget.style.background = 'rgba(255,255,255,0.05)'}
            onMouseLeave={e => e.currentTarget.style.background = 'none'}
          >
            <span style={{ fontSize: 12, fontWeight: 600, color: '#94a3b8' }}>
              {isDark ? 'Light' : 'Dark'}
            </span>
            <span style={{
              width: 34, height: 20, borderRadius: 999,
              background: isDark ? '#0ea5e9' : '#334155',
              position: 'relative', display: 'inline-flex', alignItems: 'center',
              transition: 'background 0.25s ease',
              border: `1px solid ${isDark ? 'rgba(14,165,233,0.5)' : 'rgba(255,255,255,0.1)'}`,
              flexShrink: 0,
            }}>
              <span style={{
                position: 'absolute', top: 2,
                left: isDark ? 16 : 2,
                width: 14, height: 14, borderRadius: '50%',
                background: 'white',
                transition: 'left 0.25s ease',
                boxShadow: '0 1px 4px rgba(0,0,0,0.3)',
              }} />
            </span>
          </button>

          <div style={{ width: 1, height: 20, background: 'rgba(255,255,255,0.08)', flexShrink: 0 }} />

          <button
            onClick={async () => { await logout(); navigate('/login') }}
            style={{
              background: 'none', border: 'none', cursor: 'pointer',
              fontSize: 12, fontWeight: 500, color: '#64748b',
              display: 'flex', alignItems: 'center', gap: 5,
              padding: '4px 8px', borderRadius: 7,
              transition: 'all 0.15s',
            }}
            onMouseEnter={e => { e.currentTarget.style.color = '#f87171'; e.currentTarget.style.background = 'rgba(248,113,113,0.08)' }}
            onMouseLeave={e => { e.currentTarget.style.color = '#64748b'; e.currentTarget.style.background = 'none' }}
          >
            Sign Out
          </button>
        </div>
      </div>

      {/* ── Row 2: Project context tabs — only shown when a project is active ── */}
      {hasProject && (
        <div style={{
          display: 'flex', alignItems: 'center', height: 36,
          padding: '0 20px',
          borderTop: '1px solid rgba(255,255,255,0.04)',
          background: 'rgba(0,0,0,0.15)',
          gap: 2,
        }}>
          {/* Small label */}
          <span style={{
            fontSize: 10, fontWeight: 600, color: '#334155',
            textTransform: 'uppercase', letterSpacing: '0.08em',
            marginRight: 8, flexShrink: 0,
          }}>
            Project:
          </span>
          {PROJECT_TABS.map(tab => (
            <TabLink key={tab.to} to={tab.to} label={tab.label} compact />
          ))}
        </div>
      )}
    </header>
  )
}
