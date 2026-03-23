import React from 'react'
import GenericListPage from '../components/ui/GenericListPage'
import { tasksApi, checklistsApi, assetsApi, personsApi, companiesApi, rolesApi, equipmentApi } from '../services/api'
import { useProject } from '../context/ProjectContext'
import { CheckSquare, Tag, Server, Users, Building2, UserCog, Cpu } from 'lucide-react'
import { StatusBadge } from '../components/ui'

// ─── Tasks ────────────────────────────────────────────────────────────────────
export function TasksPage() {
  const { activeProject } = useProject()
  return (
    <GenericListPage
      entityType="tasks"
      fetchFn={(pid) => tasksApi.getAll(pid ? { projectId: pid } : {})}
      syncFn={(pid) => tasksApi.sync(pid)}
      activeProjectId={activeProject?.externalId}
      emptyIcon={CheckSquare}
      emptyTitle="No Tasks Found"
      emptyDesc="Sync to pull tasks from CxAlloy"
      searchKeys={['title', 'externalId', 'status', 'assignee']}
      columns={[
        { key: 'externalId', label: 'ID', render: v => <span className="font-mono text-xs text-dark-400">{v || '—'}</span> },
        { key: 'title', label: 'Title', render: v => <span className="font-500 text-white">{v || '—'}</span> },
        { key: 'status', label: 'Status', render: v => <StatusBadge status={v} /> },
        { key: 'assignee', label: 'Assignee' },
        { key: 'dueDate', label: 'Due Date' },
      ]}
    />
  )
}

// ─── Checklists ───────────────────────────────────────────────────────────────
// Helper: format ISO date strings to MM/DD/YYYY matching CxAlloy UI
function fmtDate(v) {
  if (!v) return '—'
  try {
    const d = new Date(v)
    if (isNaN(d)) return v
    return d.toLocaleDateString('en-US', { month: '2-digit', day: '2-digit', year: 'numeric' })
  } catch { return v }
}

export function ChecklistsPage() {
  const { activeProject } = useProject()
  return (
    <GenericListPage
      entityType="checklists"
      fetchFn={(pid) => checklistsApi.getAll(pid)}
      syncFn={(pid) => checklistsApi.syncWithStatusDates(pid)}
      activeProjectId={activeProject?.externalId}
      emptyIcon={Tag}
      emptyTitle="No Checklists Found"
      emptyDesc="Sync to pull checklists from CxAlloy"
      searchKeys={['name', 'externalId', 'status', 'checklistType']}
      filterConfigs={[
        { key: 'status', label: 'All Statuses', getValue: item => item.status || '' },
        { key: 'tagLevel', label: 'All Tags', getValue: item => item.tagLevel || '' },
        { key: 'checklistType', label: 'All Types', getValue: item => item.checklistType || '' },
      ]}
      columns={[
        { key: 'externalId', label: 'ID', render: v => <span className="font-mono text-xs text-dark-400">{v || '—'}</span> },
        { key: 'name', label: 'Name', render: v => <span className="font-500 text-white">{v || '—'}</span> },
        {
          key: 'checklistType', label: 'Type',
          render: v => v
            ? <span className="status-badge tag-gray">{v}</span>
            : <span className="text-dark-500">—</span>
        },
        { key: 'status', label: 'Status', render: v => <StatusBadge status={v} /> },
        { key: 'createdAt', label: 'Created', render: v => <span className="font-mono text-xs text-dark-400">{fmtDate(v)}</span>, exportValue: v => fmtDate(v) },
        { key: 'updatedAt', label: 'Last Updated', render: v => <span className="font-mono text-xs text-dark-400">{fmtDate(v)}</span>, exportValue: v => fmtDate(v) },
        { key: 'latestFinishedDate', label: 'Finished Status Date', render: v => <span className="font-mono text-xs text-dark-400">{fmtDate(v)}</span>, exportValue: v => fmtDate(v) },
        { key: 'actualFinishDate', label: 'Actual Finish', render: v => <span className="font-mono text-xs text-dark-400">{fmtDate(v)}</span>, exportValue: v => fmtDate(v) },
      ]}
    />
  )
}

// ─── Equipment ────────────────────────────────────────────────────────────────
// Dedicated page for CxAlloy GET /equipment — individual equipment records with
// type, discipline, tag, status, and location hierarchy fields.
export function EquipmentPage() {
  const { activeProject } = useProject()
  return (
    <GenericListPage
      entityType="equipment"
      fetchFn={(pid) => equipmentApi.getAll(pid)}
      syncFn={(pid) => equipmentApi.sync(pid)}
      activeProjectId={activeProject?.externalId}
      emptyIcon={Cpu}
      emptyTitle="No Equipment Found"
      emptyDesc="Sync to pull equipment records from CxAlloy GET /equipment"
      searchKeys={['name', 'tag', 'externalId', 'equipmentType', 'discipline', 'status']}
      showStats={false}
      columns={[
        { key: 'externalId', label: 'ID', render: v => <span className="font-mono text-xs text-dark-400">{v || '—'}</span> },
        { key: 'name', label: 'Name', render: v => <span className="font-500 text-white">{v || '—'}</span> },
        { key: 'tag', label: 'Tag', render: v => v ? <span className="status-badge tag-blue font-mono text-xs">{v}</span> : '—' },
        { key: 'equipmentType', label: 'Type', render: v => v ? <span className="status-badge tag-gray">{v}</span> : '—' },
        { key: 'discipline', label: 'Discipline', render: v => v || '—' },
        { key: 'status', label: 'Status', render: v => <StatusBadge status={v} /> },
      ]}
    />
  )
}

