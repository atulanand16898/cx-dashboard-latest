import React, { useState, useEffect, useCallback, useMemo } from 'react'
import ReactDOM from 'react-dom'
import {
  ComposedChart, Area, Line, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer,
} from 'recharts'
import {
  TrendingUp, Calendar, AlertTriangle, CheckSquare,
  RefreshCw, ChevronRight, Activity, X, ExternalLink,
  Upload, FileSpreadsheet, Trash2,
} from 'lucide-react'
import { useProject } from '../context/ProjectContext'
import { checklistsApi } from '../services/api'
import toast from 'react-hot-toast'

// ─── CxAlloy deep-link ────────────────────────────────────────────────────────
function cxUrl(projectId, externalId) {
  if (!projectId || !externalId) return null
  return `https://tq.cxalloy.com/project/${projectId}/checklists/${externalId}`
}

// ─── ISO week helpers ─────────────────────────────────────────────────────────
function isoWeekLabel(d) {
  const tmp = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()))
  const day = tmp.getUTCDay() || 7
  tmp.setUTCDate(tmp.getUTCDate() + 4 - day)
  const yr = new Date(Date.UTC(tmp.getUTCFullYear(), 0, 1))
  const wk = Math.ceil((((tmp - yr) / 86400000) + 1) / 7)
  return `${tmp.getUTCFullYear()}-W${String(wk).padStart(2, '0')}`
}

function addDays(date, n) {
  const d = new Date(date); d.setDate(d.getDate() + n); return d
}

// ─── Best date from checklist (camelCase from Spring Boot) ────────────────────
function pickDate(c) {
  return c.completedDate || c.updatedAt || c.createdAt
      || c.completed_date || c.updated_at || c.created_at || null
}

function pickDueDate(c) {
  return c.dueDate || c.due_date || c.plannedDate || c.planned_date || null
}

// ─── "done" predicate ─────────────────────────────────────────────────────────
const DONE = new Set(['finished','complete','completed','done','closed',
  'signed_off','approved','passed','issue_closed','accepted_by_owner'])
const isDone = s => DONE.has((s || '').toLowerCase().replace(/[ \-]/g,'_'))

// ─── Tag normaliser ───────────────────────────────────────────────────────────
// Handles: explicit color words, ITR-A/B/C/D (CxAlloy standard),
// bare L1/L2/L3/L4 prefixes, and level.?N patterns.
function normaliseTag(c) {
  // Trust tagLevel from backend ONLY if it is a real color (not white/blank).
  // "white" from the backend means "could not classify" — we must still try
  // checklistType / name before giving up and returning white.
  const tl = (c.tagLevel || c.tag_level || '').toLowerCase().trim()
  if (['red', 'yellow', 'green', 'blue'].includes(tl)) return tl

  // Fall through to checklistType, then name
  const src = (c.checklistType || c.checklist_type || c.name || '').toLowerCase()

  // ITR patterns (CxAlloy standard: ITR-A=red, ITR-B=yellow, ITR-C=green, ITR-D=blue)
  if (/\bitr[-_\s]?a\b/.test(src)) return 'red'
  if (/\bitr[-_\s]?b\b/.test(src)) return 'yellow'
  if (/\bitr[-_\s]?c\b/.test(src)) return 'green'
  if (/\bitr[-_\s]?d\b/.test(src)) return 'blue'

  // Explicit color words — use word boundaries to avoid false positives
  // e.g. "predefined" must NOT match "red", "blueprint" must NOT match "blue"
  if (/\bred\b/.test(src))    return 'red'
  if (/\byellow\b/.test(src)) return 'yellow'
  if (/\bgreen\b/.test(src))  return 'green'
  if (/\bblue\b/.test(src))   return 'blue'

  // Level number labels (e.g. "Level-1 RED Tag FAT", "Level 2 YELLOW")
  if (/level[-\s]?1/.test(src)) return 'red'
  if (/level[-\s]?2/.test(src)) return 'yellow'
  if (/level[-\s]?3/.test(src)) return 'green'
  if (/level[-\s]?4/.test(src)) return 'blue'

  // Bare L1/L2/L3/L4 at word boundary (e.g. "L1 - FWT/FAT", "L2 - Conditional")
  if (/\bl1\b/.test(src)) return 'red'
  if (/\bl2\b/.test(src)) return 'yellow'
  if (/\bl3\b/.test(src)) return 'green'
  if (/\bl4\b/.test(src)) return 'blue'

  // Genuinely unclassifiable
  return tl === 'white' ? 'white' : 'other'
}

// ─── Tag colours ──────────────────────────────────────────────────────────────
const TAG_COLORS = {
  red:    { dot: '#ef4444', bg: 'rgba(239,68,68,0.12)',    border: 'rgba(239,68,68,0.25)',    text: '#f87171' },
  yellow: { dot: '#eab308', bg: 'rgba(234,179,8,0.12)',    border: 'rgba(234,179,8,0.25)',    text: '#fbbf24' },
  green:  { dot: '#22c55e', bg: 'rgba(34,197,94,0.12)',    border: 'rgba(34,197,94,0.25)',    text: '#4ade80' },
  blue:   { dot: '#3b82f6', bg: 'rgba(59,130,246,0.12)',   border: 'rgba(59,130,246,0.25)',   text: '#60a5fa' },
  white:  { dot: '#94a3b8', bg: 'rgba(148,163,184,0.08)',  border: 'rgba(148,163,184,0.15)',  text: '#94a3b8' },
  other:  { dot: '#64748b', bg: 'rgba(100,116,139,0.08)',  border: 'rgba(100,116,139,0.15)',  text: '#64748b' },
}

