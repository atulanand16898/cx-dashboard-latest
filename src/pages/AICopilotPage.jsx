import React, { useEffect, useMemo, useRef, useState } from 'react'
import { useProject } from '../context/ProjectContext'
import { copilotApi } from '../services/api'

const DEFAULT_MODEL = 'gpt-4o-mini'
const API_KEY_STORAGE = 'modem_iq_openai_api_key'
const MODEL_STORAGE = 'modem_iq_openai_model'

const QUICK_PROMPTS = [
  'Summarize the biggest risks in my current data.',
  'What is blocked right now across issues, checklists, and equipment?',
  'Give me a short executive update for the selected projects.',
  'Find any mismatches between checklist progress and open issues.',
]

function MetricCard({ label, value, sub }) {
  return (
    <div style={{
      background: 'var(--bg-card)',
      border: '1px solid var(--border)',
      borderRadius: 14,
      padding: '16px 18px',
      minHeight: 96,
    }}>
      <div style={{ fontSize: 10, color: '#64748b', textTransform: 'uppercase', letterSpacing: '0.08em', fontWeight: 700 }}>{label}</div>
      <div style={{ fontSize: 28, fontWeight: 800, color: 'var(--text-primary)', lineHeight: 1.1, marginTop: 10 }}>{value}</div>
      <div style={{ fontSize: 12, color: '#64748b', marginTop: 6 }}>{sub}</div>
    </div>
  )
}

function SearchGroup({ label, rows, keyField, titleField }) {
  return (
    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, overflow: 'hidden' }}>
      <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--divider)', fontSize: 12, fontWeight: 700, color: 'var(--text-primary)' }}>
        {label}
      </div>
      {rows?.length ? rows.map((row, index) => (
        <div key={`${row[keyField] || label}-${index}`} style={{ padding: '10px 16px', borderBottom: index === rows.length - 1 ? 'none' : '1px solid var(--divider)' }}>
          <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-primary)' }}>{row[keyField] || row.name || 'Record'}</div>
          <div style={{ fontSize: 11, color: '#64748b', marginTop: 3 }}>{row[titleField] || row.status || row.location || row.projectId || 'Matched in workspace data'}</div>
        </div>
      )) : (
        <div style={{ padding: '20px 16px', fontSize: 12, color: '#64748b' }}>No direct matches yet for the last question.</div>
      )}
    </div>
  )
}

