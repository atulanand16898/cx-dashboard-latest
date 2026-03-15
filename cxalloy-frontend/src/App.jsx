import React from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { Toaster } from 'react-hot-toast'
import { AuthProvider } from './context/AuthContext'
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
  PersonsPage,
  CompaniesPage,
  RolesPage,
} from './pages/EntityPages'
import FileStorageAnalysisPage from './pages/FileStorageAnalysisPage'

export default function App() {
  return (
    <BrowserRouter>
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
              <Route index element={<Navigate to="/dashboard" replace />} />
              <Route path="dashboard" element={<DashboardPage />} />
              <Route path="issues" element={<IssuesPage />} />
              <Route path="tasks" element={<TasksPage />} />
              <Route path="checklists" element={<ChecklistsPage />} />
              <Route path="assets" element={<AssetsPage />} />
              <Route path="persons" element={<PersonsPage />} />
              <Route path="companies" element={<CompaniesPage />} />
              <Route path="roles" element={<RolesPage />} />
              <Route path="sync" element={<SyncPage />} />
              <Route path="file-storage" element={<FileStorageAnalysisPage />} />
            </Route>

            <Route path="*" element={<Navigate to="/dashboard" replace />} />
          </Routes>
        </ProjectProvider>
      </AuthProvider>
    </BrowserRouter>
  )
}
