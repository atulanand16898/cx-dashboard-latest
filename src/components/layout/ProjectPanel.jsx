import React, { useState, useRef, useEffect } from 'react'
import { useProject } from '../../context/ProjectContext'
import { Search, ChevronDown } from 'lucide-react'

function getWeekLabel() {
  const now = new Date()
  const jan1 = new Date(now.getFullYear(), 0, 1)
  const week = Math.ceil(((now - jan1) / 86400000 + jan1.getDay() + 1) / 7)
  return `${now.getFullYear()}-W${String(week).padStart(2, '0')}`
}

export default function ProjectPanel() {
  const { projects, selectedProjects, toggleProject, clearSelection, activeProject, period, setPeriod } = useProject()
  const [search, setSearch] = useState('')
  const [open, setOpen] = useState(false)
  const panelRef = useRef(null)
  const now = new Date()
  const dateStr = now.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })

  // Close on outside click
  useEffect(() => {
    function handler(e) {
      if (panelRef.current && !panelRef.current.contains(e.target)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [])

  const filtered = projects.filter(p =>
    !search ||
    (p.name || '').toLowerCase().includes(search.toLowerCase()) ||
    (p.externalId || '').toLowerCase().includes(search.toLowerCase()) ||
    (p.client || '').toLowerCase().includes(search.toLowerCase()) ||
    (p.location || '').toLowerCase().includes(search.toLowerCase())
  )

  // Build display strings for the Work Package area
  const count = selectedProjects.length
  const primaryProject = selectedProjects[0] || activeProject
  const secondaryLine = count > 1
    ? selectedProjects.slice(0, 2).map(p => p.name).join(', ') + (count > 2 ? ` +${count - 2} more` : '')
    : (primaryProject?.externalId ? `${primaryProject.client || ''} | ${primaryProject.location || ''}`.replace(/^\| /, '').replace(/ \|$/, '') : '')

  // Multi-client display for top-left
  const clientDisplay = count > 1 ? 'Multiple clients'
    : primaryProject?.client || primaryProject?.name || ''

  const liveProjectTitle = count > 1
    ? `${count} Projects Portfolio`
    : (primaryProject?.name || 'No project selected')

  return (
    <div style={{
      padding: '12px 24px 16px',
      borderBottom: '1px solid rgba(255,255,255,0.06)',
      background: 'var(--bg-base)',
    }}>
      {/* Top row: LIVE TRACKER PROJECT label + date/week/client */}
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', marginBottom: 16 }}>
        <div>
          <div style={{ fontSize: 10, fontWeight: 600, color: '#475569', textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 3 }}>
            Live Tracker Project
          </div>
          <div style={{ fontSize: 18, fontWeight: 700, color: 'var(--text-primary)' }}>
            {liveProjectTitle}
          </div>
        </div>
        <div style={{ fontSize: 12, color: '#64748b', textAlign: 'right', marginTop: 4 }}>
          {dateStr} &nbsp; {getWeekLabel()} &nbsp; {clientDisplay}
        </div>
      </div>

      {/* Bottom row: CONTROLSTRACKER FILTERS label + WORK PACKAGE selector */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ fontSize: 10, fontWeight: 600, color: '#475569', textTransform: 'uppercase', letterSpacing: '0.08em' }}>
          ControlsTracker Filters
        </div>

        {/* Work Package / Project selector */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }} ref={panelRef}>
          {/* Trigger */}
          <div style={{ position: 'relative' }}>
            <button
              onClick={() => setOpen(o => !o)}
              style={{
                display: 'flex', alignItems: 'center', gap: 12,
                background: 'none', border: 'none', cursor: 'pointer',
                padding: '4px 0', textAlign: 'left',
              }}
            >
              <div>
                <div style={{ fontSize: 10, fontWeight: 600, color: '#475569', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 2 }}>
                  Work Package
                </div>
                <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-primary)', lineHeight: 1.3 }}>
                  {count > 1 ? `${count} projects selected` : (primaryProject?.name || 'Select project')}
                </div>
                {secondaryLine && (
                  <div style={{ fontSize: 11, color: '#64748b', marginTop: 1 }}>
                    {secondaryLine}
                  </div>
                )}
              </div>
              <ChevronDown
                size={14}
                color="#475569"
                style={{ transform: open ? 'rotate(180deg)' : 'rotate(0deg)', transition: 'transform 0.2s', flexShrink: 0 }}
              />
            </button>

            {/* Dropdown panel */}
            {open && (
              <div style={{
                position: 'absolute', right: 0, top: '100%', marginTop: 8,
                width: 460,
                background: 'var(--bg-card)',
                border: '1px solid var(--border)',
                borderRadius: 12,
                boxShadow: '0 12px 48px rgba(0,0,0,0.5)',
                zIndex: 100,
                overflow: 'hidden',
                animation: 'fadeIn 0.15s ease',
              }}>
                {/* Search */}
                <div style={{ padding: '12px 16px 8px', borderBottom: '1px solid var(--border)' }}>
                  <div style={{ position: 'relative' }}>
                    <Search size={13} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: '#475569' }} />
                    <input
                      autoFocus
                      type="text"
                      placeholder="Search project, client, location"
                      value={search}
                      onChange={e => setSearch(e.target.value)}
                      style={{
                        width: '100%', paddingLeft: 30, paddingRight: 12,
                        paddingTop: 7, paddingBottom: 7,
                        background: 'var(--input-bg)',
                        border: '1px solid var(--border)',
                        borderRadius: 8, fontSize: 12,
                        color: 'var(--text-primary)', outline: 'none',
                        fontFamily: 'inherit',
                      }}
                    />
                  </div>
                </div>

                {/* Meta row */}
                <div style={{
                  display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                  padding: '7px 16px', borderBottom: '1px solid var(--border)',
                  fontSize: 11, color: '#64748b',
                }}>
                  <span>{projects.length} projects loaded</span>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                    <span>{count} selected</span>
                    {count > 0 && (
                      <button
                        onClick={clearSelection}
                        style={{
                          background: 'none', border: '1px solid var(--border)',
                          borderRadius: 5, padding: '2px 8px',
                          fontSize: 11, color: '#94a3b8', cursor: 'pointer',
                          fontFamily: 'inherit',
                        }}
                        onMouseEnter={e => e.currentTarget.style.color = 'var(--text-primary)'}
                        onMouseLeave={e => e.currentTarget.style.color = '#94a3b8'}
                      >
                        Clear
                      </button>
                    )}
                  </div>
                </div>

                {/* Project list */}
                <div style={{ maxHeight: 320, overflowY: 'auto' }}>
                  {filtered.length === 0 && (
                    <div style={{ padding: '24px 16px', textAlign: 'center', fontSize: 12, color: '#475569' }}>
                      No projects match "{search}"
                    </div>
                  )}
                  {filtered.map(p => {
                    const isSelected = selectedProjects.some(s => s.id === p.id)
                    const meta = [p.client, p.location, p.status].filter(Boolean).join(' | ')
                    return (
                      <div
                        key={p.id}
                        style={{
                          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                          padding: '11px 16px',
                          background: isSelected ? 'rgba(14,165,233,0.08)' : 'transparent',
                          borderLeft: isSelected ? '3px solid #0ea5e9' : '3px solid transparent',
                          borderBottom: '1px solid var(--border)',
                          cursor: 'pointer',
                          transition: 'background 0.12s ease',
                        }}
                        onClick={() => toggleProject(p)}
                        onMouseEnter={e => { if (!isSelected) e.currentTarget.style.background = 'var(--bg-card-light)' }}
                        onMouseLeave={e => { if (!isSelected) e.currentTarget.style.background = 'transparent' }}
                      >
                        <div style={{ minWidth: 0 }}>
                          <div style={{
                            fontSize: 13, fontWeight: 600,
                            color: isSelected ? 'var(--text-primary)' : 'var(--text-secondary)',
                          }}>
                            {p.name}
                          </div>
                          {meta && (
                            <div style={{ fontSize: 11, color: '#64748b', marginTop: 2 }}>
                              {meta}
                            </div>
                          )}
                        </div>
                        <button
                          onClick={e => { e.stopPropagation(); toggleProject(p) }}
                          style={{
                            fontSize: 11, fontWeight: 700, letterSpacing: '0.04em',
                            padding: '3px 10px', borderRadius: 5,
                            border: isSelected ? 'none' : '1px solid var(--border)',
                            background: isSelected ? 'rgba(14,165,233,0.2)' : 'none',
                            color: isSelected ? '#38bdf8' : '#64748b',
                            cursor: 'pointer', flexShrink: 0, marginLeft: 12,
                            fontFamily: 'inherit',
                            transition: 'all 0.15s',
                          }}
                        >
                          {isSelected ? 'SELECTED' : 'SELECT'}
                        </button>
                      </div>
                    )
                  })}
                </div>
              </div>
            )}
          </div>

          {/* Overall / D / W / M buttons — wired to shared period state */}
          <div style={{ display: 'flex', gap: 4 }}>
            {['Overall', 'D', 'W', 'M'].map((l) => {
              const isActive = period === l
              return (
                <button
                  key={l}
                  onClick={() => setPeriod(l)}
                  title={{ Overall: 'All time', D: 'Daily', W: 'Weekly', M: 'Monthly' }[l]}
                  style={{
                    width: l === 'Overall' ? 64 : 32, height: 32, borderRadius: 8,
                    background: isActive ? '#0ea5e9' : 'var(--bg-card-light)',
                    border: isActive ? 'none' : '1px solid var(--border)',
                    color: isActive ? 'white' : '#64748b',
                    fontSize: 12, fontWeight: 700,
                    cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontFamily: 'inherit',
                    transition: 'all 0.18s ease',
                    boxShadow: isActive ? '0 2px 10px rgba(14,165,233,0.35)' : 'none',
                  }}
                  onMouseEnter={e => { if (!isActive) { e.currentTarget.style.color = 'var(--text-primary)'; e.currentTarget.style.borderColor = 'var(--border-hover)' } }}
                  onMouseLeave={e => { if (!isActive) { e.currentTarget.style.color = '#64748b'; e.currentTarget.style.borderColor = 'var(--border)' } }}
                >
                  {l}
                </button>
              )
            })}
          </div>
        </div>
      </div>
    </div>
  )
}
