import axios from 'axios'

const api = axios.create({
  baseURL: '/api',
  headers: { 'Content-Type': 'application/json' },
})

const isAuthRoute = (url = '') => url.includes('/auth/login') || url.includes('/auth/refresh')

// Attach JWT token to every request
api.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token')
  if (token && !isAuthRoute(config.url)) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// Handle 401 - token expired
api.interceptors.response.use(
  (res) => res,
  async (error) => {
    if (error.response?.status === 401 && !isAuthRoute(error.config?.url) && !error.config?._retry) {
      const refreshToken = localStorage.getItem('refresh_token')
      if (refreshToken) {
        try {
          error.config._retry = true
          const resp = await axios.post('/api/auth/refresh', { refreshToken })
          const { accessToken, refreshToken: newRefresh } = resp.data.data
          localStorage.setItem('access_token', accessToken)
          localStorage.setItem('refresh_token', newRefresh)
          error.config.headers.Authorization = `Bearer ${accessToken}`
          return api(error.config)
        } catch {
          localStorage.clear()
          window.location.href = '/login'
        }
      } else {
        localStorage.clear()
        window.location.href = '/login'
      }
    }
    return Promise.reject(error)
  }
)

// Auth
export const authApi = {
  login: (creds) => api.post('/auth/login', creds),
  logout: (refreshToken) => api.post('/auth/logout', { refreshToken }),
  me: () => api.get('/auth/me'),
}

export const projectAccessApi = {
  getAll: () => api.get('/project-access'),
  getProjectCatalog: () => api.get('/project-access/projects'),
  getVisibility: () => api.get('/project-access/visibility'),
  saveVisibility: (payload) => api.put('/project-access/visibility', payload),
  assign: (payload) => api.post('/project-access', payload),
  remove: (id) => api.delete(`/project-access/${id}`),
}

export const reportsApi = {
  getAll: (projectId) => api.get('/reports', { params: { projectId } }),
  getOptions: (projectId) => api.get('/reports/options', { params: { projectId } }),
  getById: (id) => api.get(`/reports/${id}`),
  generate: (payload) => api.post('/reports/generate', payload),
  download: (id, format = 'json') => api.get(`/reports/${id}/download`, {
    params: { format },
    responseType: 'blob',
  }),
}

// Projects
export const projectsApi = {
  getAll: () => api.get('/projects'),
  getIds: () => api.get('/projects/ids'),
  getById: (id) => api.get(`/projects/${id}`),
  getByExternalId: (extId) => api.get(`/projects/external/${extId}`),
  syncAll: () => api.post('/projects/sync'),
  syncOne: (projectId) => api.post(`/projects/sync/${projectId}`),
}

// Issues
export const issuesApi = {
  getAll: (projectId) => api.get('/issues', { params: projectId ? { projectId } : {} }),
  getById: (id) => api.get(`/issues/${id}`),
  getByExternalId: (extId) => api.get(`/issues/external/${extId}`),
  create: (data) => api.post('/issues', data),
  update: (issueId, data) => api.put(`/issues/${issueId}`, data),
  delete: (issueId, projectId) => api.delete(`/issues/${issueId}`, { params: projectId ? { projectId } : {} }),
  syncAll: (projectId) => api.post('/issues/sync', null, { params: projectId ? { projectId } : {} }),
  syncOne: (issueId, projectId) => api.post(`/issues/sync/${issueId}`, null, { params: projectId ? { projectId } : {} }),
}

// Tasks
export const tasksApi = {
  getAll: (params) => api.get('/tasks', { params }),
  getById: (id) => api.get(`/tasks/${id}`),
  sync: (projectId) => api.post('/tasks/sync', null, { params: projectId ? { projectId } : {} }),
}

// Checklists
export const checklistsApi = {
  getAll: (projectId) => api.get('/checklists', { params: projectId ? { projectId } : {} }),
  getById: (id) => api.get(`/checklists/${id}`),
  sync: (projectId) => api.post('/checklists/sync', null, { params: projectId ? { projectId } : {} }),
}

// Assets
export const assetsApi = {
  getAll: (params) => api.get('/assets', { params }),
  getById: (id) => api.get(`/assets/${id}`),
  syncAll: (projectId) => api.post('/assets/sync', null, { params: projectId ? { projectId } : {} }),
  syncType: (type, projectId) => api.post(`/assets/sync/${type}`, null, { params: projectId ? { projectId } : {} }),
}

// Persons
export const personsApi = {
  getAll: (projectId) => api.get('/persons', { params: projectId ? { projectId } : {} }),
  getById: (id) => api.get(`/persons/${id}`),
  sync: (projectId) => api.post('/persons/sync', null, { params: projectId ? { projectId } : {} }),
}

// Companies
export const companiesApi = {
  getAll: (projectId) => api.get('/companies', { params: projectId ? { projectId } : {} }),
  getById: (id) => api.get(`/companies/${id}`),
  sync: (projectId) => api.post('/companies/sync', null, { params: projectId ? { projectId } : {} }),
}