// ─── Plan baseline parser (Excel/CSV → weekly cumulative target) ──────────────
// Excel format: Name | Start | End   (one row per checklist, dates as Excel serials or ISO strings)
// CSV  format:  Name,Start,End       (same columns, comma-separated)
//
// Strategy: each checklist contributes 1 unit on the week its "End" date falls in.
// We accumulate these per-week counts into a cumulative target series that is then
// merged with the actual S-curve data so both lines share the same X axis.

function excelSerialToDate(serial) {
  // Excel date serial: days since 1900-01-00 (with the 1900 leap-year bug)
  if (typeof serial !== 'number') return null
  const msPerDay = 86400000
  // Excel serial 1 = Jan 1 1900; JS Date epoch = Jan 1 1970
  // Offset: 25569 days from Excel epoch to Unix epoch (accounting for Excel leap bug)
  const d = new Date((serial - 25569) * msPerDay)
  return isNaN(d) ? null : d
}

function parseDateValue(val) {
  if (!val) return null
  if (val instanceof Date) return isNaN(val) ? null : val
  if (typeof val === 'number') return excelSerialToDate(val)
  // ISO string or "MM/DD/YYYY" etc.
  const d = new Date(val)
  return isNaN(d) ? null : d
}

// Parse uploaded file → array of { name, start, end }
export async function parsePlanBaseline(file) {
  const name = file.name.toLowerCase()

  if (name.endsWith('.csv')) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = e => {
        try {
          const lines = e.target.result.split('\n').map(l => l.trim()).filter(Boolean)
          const rows = []
          for (let i = 1; i < lines.length; i++) { // skip header
            const cols = lines[i].split(',')
            const n   = (cols[0] || '').trim()
            const s   = parseDateValue((cols[1] || '').trim())
            const end = parseDateValue((cols[2] || '').trim())
            if (n && end) rows.push({ name: n, start: s, end })
          }
          resolve(rows)
        } catch (err) { reject(err) }
      }
      reader.onerror = reject
      reader.readAsText(file)
    })
  }

  if (name.endsWith('.xlsx') || name.endsWith('.xls')) {
    // Use SheetJS (xlsx) loaded from CDN — already available in Vite builds via npm
    // We import it dynamically so the bundle stays lean when not needed.
    const XLSX = await import('https://cdn.sheetjs.com/xlsx-0.20.3/package/xlsx.mjs')
    return new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = e => {
        try {
          const wb = XLSX.read(new Uint8Array(e.target.result), { type: 'array', cellDates: true })
          const ws = wb.Sheets[wb.SheetNames[0]]
          const raw = XLSX.utils.sheet_to_json(ws, { header: 1, raw: false, dateNF: 'YYYY-MM-DD' })
          const rows = []
          for (let i = 1; i < raw.length; i++) {
            const [n, sStr, eStr] = raw[i]
            const s   = parseDateValue(sStr)
            const end = parseDateValue(eStr)
            if (n && end) rows.push({ name: String(n).trim(), start: s, end })
          }
          resolve(rows)
        } catch (err) { reject(err) }
      }
      reader.onerror = reject
      reader.readAsArrayBuffer(file)
    })
  }

  throw new Error('Unsupported file type. Please upload .xlsx or .csv')
}

// ─── Infer tag from a plan-row name ──────────────────────────────────────────
function tagFromName(name) {
  if (!name) return 'other'
  const s = name.toLowerCase()
  if (/\bitr[-_\s]?a\b/.test(s)) return 'red'
  if (/\bitr[-_\s]?b\b/.test(s)) return 'yellow'
  if (/\bitr[-_\s]?c\b/.test(s)) return 'green'
  if (/\bitr[-_\s]?d\b/.test(s)) return 'blue'
  if (/\bred\b/.test(s))    return 'red'
  if (/\byellow\b/.test(s)) return 'yellow'
  if (/\bgreen\b/.test(s))  return 'green'
  if (/\bblue\b/.test(s))   return 'blue'
  if (/level[-\s]?1/.test(s) || /\bl1\b/.test(s)) return 'red'
  if (/level[-\s]?2/.test(s) || /\bl2\b/.test(s)) return 'yellow'
  if (/level[-\s]?3/.test(s) || /\bl3\b/.test(s)) return 'green'
  if (/level[-\s]?4/.test(s) || /\bl4\b/.test(s)) return 'blue'
  return 'other'
}

// ─── Date bucket helpers for D / W / M / Overall ─────────────────────────────
function dateBucket(d, view) {
  if (view === 'D') {
    return d.getFullYear() + '-' + String(d.getMonth()+1).padStart(2,'0') + '-' + String(d.getDate()).padStart(2,'0')
  }
  if (view === 'M') {
    return d.getFullYear() + '-' + String(d.getMonth()+1).padStart(2,'0')
  }
  // 'W' and 'Overall' both use ISO week
  return isoWeekLabel(d)
}

