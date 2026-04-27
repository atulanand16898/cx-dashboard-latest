export const normalizeChecklistStatus = (status) =>
  String(status || '').trim().toLowerCase().replace(/[\s-]+/g, '_')

export const CHECKLIST_DONE_STATUSES = new Set([
  'finished',
  'complete',
  'completed',
  'done',
  'closed',
  'signed_off',
  'approved',
  'passed',
  'accepted_by_owner',
  'checklist_approved',
  'tag_complete',
])

export const isChecklistDone = (status) => CHECKLIST_DONE_STATUSES.has(normalizeChecklistStatus(status))

export const checklistActualFinishDate = (checklist) =>
  checklist?.actualFinishDate
  || checklist?.actual_finish_date
  || checklist?.completedDate
  || checklist?.completed_date
  || checklist?.latestFinishedDate
  || checklist?.latest_finished_date
  || null