// Roles
export const rolesApi = {
  getAll: (projectId) => api.get('/roles', { params: projectId ? { projectId } : {} }),
  getById: (id) => api.get(`/roles/${id}`),
  sync: (projectId) => api.post('/roles/sync', null, { params: projectId ? { projectId } : {} }),
}

// Tracker Briefs — server-persisted export history
export const briefsApi = {
  // GET /api/briefs?projectId=X[&period=W]
  getByProject: (projectId, period) => api.get('/briefs', {
    params: { projectId, ...(period && period !== 'Overall' ? { period } : {}) }
  }),
  // GET /api/briefs/count?projectId=X
  count: (projectId) => api.get('/briefs/count', { params: { projectId } }),
  // POST /api/briefs — save a new brief after CSV export
  create: (payload) => api.post('/briefs', payload),
  // POST /api/briefs/generate?projectId=X&period=W
  // Auto-generates a snapshot from DB data — no CSV download needed
  generate: (projectId, period) => api.post('/briefs/generate', null, {
    params: { projectId, period: period || 'Overall' }
  }),
}

// Equipment (dedicated /equipment endpoint — separate from /assets)
export const equipmentApi = {
  getAll: (projectId) => api.get('/equipment', { params: projectId ? { projectId } : {} }),
  getLive: (projectId) => api.get('/equipment/live', { params: projectId ? { projectId } : {} }),
  getById: (id) => api.get(`/equipment/${id}`),
  sync: (projectId) => api.post('/equipment/sync', null, { params: projectId ? { projectId } : {} }),
  getMatrix: (projectId) => api.get('/equipment/matrix', { params: projectId ? { projectId } : {} }),
}

// AI Copilot
export const copilotApi = {
  getContext: (projectIds, query) => api.get('/copilot/context', {
    params: {
      ...(projectIds?.length ? { projectIds } : {}),
      ...(query ? { query } : {}),
    },
    paramsSerializer: {
      indexes: null,
    },
  }),
  chat: ({ payload, files = [] }) => {
    const formData = new FormData()
    formData.append('payload', JSON.stringify(payload))
    files.forEach(file => formData.append('files', file))
    return api.post('/copilot/chat', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
}

// Sync (orchestration)
export const syncApi = {
  syncAll: () => api.post('/sync/all'),
  getStatus: () => api.get('/sync/status'),
  getStats: () => api.get('/sync/stats'),
  syncIssues: (projectIds) => api.post('/sync/issues', projectIds ? { projectIds } : null),
  syncTasks: (projectIds) => api.post('/sync/tasks', projectIds ? { projectIds } : null),
  syncChecklists: (projectIds) => api.post('/sync/checklists', projectIds ? { projectIds } : null),
  syncPersons: (projectIds) => api.post('/sync/persons', projectIds ? { projectIds } : null),
  syncCompanies: (projectIds) => api.post('/sync/companies', projectIds ? { projectIds } : null),
  syncRoles: (projectIds) => api.post('/sync/roles', projectIds ? { projectIds } : null),
  syncAssets: (projectIds) => api.post('/sync/assets', projectIds ? { projectIds } : null),
  syncEquipment: (projectIds) => api.post('/sync/equipment', projectIds ? { projectIds } : null),
  syncFiles: (projectIds) => api.post('/sync/files', projectIds ? { projectIds } : null),
  syncProject: (id) => api.post(`/sync/project/${id}`),
  rawPreview: (endpoint, projectId) => api.get('/sync/raw-preview', { params: { endpoint, project_id: projectId } }),
  discover: (projectId) => api.get('/sync/discover', { params: { project_id: projectId } }),
}

export default api

// File Storage Analysis
export const fileStorageApi = {
  sync: (projectId) =>
    api.post('/files/sync', null, { params: projectId ? { projectId } : {} }),
  analyze: (projectId) =>
    api.post('/files/analyze', null, { params: projectId ? { projectId } : {} }),
  getReport: (projectId) =>
    api.get('/files/report', { params: projectId ? { projectId } : {} }),
  getDuplicates: (projectId) =>
    api.get('/files/duplicates', { params: projectId ? { projectId } : {} }),
  getLargest: (projectId, limit = 10) =>
    api.get('/files/largest', { params: { ...(projectId ? { projectId } : {}), limit } }),
  exportPdf: (projectId) =>
    api.get('/files/export/pdf', {
      params: projectId ? { projectId } : {},
      responseType: 'blob',
    }),
  downloadJson: (projectId) =>
    api.get('/files/export/json', {
      params: projectId ? { projectId } : {},
      responseType: 'blob',
    }),
  getHeaviestAssets: (projectId, limit = 5) =>
    api.get('/files/heaviest-assets', { params: { ...(projectId ? { projectId } : {}), limit } }),
}