// ─── Build S-curve data ───────────────────────────────────────────────────────
function buildSCurveData(checklists, planRows, view = 'Overall') {
  // ── PLAN BASELINE MODE ────────────────────────────────────────────────────
  if (planRows && planRows.length > 0) {
    // 1. Build stacked bars from plan rows bucketed by view granularity
    const planWeekMap = new Map()
    planRows.forEach(r => {
      const d = r.end || r.start
      if (!d) return
      const bucket = dateBucket(d, view)
      const tag = tagFromName(r.name)
      const row = planWeekMap.get(bucket) || { week: bucket, red: 0, yellow: 0, green: 0, blue: 0, white: 0, other: 0 }
      row[tag] = (row[tag] || 0) + 1
      planWeekMap.set(bucket, row)
    })

    const planWeeks = Array.from(planWeekMap.values()).sort((a, b) => a.week.localeCompare(b.week))
    if (!planWeeks.length) return []

    // 2. Accumulate Target CUM from plan
    let cumTarget = 0
    planWeeks.forEach(w => {
      const total = w.red + w.yellow + w.green + w.blue + w.white + w.other
      cumTarget += total
      w.cumTarget = cumTarget
    })

    // 3. Build actual CUM from checklists per bucket
    const actualCumByBucket = new Map()
    const actualBucketCounts = new Map()
    checklists.forEach(c => {
      const raw = pickDate(c)
      if (!raw) return
      const d = new Date(raw)
      if (isNaN(d)) return
      const bucket = dateBucket(d, view)
      actualBucketCounts.set(bucket, (actualBucketCounts.get(bucket) || 0) + 1)
    })
    let cumActual = 0
    Array.from(actualBucketCounts.entries())
      .sort((a, b) => a[0].localeCompare(b[0]))
      .forEach(([bucket, cnt]) => {
        cumActual += cnt
        actualCumByBucket.set(bucket, cumActual)
      })

    // 4. Merge actual CUM onto plan axis (carry-forward)
    let lastActual = 0
    planWeeks.forEach(w => {
      if (actualCumByBucket.has(w.week)) lastActual = actualCumByBucket.get(w.week)
      w.cumActual = lastActual
    })

    return planWeeks
  }

  // ── ACTUAL-ONLY MODE (no baseline) ────────────────────────────────────────
  if (!checklists.length) return []

  const weekMap = new Map()
  checklists.forEach(c => {
    const raw = pickDate(c)
    if (!raw) return
    const d = new Date(raw)
    if (isNaN(d)) return
    const bucket = dateBucket(d, view)
    const tag = normaliseTag(c)
    const row = weekMap.get(bucket) || { week: bucket, red: 0, yellow: 0, green: 0, blue: 0, white: 0, other: 0 }
    row[tag] = (row[tag] || 0) + 1
    weekMap.set(bucket, row)
  })

  const weeks = Array.from(weekMap.values()).sort((a, b) => a.week.localeCompare(b.week))
  if (!weeks.length) return []

  let cumActual = 0
  const data = weeks.map(w => {
    const total = w.red + w.yellow + w.green + w.blue + w.white + w.other
    cumActual += total
    return { ...w, total, cumActual }
  })

  const totalChecklists = checklists.length
  data.forEach((d, i) => {
    d.cumTarget = Math.round((i + 1) / data.length * totalChecklists)
  })
  return data
}

// ─── Pace metrics ─────────────────────────────────────────────────────────────
function buildPaceMetrics(checklists) {
  if (!checklists.length) return { d: 0, w: 0, m: 0, cumulative: checklists.length }

  const weekMap = new Map()
  checklists.forEach(c => {
    const raw = pickDate(c)
    if (!raw) return
    const d = new Date(raw)
    if (isNaN(d)) return
    const wk = isoWeekLabel(d)
    weekMap.set(wk, (weekMap.get(wk) || 0) + 1)
  })

  const entries = Array.from(weekMap.values())
  const total = entries.reduce((s, v) => s + v, 0)
  const activeWeeks = entries.length || 1
  const w = +(total / activeWeeks).toFixed(1)
  return {
    d: +(w / 7).toFixed(1),
    w,
    m: +(w * 4.33).toFixed(1),
    cumulative: checklists.length,
  }
}

// ─── Overdue items ────────────────────────────────────────────────────────────
// A checklist is overdue if:
//  1. It has an explicit dueDate in the past AND is not done, OR
//  2. It is not done AND was created/updated more than 30 days ago (same logic modum.me uses)
function computeOverdue(checklists) {
  const now = new Date()
  const results = []
  checklists.forEach(c => {
    if (isDone(c.status)) return

    let delay = 0
    const due = pickDueDate(c)
    if (due) {
      const dueD = new Date(due)
      if (!isNaN(dueD) && dueD < now) {
        delay = Math.round((now - dueD) / 86400000)
      } else {
        return // has future due date — not overdue
      }
    } else {
      // No due date — treat as overdue if last activity > 30 days ago
      const activityDate = new Date(c.updatedAt || c.created_at || c.createdAt || 0)
      delay = Math.round((now - activityDate) / 86400000)
      if (delay < 30) return
    }

    const tag = normaliseTag(c)
    results.push({
      id:          c.id,
      name:        c.name || c.title || c.code || 'Unnamed Checklist',
      description: c.description || c.checklistType || c.checklist_type || '',
      tag,
      delay,
      externalId:  c.externalId || c.external_id,
      projectId:   c.projectId  || c.project_id,
    })
  })

  return results.sort((a, b) => b.delay - a.delay)
}

