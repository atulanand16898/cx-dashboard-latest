import React from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { Toaster } from 'react-hot-toast'
import { AuthProvider } from './context/AuthContext'
import { ThemeProvider } from './context/ThemeContext'
import { ProjectProvider } from './context/ProjectContext'
import ProtectedRoute from './components/ProtectedRoute'
import AppLayout from './components/layout/AppLayout'
import LoginPage from './pages/LoginPage'
import DashboardPage from './pages/DashboardPage'
import IssuesPage from './pages/IssuesPage'
import SyncPage from './pages/SyncPage'
import {
  TasksPage,
  ChecklistsPage,
  AssetsPage,
  EquipmentPage,
  PersonsPage,
  CompaniesPage,
  RolesPage,
} from './pages/EntityPages'
import FileStoragePage from './pages/FileStoragePage'
import ProjectFilesPage from './pages/ProjectFilesPage'
import PlannedVsActualPage from './pages/PlannedVsActualPage'
import TrackerPulsePage from './pages/TrackerPulsePage'
import IssueRadarPage from './pages/IssueRadarPage'
import AICopilotPage from './pages/AICopilotPage'
import AssetReadinessPage from './pages/AssetReadinessPage'
import ReportsPage from './pages/ReportsPage'
import ProjectAccessPage from './pages/ProjectAccessPage'

export default function App() {
  return (
    <BrowserRouter>
      <ThemeProvider>
      <AuthProvider>
        <ProjectProvider>
          <Toaster
            position="top-right"
            toastOptions={{
              style: {
                background: '#1e293b',
                color: '#f8fafc',
                border: '1px solid rgba(255,255,255,0.08)',
                borderRadius: '12px',
                fontSize: '13px',
                fontFamily: 'Sora, sans-serif',
                fontWeight: '500',
              },
              success: {
                iconTheme: { primary: '#22c55e', secondary: '#1e293b' },
              },
              error: {
                iconTheme: { primary: '#ef4444', secondary: '#1e293b' },
              },
            }}
          />

          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route
              path="/"
              element={
                <ProtectedRoute>
                  <AppLayout />
                </ProtectedRoute>
              }
            >
              <Route index element={<Navigate to="/tracker-pulse" replace />} />
              <Route path="dashboard" element={<Navigate to="/tracker-pulse" replace />} />
              <Route path="issues" element={<IssuesPage />} />
              <Route path="tasks" element={<TasksPage />} />
              <Route path="checklists" element={<ChecklistsPage />} />
              <Route path="equipment" element={<EquipmentPage />} />
              <Route path="files" element={<ProjectFilesPage />} />
              <Route path="assets" element={<AssetsPage />} />
              <Route path="persons" element={<PersonsPage />} />
              <Route path="companies" element={<CompaniesPage />} />
              <Route path="roles" element={<RolesPage />} />
              <Route path="sync" element={<SyncPage />} />
              <Route path="file-storage" element={<FileStoragePage />} />
              <Route path="planned-vs-actual" element={<PlannedVsActualPage />} />
              <Route path="tracker-pulse" element={<TrackerPulsePage />} />
              <Route path="checklist-flow" element={<Navigate to="/asset-readiness" replace />} />
              <Route path="issue-radar" element={<IssueRadarPage />} />
              <Route path="ai-copilot" element={<AICopilotPage />} />
              <Route path="asset-readiness" element={<AssetReadinessPage />} />
              <Route path="reports" element={<ReportsPage />} />
              <Route path="tracker-briefs" element={<ReportsPage />} />
              <Route path="project-access" element={<ProjectAccessPage />} />
            </Route>

            <Route path="*" element={<Navigate to="/tracker-pulse" replace />} />
          </Routes>
        </ProjectProvider>
      </AuthProvider>
      </ThemeProvider>
    </BrowserRouter>
  )
}
