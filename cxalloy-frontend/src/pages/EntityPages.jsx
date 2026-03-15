import React from 'react'
import GenericListPage from '../components/ui/GenericListPage'
import { tasksApi, checklistsApi, assetsApi, personsApi, companiesApi, rolesApi } from '../services/api'
import { useProject } from '../context/ProjectContext'
import { cacheKeys } from '../services/apiCache'
import { CheckSquare, Tag, Server, Users, Building2, UserCog } from 'lucide-react'
import { StatusBadge } from '../components/ui'

// ─── Tasks ────────────────────────────────────────────────────────────────────
export function TasksPage() {
  const { activeProject } = useProject()
  return (
    <GenericListPage
      fetchFn={(pid) => tasksApi.getAll(pid ? { projectId: pid } : {})}
      syncFn={(pid) => tasksApi.sync(pid)}
      activeProjectId={activeProject?.externalId}
      cacheKey={activeProject?.externalId ? cacheKeys.tasks(activeProject.externalId) : null}
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
export function ChecklistsPage() {
  const { activeProject } = useProject()
  return (
    <GenericListPage
      fetchFn={(pid) => checklistsApi.getAll(pid)}
      syncFn={(pid) => checklistsApi.sync(pid)}
      activeProjectId={activeProject?.externalId}
      cacheKey={activeProject?.externalId ? cacheKeys.checklists(activeProject.externalId) : null}
      emptyIcon={Tag}
      emptyTitle="No Checklists Found"
      emptyDesc="Sync to pull checklists from CxAlloy"
      searchKeys={['name', 'externalId', 'status', 'type']}
      columns={[
        { key: 'externalId', label: 'ID', render: v => <span className="font-mono text-xs text-dark-400">{v || '—'}</span> },
        { key: 'name', label: 'Name', render: v => <span className="font-500 text-white">{v || '—'}</span> },
        { key: 'type', label: 'Type' },
        { key: 'status', label: 'Status', render: v => <StatusBadge status={v} /> },
        { key: 'createdAt', label: 'Created' },
      ]}
    />
  )
}

// ─── Assets ───────────────────────────────────────────────────────────────────
export function AssetsPage() {
  const { activeProject } = useProject()
  return (
    <GenericListPage
      fetchFn={(pid) => assetsApi.getAll(pid ? { projectId: pid } : {})}
      syncFn={(pid) => assetsApi.syncAll(pid)}
      activeProjectId={activeProject?.externalId}
      cacheKey={activeProject?.externalId ? cacheKeys.assets(activeProject.externalId) : null}
      emptyIcon={Server}
      emptyTitle="No Assets Found"
      emptyDesc="Sync to pull equipment, buildings, systems from CxAlloy"
      searchKeys={['name', 'externalId', 'type', 'assetType']}
      columns={[
        { key: 'externalId', label: 'ID', render: v => <span className="font-mono text-xs text-dark-400">{v || '—'}</span> },
        { key: 'name', label: 'Name', render: v => <span className="font-500 text-white">{v || '—'}</span> },
        { key: 'type', label: 'Type', render: v => v ? <span className="status-badge tag-blue">{v}</span> : '—' },
        { key: 'assetType', label: 'Asset Type' },
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
      fetchFn={(pid) => personsApi.getAll(pid)}
      syncFn={(pid) => personsApi.sync(pid)}
      activeProjectId={activeProject?.externalId}
      cacheKey={activeProject?.externalId ? cacheKeys.persons(activeProject.externalId) : null}
      emptyIcon={Users}
      emptyTitle="No Personnel Found"
      emptyDesc="Sync to pull personnel data from CxAlloy"
      searchKeys={['firstName', 'lastName', 'email', 'company', 'externalId']}
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
      fetchFn={(pid) => companiesApi.getAll(pid)}
      syncFn={(pid) => companiesApi.sync(pid)}
      activeProjectId={activeProject?.externalId}
      cacheKey={activeProject?.externalId ? cacheKeys.companies(activeProject.externalId) : null}
      emptyIcon={Building2}
      emptyTitle="No Companies Found"
      emptyDesc="Sync to pull company data from CxAlloy"
      searchKeys={['name', 'externalId', 'type', 'contactEmail']}
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
      fetchFn={(pid) => rolesApi.getAll(pid)}
      syncFn={(pid) => rolesApi.sync(pid)}
      activeProjectId={activeProject?.externalId}
      cacheKey={activeProject?.externalId ? cacheKeys.roles(activeProject.externalId) : null}
      emptyIcon={UserCog}
      emptyTitle="No Roles Found"
      emptyDesc="Sync to pull roles from CxAlloy"
      searchKeys={['name', 'externalId', 'description']}
      columns={[
        { key: 'externalId', label: 'ID', render: v => <span className="font-mono text-xs text-dark-400">{v || '—'}</span> },
        { key: 'name', label: 'Role Name', render: v => <span className="font-500 text-white">{v || '—'}</span> },
        { key: 'description', label: 'Description' },
        { key: 'createdAt', label: 'Created' },
      ]}
    />
  )
}