// ─── Next 14 days ─────────────────────────────────────────────────────────────
// When a plan baseline is loaded: show items from the plan whose End date falls
// within the next 14 days — this matches modum.me behaviour (it reads from the
// plan schedule, not just currently-open checklists).
// Without baseline: fall back to recently-active open checklists.
function computeNext14Days(checklists, planRows) {
  const now    = new Date()
  const future = addDays(now, 14)

  // ── WITH plan baseline: use plan rows with End date in next 14 days ────────
  if (planRows && planRows.length > 0) {
    const items = []
    planRows.forEach(r => {
      const end = r.end
      if (!end) return
      const d = new Date(end)
      if (isNaN(d)) return
      // Show items whose end date is coming up (within 14 days from now, or slightly past)
      // modum.me shows recent past too — use a 30 day look-back + 14 day look-ahead
      const lookBack = addDays(now, -30)
      if (d < lookBack || d > future) return

      // Try to enrich with checklist metadata (match by name)
      const matched = checklists.find(c =>
        (c.name || '').toLowerCase().trim() === (r.name || '').toLowerCase().trim()
      )
      const tag = tagFromName(r.name)
      const tc = TAG_COLORS[tag] || TAG_COLORS.other

      // Build tagLabel from the checklist's checklistType if matched, else derive from tag
      let tagLabel = matched?.checklistType || matched?.checklist_type || ''
      if (!tagLabel) {
        if (tag === 'red')    tagLabel = 'Level-1 RED Tag FAT'
        else if (tag === 'yellow') tagLabel = 'Level-2 YELLOW Tag QA/QC/IVC'
        else if (tag === 'green')  tagLabel = 'Level-3 GREEN Tag Start-Up/PFC'
        else if (tag === 'blue')   tagLabel = 'Level-4 BLUE Tag Sign-Off'
        else tagLabel = 'Checklist'
      }

      items.push({
        id:         matched?.id || r.name,
        name:       r.name,
        tagLabel,
        tag,
        tagColor:   tc,
        dueDate:    d,
        externalId: matched?.externalId || matched?.external_id,
        projectId:  matched?.projectId  || matched?.project_id,
      })
    })
    return items.sort((a, b) => a.dueDate - b.dueDate)
  }

  // ── WITHOUT plan baseline: recently-active open checklists ────────────────
  const toItem = (c, dateOverride) => {
    const due = pickDueDate(c)
    const rawType = (c.checklistType || c.checklist_type || '').trim()
    const tag = normaliseTag(c)
    const tc = TAG_COLORS[tag] || TAG_COLORS.other
    let tagLabel = rawType
    if (!tagLabel) {
      if      (tag === 'red')    tagLabel = 'Level-1 RED Tag FAT'
      else if (tag === 'yellow') tagLabel = 'Level-2 YELLOW Tag QA/QC/IVC'
      else if (tag === 'green')  tagLabel = 'Level-3 GREEN Tag Start-Up/PFC'
      else if (tag === 'blue')   tagLabel = 'Level-4 BLUE Tag Sign-Off'
      else                       tagLabel = 'Checklist'
    }
    const actDate = dateOverride
      || (due ? new Date(due) : null)
      || new Date(c.updatedAt || c.updated_at || c.createdAt || c.created_at || now)
    return {
      id:         c.id,
      name:       c.name || c.title || c.code || 'Unnamed',
      tagLabel,
      tag,
      tagColor:   tc,
      dueDate:    actDate,
      externalId: c.externalId || c.external_id,
      projectId:  c.projectId  || c.project_id,
    }
  }

  const withFutureDue = checklists.filter(c => {
    if (isDone(c.status)) return false
    const due = pickDueDate(c)
    if (!due) return false
    const d = new Date(due)
    return !isNaN(d) && d >= now && d <= future
  }).map(c => toItem(c, null)).sort((a, b) => a.dueDate - b.dueDate)

  const futureDueIds = new Set(withFutureDue.map(i => i.id))
  const recentOpen = checklists
    .filter(c => {
      if (isDone(c.status)) return false
      if (futureDueIds.has(c.id)) return false
      const d = new Date(c.updatedAt || c.updated_at || c.createdAt || c.created_at || 0)
      return !isNaN(d)
    })
    .sort((a, b) => {
      const da = new Date(a.updatedAt || a.updated_at || a.createdAt || a.created_at || 0)
      const db = new Date(b.updatedAt || b.updated_at || b.createdAt || b.created_at || 0)
      return db - da
    })
    .slice(0, Math.max(0, 50 - withFutureDue.length))
    .map(c => {
      const actDate = new Date(c.updatedAt || c.updated_at || c.createdAt || c.created_at || now)
      return toItem(c, actDate)
    })

  return [...withFutureDue, ...recentOpen]
}