// ─── Assets ───────────────────────────────────────────────────────────────────
export function AssetsPage() {
  const { activeProject } = useProject()
  return (
    <GenericListPage
      entityType="assets"
      fetchFn={(pid) => assetsApi.getAll(pid ? { projectId: pid } : {})}
      syncFn={(pid) => assetsApi.syncAll(pid)}
      activeProjectId={activeProject?.externalId}
      emptyIcon={Server}
      emptyTitle="No Assets Found"
      emptyDesc="Sync to pull equipment, buildings, systems from CxAlloy"
      searchKeys={['name', 'externalId', 'type', 'assetType']}
      showStats={false}
      columns={[
        { key: 'externalId', label: 'ID', render: v => <span className="font-mono text-xs text-dark-400">{v || '—'}</span> },
        { key: 'name', label: 'Name', render: v => <span className="font-500 text-white">{v || '—'}</span> },
        { key: 'type', label: 'Type', render: v => v ? <span className="status-badge tag-blue">{v}</span> : '—' },
        { key: 'assetType', label: 'Asset Type', render: v => v ? <span className="status-badge tag-gray capitalize">{v}</span> : '—' },
        { key: 'status', label: 'Status', render: v => <StatusBadge status={v} /> },
        { key: 'location', label: 'Location' },
      ]}
    />
  )
}

// ─── Persons ──────────────────────────────────────────────────────────────────
export function PersonsPage() {
  const { activeProject } = useProject()
  return (
    <GenericListPage
      entityType="persons"
      fetchFn={(pid) => personsApi.getAll(pid)}
      syncFn={(pid) => personsApi.sync(pid)}
      activeProjectId={activeProject?.externalId}
      emptyIcon={Users}
      emptyTitle="No Personnel Found"
      emptyDesc="Sync to pull personnel data from CxAlloy"
      searchKeys={['firstName', 'lastName', 'email', 'company', 'externalId']}
      showStats={false}
      columns={[
        { key: 'externalId', label: 'ID', render: v => <span className="font-mono text-xs text-dark-400">{v || '—'}</span> },
        { key: 'firstName', label: 'First Name', render: v => <span className="font-500 text-white">{v || '—'}</span> },
        { key: 'lastName', label: 'Last Name' },
        { key: 'email', label: 'Email', render: v => v ? <a href={`mailto:${v}`} className="text-sky-400 hover:underline" onClick={e => e.stopPropagation()}>{v}</a> : '—' },
        { key: 'company', label: 'Company' },
        { key: 'role', label: 'Role' },
      ]}
    />
  )
}

// ─── Companies ────────────────────────────────────────────────────────────────
export function CompaniesPage() {
  const { activeProject } = useProject()
  return (
    <GenericListPage
      entityType="companies"
      fetchFn={(pid) => companiesApi.getAll(pid)}
      syncFn={(pid) => companiesApi.sync(pid)}
      activeProjectId={activeProject?.externalId}
      emptyIcon={Building2}
      emptyTitle="No Companies Found"
      emptyDesc="Sync to pull company data from CxAlloy"
      searchKeys={['name', 'externalId', 'type', 'contactEmail']}
      showStats={false}
      columns={[
        { key: 'externalId', label: 'ID', render: v => <span className="font-mono text-xs text-dark-400">{v || '—'}</span> },
        { key: 'name', label: 'Name', render: v => <span className="font-500 text-white">{v || '—'}</span> },
        { key: 'type', label: 'Type' },
        { key: 'contactEmail', label: 'Contact' },
        { key: 'phone', label: 'Phone' },
      ]}
    />
  )
}

// ─── Roles ────────────────────────────────────────────────────────────────────
export function RolesPage() {
  const { activeProject } = useProject()
  return (
    <GenericListPage
      entityType="roles"
      fetchFn={(pid) => rolesApi.getAll(pid)}
      syncFn={(pid) => rolesApi.sync(pid)}
      activeProjectId={activeProject?.externalId}
      emptyIcon={UserCog}
      emptyTitle="No Roles Found"
      emptyDesc="Sync to pull roles from CxAlloy"
      searchKeys={['name', 'externalId', 'description']}
      showStats={false}
      columns={[
        { key: 'externalId', label: 'ID', render: v => <span className="font-mono text-xs text-dark-400">{v || '—'}</span> },
        { key: 'name', label: 'Role Name', render: v => <span className="font-500 text-white">{v || '—'}</span> },
        { key: 'description', label: 'Description' },
        { key: 'createdAt', label: 'Created' },
      ]}
    />
  )
}