export default function AICopilotPage() {
  const { selectedProjects, activeProject } = useProject()
  const targets = selectedProjects.length > 0 ? selectedProjects : (activeProject ? [activeProject] : [])
  const [scopeMode, setScopeMode] = useState('selection')
  const [context, setContext] = useState(null)
  const [contextLoading, setContextLoading] = useState(false)
  const [messages, setMessages] = useState([])
  const [prompt, setPrompt] = useState('')
  const [thinking, setThinking] = useState(false)
  const [apiKey, setApiKey] = useState(() => localStorage.getItem(API_KEY_STORAGE) || '')
  const [model, setModel] = useState(() => localStorage.getItem(MODEL_STORAGE) || DEFAULT_MODEL)
  const [files, setFiles] = useState([])
  const [error, setError] = useState('')
  const endRef = useRef(null)

  const projectIds = useMemo(
    () => (scopeMode === 'workspace' ? [] : targets.map(project => project.externalId)),
    [scopeMode, targets]
  )

  const scopeLabel = scopeMode === 'workspace'
    ? 'All synced workspace data'
    : targets.length
      ? `${targets.length} selected project${targets.length > 1 ? 's' : ''}`
      : 'No project selected'

  useEffect(() => {
    localStorage.setItem(API_KEY_STORAGE, apiKey)
  }, [apiKey])

  useEffect(() => {
    localStorage.setItem(MODEL_STORAGE, model)
  }, [model])

  useEffect(() => {
    let cancelled = false
    setContextLoading(true)
    copilotApi.getContext(projectIds)
      .then(({ data }) => {
        if (!cancelled) {
          setContext(data.data)
          setError('')
        }
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err.response?.data?.message || err.message || 'Failed to load copilot context.')
        }
      })
      .finally(() => {
        if (!cancelled) setContextLoading(false)
      })
    return () => { cancelled = true }
  }, [projectIds.join('|')])

  useEffect(() => {
    if (!context) return
    const totals = context.totals || {}
    const names = context.scope?.projectNames || []
    setMessages([{
      role: 'assistant',
      text: `AI Copilot is online for ${scopeLabel}. I can see ${totals.projects || 0} projects, ${totals.issues || 0} issues, ${totals.checklists || 0} checklists, ${totals.equipment || 0} equipment rows, and ${totals.tasks || 0} tasks.${names.length ? ` Active projects: ${names.slice(0, 3).join(', ')}${names.length > 3 ? '...' : ''}.` : ''}`,
    }])
  }, [
    scopeMode,
    projectIds.join('|'),
    context?.totals?.projects,
    context?.totals?.issues,
    context?.totals?.checklists,
    context?.totals?.equipment,
    context?.totals?.tasks,
  ])

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, thinking])

  function handleFilesChange(event) {
    setFiles(Array.from(event.target.files || []))
  }

  async function ask(question) {
    const text = (question || prompt).trim()
    if (!text || thinking) return
    if (!apiKey.trim()) {
      setError('Add your OpenAI API key to use the copilot.')
      return
    }

    setPrompt('')
    setThinking(true)
    setError('')

    const userMessage = { role: 'user', text }
    const history = [...messages, userMessage]
    setMessages(history)

    try {
      const response = await copilotApi.chat({
        payload: {
          apiKey: apiKey.trim(),
          model: model.trim() || DEFAULT_MODEL,
          prompt: text,
          projectIds,
          conversation: messages,
        },
        files,
      })

      const data = response.data.data
      setMessages(prev => [...prev, { role: 'assistant', text: data.answer }])
      setContext(prev => prev ? {
        ...prev,
        totals: data.totals || prev.totals,
        scope: data.scope || prev.scope,
        searchMatches: data.searchMatches || prev.searchMatches,
        uploads: data.uploads || prev.uploads,
      } : prev)
    } catch (err) {
      const message = err.response?.data?.message || err.message || 'Copilot request failed.'
      setMessages(prev => [...prev, { role: 'assistant', text: `Error: ${message}` }])
      setError(message)
    } finally {
      setThinking(false)
    }
  }

  const totals = context?.totals || {}
  const searchMatches = context?.searchMatches || {}
  const uploadPreviews = context?.uploads || []

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
      <div>
        <h2 style={{ fontSize: 24, fontWeight: 800, color: 'var(--text-primary)', margin: 0 }}>AI Copilot</h2>
        <p style={{ fontSize: 13, color: '#64748b', marginTop: 6 }}>
          Ask about everything already synced into the workspace database, then enrich the conversation with your own uploaded files.
        </p>
      </div>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10 }}>
        <button
          onClick={() => setScopeMode('selection')}
          style={{
            padding: '8px 14px',
            borderRadius: 999,
            border: scopeMode === 'selection' ? '1px solid rgba(56,189,248,0.4)' : '1px solid var(--border)',
            background: scopeMode === 'selection' ? 'rgba(56,189,248,0.12)' : 'var(--bg-card)',
            color: scopeMode === 'selection' ? '#7dd3fc' : '#94a3b8',
            fontSize: 12,
            fontWeight: 700,
            cursor: 'pointer',
          }}
        >
          Selected Projects
        </button>
        <button
          onClick={() => setScopeMode('workspace')}
          style={{
            padding: '8px 14px',
            borderRadius: 999,
            border: scopeMode === 'workspace' ? '1px solid rgba(56,189,248,0.4)' : '1px solid var(--border)',
            background: scopeMode === 'workspace' ? 'rgba(56,189,248,0.12)' : 'var(--bg-card)',
            color: scopeMode === 'workspace' ? '#7dd3fc' : '#94a3b8',
            fontSize: 12,
            fontWeight: 700,
            cursor: 'pointer',
          }}
        >
          Whole Workspace
        </button>
        <div style={{ padding: '8px 14px', borderRadius: 999, border: '1px solid var(--border)', background: 'var(--bg-card)', fontSize: 12, color: '#94a3b8' }}>
          Scope: {scopeLabel}
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', gap: 14 }}>
        <MetricCard label="Projects" value={contextLoading ? '...' : totals.projects || 0} sub="In current AI scope" />
        <MetricCard label="Issues" value={contextLoading ? '...' : totals.issues || 0} sub={`${totals.openIssues || 0} open / ${totals.closedIssues || 0} closed`} />
        <MetricCard label="Checklists" value={contextLoading ? '...' : totals.checklists || 0} sub={`${totals.equipment || 0} equipment rows linked`} />
        <MetricCard label="Files" value={contextLoading ? '...' : totals.files || 0} sub={`${Math.round((totals.fileBytes || 0) / 1024 / 1024)} MB indexed`} />
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1.7fr 1fr', gap: 16, alignItems: 'start' }}>
        <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 14, overflow: 'hidden' }}>
          <div style={{ padding: '14px 18px', borderBottom: '1px solid var(--divider)', display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
            <div>
              <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' }}>Workspace Chat</div>
              <div style={{ fontSize: 11, color: '#64748b', marginTop: 3 }}>Server-side OpenAI calls with database context and optional file uploads.</div>
            </div>
            <button
              onClick={() => setMessages(messages.slice(0, 1))}
              style={{ background: 'none', border: '1px solid var(--border)', borderRadius: 8, color: '#94a3b8', fontSize: 11, padding: '6px 10px', cursor: 'pointer' }}
            >
              Clear chat
            </button>
          </div>

          <div style={{ padding: '14px 18px', borderBottom: '1px solid var(--divider)', display: 'grid', gridTemplateColumns: '1.4fr 1fr', gap: 10 }}>
            <input
              type="password"
              value={apiKey}
              onChange={e => setApiKey(e.target.value)}
              placeholder="OpenAI API key (stored in this browser only)"
              style={{ padding: '10px 12px', borderRadius: 9, border: '1px solid var(--border)', background: 'var(--bg-base)', color: 'var(--text-primary)', fontSize: 12, outline: 'none' }}
            />
            <input
              value={model}
              onChange={e => setModel(e.target.value)}
              placeholder="Model"
              style={{ padding: '10px 12px', borderRadius: 9, border: '1px solid var(--border)', background: 'var(--bg-base)', color: 'var(--text-primary)', fontSize: 12, outline: 'none' }}
            />
          </div>

          <div style={{ height: 360, overflowY: 'auto', padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 12 }}>
            {messages.map((message, index) => (
              <div
                key={`${message.role}-${index}`}
                style={{
                  alignSelf: message.role === 'user' ? 'flex-end' : 'flex-start',
                  maxWidth: '88%',
                  padding: '11px 14px',
                  borderRadius: message.role === 'user' ? '16px 16px 4px 16px' : '16px 16px 16px 4px',
                  background: message.role === 'user' ? '#0ea5e9' : 'rgba(51,65,85,0.55)',
                  color: message.role === 'user' ? '#fff' : 'var(--text-primary)',
                  fontSize: 12.5,
                  lineHeight: 1.6,
                  whiteSpace: 'pre-wrap',
                }}
              >
                {message.text}
              </div>
            ))}
            {thinking && (
              <div style={{ alignSelf: 'flex-start', padding: '10px 14px', borderRadius: '16px 16px 16px 4px', background: 'rgba(51,65,85,0.55)', fontSize: 12, color: '#94a3b8' }}>
                Thinking...
              </div>
            )}
            <div ref={endRef} />
          </div>

          <div style={{ padding: '12px 18px', borderTop: '1px solid var(--divider)', display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {QUICK_PROMPTS.map(item => (
              <button
                key={item}
                onClick={() => ask(item)}
                disabled={thinking}
                style={{ padding: '6px 11px', borderRadius: 999, border: '1px solid var(--border)', background: 'var(--bg-base)', color: '#94a3b8', fontSize: 11, cursor: 'pointer' }}
              >
                {item}
              </button>
            ))}
          </div>

          <div style={{ padding: '14px 18px', borderTop: '1px solid var(--divider)', display: 'flex', flexDirection: 'column', gap: 10 }}>
            <textarea
              value={prompt}
              onChange={e => setPrompt(e.target.value)}
              onKeyDown={e => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault()
                  ask()
                }
              }}
              placeholder="Ask about the synced DB, compare issue/checklist trends, generate executive summaries, or reason over uploaded files..."
              style={{ minHeight: 90, resize: 'vertical', padding: '12px 14px', borderRadius: 10, border: '1px solid var(--border)', background: 'var(--bg-base)', color: 'var(--text-primary)', fontSize: 12.5, outline: 'none' }}
            />
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', alignItems: 'center' }}>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <input type="file" multiple onChange={handleFilesChange} style={{ fontSize: 12, color: '#94a3b8' }} />
                {!!files.length && (
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                    {files.map(file => (
                      <span key={`${file.name}-${file.size}`} style={{ padding: '4px 8px', borderRadius: 999, background: 'rgba(59,130,246,0.08)', border: '1px solid rgba(59,130,246,0.18)', fontSize: 11, color: '#93c5fd' }}>
                        {file.name}
                      </span>
                    ))}
                  </div>
                )}
              </div>
              <button
                onClick={() => ask()}
                disabled={thinking || !prompt.trim() || !apiKey.trim()}
                style={{
                  padding: '10px 18px',
                  borderRadius: 10,
                  border: 'none',
                  background: '#0ea5e9',
                  color: '#fff',
                  fontSize: 12,
                  fontWeight: 700,
                  cursor: 'pointer',
                  opacity: thinking || !prompt.trim() || !apiKey.trim() ? 0.5 : 1,
                }}
              >
                Ask Copilot
              </button>
            </div>
            {error && <div style={{ fontSize: 12, color: '#f87171' }}>{error}</div>}
          </div>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <SearchGroup label="Issue Matches" rows={searchMatches.issues} keyField="issueId" titleField="title" />
          <SearchGroup label="Checklist Matches" rows={searchMatches.checklists} keyField="checklistId" titleField="name" />
          <SearchGroup label="Equipment Matches" rows={searchMatches.equipment} keyField="equipmentId" titleField="name" />
          <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 12, padding: '14px 16px' }}>
            <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-primary)' }}>Uploaded File Context</div>
            <div style={{ fontSize: 11, color: '#64748b', marginTop: 4 }}>Files are attached to the next copilot answer. Text-like files are extracted; binary files are sent as metadata only in this version.</div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginTop: 12 }}>
              {(uploadPreviews.length ? uploadPreviews : files).length ? (uploadPreviews.length ? uploadPreviews : files).map((file, index) => (
                <div key={`${file.name || file.originalFilename || 'file'}-${index}`} style={{ padding: '8px 10px', borderRadius: 10, background: 'var(--bg-base)', border: '1px solid var(--border)' }}>
                  <div style={{ fontSize: 11.5, fontWeight: 700, color: 'var(--text-primary)' }}>{file.name}</div>
                  <div style={{ fontSize: 10.5, color: '#64748b', marginTop: 3 }}>
                    {file.contentType || 'unknown type'} • {Math.round((file.sizeBytes || file.size || 0) / 1024)} KB
                  </div>
                </div>
              )) : (
                <div style={{ fontSize: 12, color: '#64748b', marginTop: 8 }}>No files attached.</div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}