// ─── Checklist Detail Modal ───────────────────────────────────────────────────
function ChecklistModal({ item, onClose }) {
  useEffect(() => {
    const onKey = e => { if (e.key === 'Escape') onClose() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  const url = cxUrl(item.projectId, item.externalId)
  const c   = TAG_COLORS[item.tag] || TAG_COLORS.other

  return ReactDOM.createPortal(
    <div
      style={{ position: 'fixed', inset: 0, zIndex: 9999, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24 }}
      onClick={e => { if (e.target === e.currentTarget) onClose() }}
    >
      {/* Backdrop */}
      <div style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.7)', backdropFilter: 'blur(4px)' }} />

      {/* Card */}
      <div style={{
        position: 'relative', zIndex: 1, background: 'var(--bg-card)',
        border: '1px solid var(--border)', borderRadius: 16,
        padding: 28, width: '100%', maxWidth: 520,
        boxShadow: '0 25px 60px rgba(0,0,0,0.5)',
      }}>
        {/* Header */}
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12, marginBottom: 20 }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 16, fontWeight: 800, color: 'var(--text-primary)', lineHeight: 1.3, marginBottom: 6 }}>
              {item.name}
            </div>
            {item.description && (
              <div style={{ fontSize: 12, color: '#64748b' }}>{item.description}</div>
            )}
          </div>
          <button
            onClick={onClose}
            style={{ background: 'var(--border)', border: '1px solid var(--border)', borderRadius: 8, padding: '6px 8px', cursor: 'pointer', color: '#94a3b8', flexShrink: 0, lineHeight: 0 }}
          >
            <X size={16} />
          </button>
        </div>

        {/* Details grid */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 20 }}>
          {[
            { label: 'TAG LEVEL', value: (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '3px 10px', borderRadius: 20, background: c.bg, border: `1px solid ${c.border}`, color: c.text, fontSize: 12, fontWeight: 600, textTransform: 'capitalize' }}>
                <span style={{ width: 7, height: 7, borderRadius: '50%', background: c.dot }} />
                {item.tag}
              </span>
            )},
            { label: 'DELAY', value: <span style={{ color: '#f87171', fontWeight: 700 }}>{item.delay}d overdue</span> },
          ].map(row => (
            <div key={row.label} style={{ background: 'var(--border-subtle)', borderRadius: 10, padding: '12px 14px' }}>
              <div style={{ fontSize: 9, fontWeight: 600, color: '#475569', textTransform: 'uppercase', letterSpacing: '0.08em', marginBottom: 6 }}>{row.label}</div>
              <div style={{ fontSize: 14, color: 'var(--text-primary)' }}>{row.value}</div>
            </div>
          ))}
        </div>

        {/* CxAlloy link */}
        {url ? (
          <a
            href={url}
            target="_blank"
            rel="noopener noreferrer"
            style={{
              display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
              padding: '11px 0', background: '#0ea5e9', color: 'var(--text-primary)',
              border: 'none', borderRadius: 10, fontSize: 13, fontWeight: 600,
              cursor: 'pointer', textDecoration: 'none', transition: 'background 0.15s',
            }}
            onMouseEnter={e => e.currentTarget.style.background = '#38bdf8'}
            onMouseLeave={e => e.currentTarget.style.background = '#0ea5e9'}
          >
            <ExternalLink size={14} />
            Open in CxAlloy
          </a>
        ) : (
          <div style={{ fontSize: 12, color: '#475569', textAlign: 'center' }}>No CxAlloy link available</div>
        )}
      </div>
    </div>,
    document.body
  )
}

// ─── S-Curve tooltip ──────────────────────────────────────────────────────────
function SCurveTooltip({ active, payload, label }) {
  if (!active || !payload?.length) return null
  return (
    <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 8, padding: '10px 14px', fontSize: 12, minWidth: 160 }}>
      <div style={{ color: '#64748b', fontWeight: 600, marginBottom: 8 }}>{label}</div>
      {payload.map((p, i) => p.value != null && (
        <div key={i} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 14, marginBottom: 3 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <div style={{ width: 8, height: 8, borderRadius: '50%', background: p.color }} />
            <span style={{ color: '#94a3b8' }}>{p.name}</span>
          </div>
          <span style={{ fontWeight: 700, color: 'var(--text-primary)' }}>{p.value}</span>
        </div>
      ))}
    </div>
  )
}

// ─── Pace card ────────────────────────────────────────────────────────────────
function PaceCard({ label, value, desc, color }) {
  return (
    <div style={{ background: 'var(--bg-card)', border: `1px solid ${color}30`, borderRadius: 12, padding: '18px 20px' }}>
      <div style={{ fontSize: 10, color: '#475569', textTransform: 'uppercase', letterSpacing: '0.1em', fontWeight: 600, marginBottom: 6 }}>{label}</div>
      <div style={{ fontSize: 32, fontWeight: 800, color, lineHeight: 1, marginBottom: 4 }}>{value}</div>
      <div style={{ fontSize: 12, color: '#64748b' }}>{desc}</div>
    </div>
  )
}

// ─── Main page ────────────────────────────────────────────────────────────────
const PAGED = 10

