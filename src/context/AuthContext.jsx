import React, { createContext, useContext, useState, useEffect } from 'react'
import { authApi } from '../services/api'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [loading, setLoading] = useState(true)

  const refreshUser = async () => {
    const meRes = await authApi.me()
    setUser(meRes.data.data)
    return meRes.data.data
  }

  useEffect(() => {
    const token = localStorage.getItem('access_token')
    if (token) {
      refreshUser()
        .catch(() => { localStorage.clear(); setUser(null) })
        .finally(() => setLoading(false))
    } else {
      setLoading(false)
    }
  }, [])

  const login = async (username, password) => {
    localStorage.removeItem('access_token')
    localStorage.removeItem('refresh_token')

    const res = await authApi.login({
      username: username.trim(),
      password: password.trim(),
    })
    const { accessToken, refreshToken } = res.data.data
    localStorage.setItem('access_token', accessToken)
    localStorage.setItem('refresh_token', refreshToken)
    await refreshUser()
  }

  const logout = async () => {
    const refreshToken = localStorage.getItem('refresh_token')
    try { await authApi.logout(refreshToken) } catch {}
    localStorage.clear()
    setUser(null)
  }

  return (
    <AuthContext.Provider value={{
      user,
      login,
      logout,
      refreshUser,
      loading,
      isAuthenticated: !!user,
      isAdmin: !!user?.isAdmin,
      accessibleProjectIds: user?.accessibleProjectIds || [],
    }}>
      {children}
    </AuthContext.Provider>
  )
}

export const useAuth = () => useContext(AuthContext)
