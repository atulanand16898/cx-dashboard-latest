import React from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { Activity } from 'lucide-react'
import { PRIVATE_LOGIN_PATH } from '../config/appRoutes'

export default function ProtectedRoute({ children }) {
  const { isAuthenticated, loading } = useAuth()

  if (loading) {
    return (
      <div className="min-h-screen bg-dark-950 flex items-center justify-center">
        <div className="flex flex-col items-center gap-3">
          <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-sky-400 to-blue-600 flex items-center justify-center shadow-xl shadow-sky-500/30 animate-pulse-slow">
            <Activity size={22} className="text-white" />
          </div>
          <div className="text-sm text-dark-400">Loading...</div>
        </div>
      </div>
    )
  }

  return isAuthenticated ? children : <Navigate to={PRIVATE_LOGIN_PATH} replace />
}