export default function PlannedVsActualPage() {
  const { selectedProjects, activeProject } = useProject()
  const targets = selectedProjects.length > 0 ? selectedProjects : (activeProject ? [activeProject] : [])

  const [loading,    setLoading]    = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [checklists, setChecklists] = useState([])
  const [error,      setError]      = useState(null)
  const [overdueVisible, setOverdueVisible] = useState(PAGED)
  const [next14Visible,  setNext14Visible]  = useState(PAGED)
  const [modalItem,  setModalItem]  = useState(null)
  const [planBaseline, setPlanBaseline] = useState(null) // { rows, fileName }
  const [importLoading, setImportLoading] = useState(false)
  const [chartView, setChartView] = useState('Overall') // 'Overall' | 'D' | 'W' | 'M'
  const fileInputRef = React.useRef(null)

  const handlePlanImport = async (e) => {
    const file = e.target.files?.[0]
    if (!file) return
    e.target.value = '' // reset so same file can be re-imported
    setImportLoading(true)
    try {
      const rows = await parsePlanBaseline(file)
      if (!rows.length) { toast.error('No valid rows found in file'); return }
      setPlanBaseline({ rows, fileName: file.name, count: rows.length })
      toast.success(`Plan baseline loaded: ${rows.length} items from "${file.name}"`)
    } catch (err) {
      toast.error(`Import failed: ${err.message}`)
    } finally {
      setImportLoading(false)
    }
  }

  const clearPlanBaseline = () => {
    setPlanBaseline(null)
    toast.success('Plan baseline cleared')
  }

  const loadData = useCallback(async () => {
    if (!targets.length) return
    setLoading(true)
    setError(null)
    try {
      const results = await Promise.all(
        targets.map(p => checklistsApi.getAll(p.externalId).then(r => r.data?.data || []).catch(() => []))
      )
      setChecklists(results.flat())
    } catch {
      setError('Failed to load checklist data')
      toast.error('Failed to load checklist data')
    } finally {
      setLoading(false)
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [targets.map(p => p.externalId).join(',')])

  useEffect(() => { loadData() }, [targets.map(p => p.externalId).join(',')])

  const handleRefresh = async () => {
    setRefreshing(true)
    await loadData()
    setRefreshing(false)
    toast.success('Data refreshed')
  }

  const sCurveData  = useMemo(() => buildSCurveData(checklists, planBaseline?.rows, chartView), [checklists, planBaseline, chartView])
  const paceMetrics = useMemo(() => buildPaceMetrics(checklists),   [checklists])
  const overdueItems = useMemo(() => computeOverdue(checklists),    [checklists])
  const next14Days   = useMemo(() => computeNext14Days(checklists, planBaseline?.rows), [checklists, planBaseline])

  // Tag counts for overdue summary — always show all 6 rows
  const overdueTagCounts = useMemo(() => {
    const counts = { red: 0, yellow: 0, green: 0, blue: 0, white: 0, other: 0 }
    overdueItems.forEach(i => { counts[i.tag] = (counts[i.tag] || 0) + 1 })
    return counts
  }, [overdueItems])

  if (!targets.length) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: 300, color: '#475569', gap: 12 }}>
        <TrendingUp size={28} />
        <div style={{ fontSize: 15, fontWeight: 600, color: 'var(--text-primary)' }}>No Project Selected</div>
        <div style={{ fontSize: 13 }}>Select a project to view planned vs actual progress</div>
      </div>
    )
  }

  return (
    <div className="space-y-5 animate-fade-in">
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 16 }}>
        <div>
          <h2 style={{ fontSize: 22, fontWeight: 800, color: 'var(--text-primary)', margin: 0 }}>Planned vs Actual</h2>
          <p style={{ fontSize: 13, color: '#64748b', marginTop: 4 }}>S-curve view with cumulative progression and overdue checklist drill-down.</p>
        </div>

        {/* Action buttons */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>

          {/* Hidden file input */}
          <input
            ref={fileInputRef}
            type="file"
            accept=".xlsx,.xls,.csv"
            style={{ display: 'none' }}
            onChange={handlePlanImport}
          />

          {/* Plan baseline status pill — shown when a file is loaded */}
          {planBaseline && (
            <div style={{
              display: 'flex', alignItems: 'center', gap: 6,
              padding: '6px 10px', borderRadius: 8,
              background: 'rgba(34,197,94,0.1)', border: '1px solid rgba(34,197,94,0.25)',
              fontSize: 11, color: '#4ade80', fontWeight: 600, maxWidth: 220,
            }}>
              <FileSpreadsheet size={12} />
              <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {planBaseline.count} items · {planBaseline.fileName}
              </span>
              <button
                onClick={clearPlanBaseline}
                title="Remove baseline"
                style={{ background: 'none', border: 'none', cursor: 'pointer', color: '#4ade80', padding: 0, lineHeight: 0, flexShrink: 0 }}
              >
                <Trash2 size={11} />
              </button>
            </div>
          )}

          {/* Import Plan Baseline button */}
          <button
            onClick={() => fileInputRef.current?.click()}
            disabled={importLoading}
            title="Import plan baseline (.xlsx or .csv with Name, Start, End columns)"
            style={{
              display: 'flex', alignItems: 'center', gap: 6,
              padding: '8px 14px',
              background: planBaseline ? 'rgba(34,197,94,0.08)' : 'var(--bg-card)',
              border: planBaseline ? '1px solid rgba(34,197,94,0.3)' : '1px solid var(--border)',
              borderRadius: 8, cursor: importLoading ? 'wait' : 'pointer',
              color: planBaseline ? '#4ade80' : '#94a3b8',
              fontSize: 12, fontWeight: 600, transition: 'all 0.15s',
            }}
          >
            <Upload size={13} style={{ animation: importLoading ? 'spin 1s linear infinite' : 'none' }} />
            {importLoading ? 'Importing…' : 'Import Plan'}
          </button>

          {/* Refresh button */}
          <button
            onClick={handleRefresh}
            disabled={refreshing}
            style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '8px 14px', background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 8, cursor: 'pointer', color: '#94a3b8', fontSize: 12, fontWeight: 600 }}
          >
            <RefreshCw size={13} style={{ animation: refreshing ? 'spin 1s linear infinite' : 'none' }} />
            Refresh
          </button>
        </div>
      </div>

      {/* Loading */}
      {loading && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 14 }}>
          {[...Array(4)].map((_, i) => (
            <div key={i} style={{ background: 'var(--bg-card)', borderRadius: 12, height: 100, border: '1px solid var(--border)' }} />
          ))}
        </div>
      )}

      {!loading && (
        <>
          {/* Pace Cards */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 14 }}>
            <PaceCard label="D Value"    value={paceMetrics.d}                          desc="Checklist pace per day"    color="#38bdf8" />
            <PaceCard label="W Value"    value={paceMetrics.w}                          desc="Checklist pace per week"   color="#4ade80" />
            <PaceCard label="M Value"    value={paceMetrics.m}                          desc="Checklist pace per month"  color="#f59e0b" />
            <PaceCard label="Cumulative" value={paceMetrics.cumulative.toLocaleString()} desc="Overall cumulative checklists" color="#a78bfa" />
          </div>

          {/* S-Curve */}
          <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 14, padding: '20px 24px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 2 }}>
              <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--text-primary)' }}>S-Curve</div>
              {/* D / W / Overall / M toggle */}
              <div style={{ display: 'flex', gap: 4 }}>
                {['Overall', 'D', 'W', 'M'].map(v => (
                  <button
                    key={v}
                    onClick={() => setChartView(v)}
                    style={{
                      padding: '4px 12px', borderRadius: 6, fontSize: 12, fontWeight: 600, cursor: 'pointer',
                      background: chartView === v ? '#3b82f6' : 'var(--bg-card)',
                      border: chartView === v ? '1px solid #3b82f6' : '1px solid var(--border)',
                      color: chartView === v ? '#fff' : '#94a3b8',
                      transition: 'all 0.15s',
                    }}
                  >{v}</button>
                ))}
              </div>
            </div>
            <div style={{ fontSize: 11, color: '#64748b', marginBottom: 16 }}>
              Stacked stage values with cumulative lines ({chartView === 'D' ? 'Daily' : chartView === 'W' ? 'Weekly' : chartView === 'M' ? 'Monthly' : 'Overall'})
            </div>

            {sCurveData.length > 0 ? (
              <ResponsiveContainer width="100%" height={320}>
                <ComposedChart data={sCurveData} margin={{ top: 8, right: 36, bottom: 8, left: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--divider)" />
                  <XAxis
                    dataKey="week"
                    tick={{ fill: '#64748b', fontSize: 10 }}
                    axisLine={false}
                    tickLine={false}
                    interval="preserveStartEnd"
                    tickFormatter={v => {
                      // D: "2025-11-06" → "Nov 6"
                      if (chartView === 'D') {
                        const d = new Date(v)
                        return isNaN(d) ? v : d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' })
                      }
                      // M: "2025-11" → "Nov 25"
                      if (chartView === 'M') {
                        const [yr, mo] = v.split('-')
                        const d = new Date(Number(yr), Number(mo) - 1, 1)
                        return isNaN(d) ? v : d.toLocaleDateString('en-US', { month: 'short', year: '2-digit' })
                      }
                      // W / Overall: "2025-W42" → "25-W42"
                      return v.replace('20', '')
                    }}
                  />
                  <YAxis yAxisId="left"  tick={{ fill: '#64748b', fontSize: 10 }} axisLine={false} tickLine={false} />
                  <YAxis yAxisId="right" orientation="right" tick={{ fill: '#64748b', fontSize: 10 }} axisLine={false} tickLine={false} />
                  <Tooltip content={<SCurveTooltip />} />

                  {/* Stacked areas — green bottom, then yellow, red, blue */}
                  <Area yAxisId="left" type="monotone" dataKey="green"  name="green"  stackId="1" fill="rgba(34,197,94,0.65)"  stroke="none" />
                  <Area yAxisId="left" type="monotone" dataKey="yellow" name="yellow" stackId="1" fill="rgba(234,179,8,0.65)"  stroke="none" />
                  <Area yAxisId="left" type="monotone" dataKey="red"    name="red"    stackId="1" fill="rgba(239,68,68,0.65)"   stroke="none" />
                  <Area yAxisId="left" type="monotone" dataKey="blue"   name="blue"   stackId="1" fill="rgba(59,130,246,0.55)"  stroke="none" />
                  <Area yAxisId="left" type="monotone" dataKey="white"  name="white"  stackId="1" fill="rgba(148,163,184,0.35)" stroke="none" />

                  {/* Cumulative lines */}
                  <Line yAxisId="right" type="monotone" dataKey="cumActual" name="Actual CUM"  stroke="#22c55e"              strokeWidth={2}   dot={false} connectNulls />
                  <Line yAxisId="right" type="monotone" dataKey="cumTarget" name="Target CUM"  stroke="rgba(100,116,139,0.5)" strokeWidth={2}   strokeDasharray="6 4" dot={false} />
                </ComposedChart>
              </ResponsiveContainer>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: 260, color: '#475569', gap: 8 }}>
                <Activity size={28} />
                <div style={{ fontSize: 13 }}>No checklist data to plot</div>
              </div>
            )}

            <div style={{ marginTop: 12, fontSize: 11, color: '#334155', fontStyle: 'italic' }}>
              {planBaseline
                ? `Target line uses imported plan baseline: "${planBaseline.fileName}" (${planBaseline.count} items). Actual line uses completion dates where available, falling back to creation dates.`
                : 'Actual line uses completion dates where available, falling back to creation dates. Target is a linear estimate — upload a plan baseline for accurate planned vs actual comparison.'
              }
            </div>
          </div>

          {/* Overdue + Next 14 Days */}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>

            {/* ── Overdue Items ────────────────────────────────────────────── */}
            <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 14, overflow: 'hidden' }}>
              {/* Header */}
              <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--divider)' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                  <AlertTriangle size={14} color="#f87171" />
                  <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' }}>Overdue Items</span>
                  {overdueItems.length > 0 && (
                    <span style={{ marginLeft: 'auto', fontSize: 11, padding: '2px 8px', borderRadius: 20, background: 'rgba(239,68,68,0.12)', border: '1px solid rgba(239,68,68,0.25)', color: '#f87171', fontWeight: 600 }}>
                      {overdueItems.length}
                    </span>
                  )}
                </div>
                <div style={{ fontSize: 11, color: '#475569' }}>Red/Yellow/Green/Blue/White/Other counts with delay in days</div>

                {/* Always show all 6 tag rows */}
                <div style={{ marginTop: 12, display: 'flex', flexDirection: 'column', gap: 6 }}>
                  {['red','yellow','green','blue','white','other'].map(tag => {
                    const c = TAG_COLORS[tag]
                    const count = overdueTagCounts[tag] || 0
                    return (
                      <div key={tag} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <div style={{ width: 9, height: 9, borderRadius: '50%', background: c.dot, flexShrink: 0 }} />
                        <span style={{ fontSize: 13, color: count > 0 ? c.text : '#475569', fontWeight: 500, textTransform: 'capitalize', flex: 1 }}>
                          {tag.charAt(0).toUpperCase() + tag.slice(1)}
                        </span>
                        <span style={{ fontSize: 13, fontWeight: 700, color: count > 0 ? 'var(--text-primary)' : '#334155' }}>{count}</span>
                      </div>
                    )
                  })}
                </div>
              </div>

              {/* Item rows */}
              <div>
                {overdueItems.slice(0, overdueVisible).map((item, i) => {
                  const c = TAG_COLORS[item.tag] || TAG_COLORS.other
                  return (
                    <div
                      key={item.id || i}
                      onClick={() => setModalItem(item)}
                      style={{
                        display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8,
                        padding: '12px 20px', borderBottom: '1px solid var(--border-subtle)',
                        cursor: 'pointer', transition: 'background 0.12s',
                      }}
                      onMouseEnter={e => e.currentTarget.style.background = 'var(--row-alt)'}
                      onMouseLeave={e => e.currentTarget.style.background = 'transparent'}
                    >
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-primary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={item.name}>
                          {item.name}
                        </div>
                        {item.description && (
                          <div style={{ fontSize: 11, color: '#64748b', marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            {item.description}
                          </div>
                        )}
                      </div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0 }}>
                        <span style={{ fontSize: 11, padding: '2px 8px', borderRadius: 20, background: c.bg, border: `1px solid ${c.border}`, color: c.text, fontWeight: 600, textTransform: 'capitalize' }}>
                          {item.tag}
                        </span>
                        <span style={{ fontSize: 11, color: '#f87171', fontWeight: 600, whiteSpace: 'nowrap' }}>
                          {item.delay}d delay
                        </span>
                      </div>
                    </div>
                  )
                })}

                {!overdueItems.length && (
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '40px 20px', gap: 8 }}>
                    <CheckSquare size={22} color="#22c55e" />
                    <div style={{ fontSize: 12, color: '#475569' }}>All checklists are on track</div>
                  </div>
                )}

                {overdueItems.length > overdueVisible && (
                  <div style={{ padding: '12px 20px', textAlign: 'center' }}>
                    <button
                      onClick={() => setOverdueVisible(v => v + PAGED)}
                      style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 18px', background: 'rgba(239,68,68,0.07)', border: '1px solid rgba(239,68,68,0.2)', borderRadius: 8, cursor: 'pointer', color: '#f87171', fontSize: 12, fontWeight: 600 }}
                    >
                      <ChevronRight size={13} />
                      Load {Math.min(PAGED, overdueItems.length - overdueVisible)} more · {overdueItems.length - overdueVisible} remaining
                    </button>
                  </div>
                )}
              </div>
            </div>

            {/* ── Next 14 Days ─────────────────────────────────────────────── */}
            <div style={{ background: 'var(--bg-card)', border: '1px solid var(--border)', borderRadius: 14, overflow: 'hidden' }}>
              <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--divider)' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                  <Calendar size={14} color="#38bdf8" />
                  <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--text-primary)' }}>Next 14 Days</span>
                  {next14Days.length > 0 && (
                    <span style={{ marginLeft: 'auto', fontSize: 11, padding: '2px 8px', borderRadius: 20, background: 'rgba(56,189,248,0.12)', border: '1px solid rgba(56,189,248,0.25)', color: '#38bdf8', fontWeight: 600 }}>
                      {next14Days.length}
                    </span>
                  )}
                </div>
                <div style={{ fontSize: 11, color: '#475569' }}>Upcoming checklist activity preview</div>
              </div>

              <div>
                {next14Days.slice(0, next14Visible).map((item, i) => (
                  <div
                    key={item.id || i}
                    onClick={() => {
                      const url = cxUrl(item.projectId, item.externalId)
                      if (url) window.open(url, '_blank', 'noopener')
                    }}
                    style={{
                      display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8,
                      padding: '12px 20px', borderBottom: '1px solid var(--border-subtle)',
                      cursor: 'pointer', transition: 'background 0.12s',
                    }}
                    onMouseEnter={e => e.currentTarget.style.background = 'var(--row-alt)'}
                    onMouseLeave={e => e.currentTarget.style.background = 'transparent'}
                  >
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--text-primary)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={item.name}>
                        {item.name}
                      </div>
                      <div style={{ fontSize: 11, marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: item.tagColor?.text || '#64748b', fontWeight: 500 }}>
                        {item.tagLabel}
                      </div>
                    </div>
                    <div style={{ fontSize: 11, color: '#64748b', fontFamily: 'monospace', flexShrink: 0 }}>
                      {item.dueDate.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}
                    </div>
                  </div>
                ))}

                {!next14Days.length && (
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', padding: '40px 20px', gap: 8 }}>
                    <Calendar size={22} color="#334155" />
                    <div style={{ fontSize: 12, color: '#475569' }}>No open checklist activity found — sync checklists first</div>
                  </div>
                )}

                {next14Days.length > next14Visible && (
                  <div style={{ padding: '12px 20px', textAlign: 'center' }}>
                    <button
                      onClick={() => setNext14Visible(v => v + PAGED)}
                      style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 18px', background: 'rgba(56,189,248,0.07)', border: '1px solid rgba(56,189,248,0.2)', borderRadius: 8, cursor: 'pointer', color: '#38bdf8', fontSize: 12, fontWeight: 600 }}
                    >
                      <ChevronRight size={13} />
                      Load {Math.min(PAGED, next14Days.length - next14Visible)} more · {next14Days.length - next14Visible} remaining
                    </button>
                  </div>
                )}
              </div>
            </div>
          </div>
        </>
      )}

      {/* Detail modal for overdue items */}
      {modalItem && <ChecklistModal item={modalItem} onClose={() => setModalItem(null)} />}
    </div>
  )
}
