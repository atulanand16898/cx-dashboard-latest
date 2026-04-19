export const CHECKLIST_TAG_ORDER = ['red', 'yellow', 'green', 'blue']
export const DASHBOARD_CHECKLIST_TAG_ORDER = [...CHECKLIST_TAG_ORDER, 'non_critical']

export const CHECKLIST_TAG_COLORS = {
  red: '#ef4444',
  yellow: '#eab308',
  green: '#22c55e',
  blue: '#3b82f6',
  non_critical: '#94a3b8',
  white: '#94a3b8',
}

const DIRECT_TAGS = new Set([...CHECKLIST_TAG_ORDER, 'white'])

const DISPLAY_LABELS = {
  red: 'Red Tag (L1 / L2A)',
  yellow: 'Yellow Tag (L2 / L2B)',
  green: 'Green Tag (L3)',
  blue: 'Blue Tag (L4)',
  non_critical: 'Non-Critical Checklist',
  white: 'White Tag',
  other: 'Checklist',
}

export function checklistTagDisplayLabel(tag) {
  return DISPLAY_LABELS[(tag || '').toLowerCase()] || DISPLAY_LABELS.other
}

function detectByPrimaryMethod(value) {
  if (!value) return null
  const text = String(value).toLowerCase().trim()

  if (/\bl5\b/.test(text) || /\blevel[-_\s]?5\b/.test(text) || text === '5') return 'white'
  if (/\bl2[-_\s]?a\b/.test(text) || /\blevel[-_\s]?2[-_\s]?a\b/.test(text)) return 'red'
  if (/\bl2[-_\s]?b\b/.test(text) || /\blevel[-_\s]?2[-_\s]?b\b/.test(text)) return 'yellow'

  if (/\bl1\b/.test(text) || /\blevel[-_\s]?1\b/.test(text) || text === '1') return 'red'
  if (/\bl2\b/.test(text) || /\blevel[-_\s]?2\b/.test(text) || text === '2') return 'yellow'
  if (/\bl3\b/.test(text) || /\blevel[-_\s]?3\b/.test(text) || text === '3') return 'green'
  if (/\bl4\b/.test(text) || /\blevel[-_\s]?4\b/.test(text) || text === '4') return 'blue'

  return null
}

function detectNonCritical(value) {
  if (!value) return null
  const text = String(value).toLowerCase().trim()

  if (text.includes('non critical') || text.includes('non-critical') || /\bnc\b/.test(text)) {
    return 'non_critical'
  }

  return null
}

function detectByLegacyMethod(value) {
  if (!value) return null
  const text = String(value).toLowerCase().trim()

  if (/\bitr[-_\s]?a\b/.test(text) || text === 'itra' || text === 'itr-a') return 'red'
  if (/\bitr[-_\s]?b\b/.test(text) || text === 'itrb' || text === 'itr-b') return 'yellow'
  if (/\bitr[-_\s]?c\b/.test(text) || text === 'itrc' || text === 'itr-c') return 'green'
  if (/\bitr[-_\s]?d\b/.test(text) || text === 'itrd' || text === 'itr-d') return 'blue'

  if (/\bred\b/.test(text)) return 'red'
  if (/\byellow\b/.test(text)) return 'yellow'
  if (/\bgreen\b/.test(text)) return 'green'
  if (/\bblue\b/.test(text)) return 'blue'

  if (text.includes('pre-cx') || text.includes('precx') || text.includes('pre cx') || text.includes('fat')) return 'red'
  if (/\bcx[-_]?a\b/.test(text) || text.includes('installation') || text.includes('qa/qc') || text.includes('ivc')) return 'yellow'
  if (/\bcx[-_]?b\b/.test(text) || text.includes('startup') || text.includes('start-up') || text.includes('pre-functional') || text.includes('prefunctional') || text.includes('pfc')) return 'green'
  if (text.includes('functional performance') || text.includes('fpt') || text.includes('sign-off') || text.includes('sign off')) return 'blue'

  return null
}

export function deriveChecklistTag(checklist) {
  const sources = [
    checklist?.tagColor,
    checklist?.tag_color,
    checklist?.color,
    checklist?.tagLevel,
    checklist?.tag_level,
    checklist?.checklistType,
    checklist?.checklist_type,
    checklist?.name,
    checklist?.rawJson,
    checklist?.raw_json,
  ].filter(Boolean)

  for (const source of sources) {
    const tag = detectByPrimaryMethod(source)
    if (tag) return tag
  }

  for (const source of sources) {
    const tag = detectNonCritical(source)
    if (tag) return tag
  }

  const directColor = String(checklist?.tagColor || checklist?.tag_color || checklist?.color || '').toLowerCase().trim()
  if (DIRECT_TAGS.has(directColor) && directColor !== 'white') return directColor

  const directTagLevel = String(checklist?.tagLevel || checklist?.tag_level || '').toLowerCase().trim()
  if (DIRECT_TAGS.has(directTagLevel) && directTagLevel !== 'white') return directTagLevel

  for (const source of sources) {
    const tag = detectByLegacyMethod(source)
    if (tag) return tag
  }

  if (DIRECT_TAGS.has(directColor)) return directColor
  if (DIRECT_TAGS.has(directTagLevel)) return directTagLevel

  return 'white'
}
