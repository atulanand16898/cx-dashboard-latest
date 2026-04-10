import React, { createContext, useContext, useState, useEffect } from 'react'
import { authApi } from '../services/api'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null)
  const [loading, setLoading] = useState(true)
  const [provider, setProvider] = useState(null)

  const refreshUser = async () => {
    const meRes = await authApi.me()
    const nextUser = meRes.data.data
    setUser(nextUser)
    if (nextUser?.provider) {
      setProvider(nextUser.provider)
    } else {
      setProvider(null)
    }
    return nextUser
  }

  useEffect(() => {
    const token = localStorage.getItem('access_token')
    if (token) {
      refreshUser()
        .catch(() => { localStorage.clear(); setUser(null); setProvider(null) })
        .finally(() => setLoading(false))
    } else {
      setProvider(null)
      setLoading(false)
    }
  }, [])

  const login = async (username, password, selectedProvider) => {
    localStorage.removeItem('access_token')
    localStorage.removeItem('refresh_token')
    setUser(null)
    setProvider(selectedProvider)

    const res = await authApi.login({
      username: username.trim(),
      password: password.trim(),
      provider: selectedProvider,
    })
    const { accessToken, refreshToken, provider: authenticatedProvider } = res.data.data
    localStorage.setItem('access_token', accessToken)
    localStorage.setItem('refresh_token', refreshToken)
    if (authenticatedProvider) {
      setProvider(authenticatedProvider)
    }
    await refreshUser()
  }

  const logout = async () => {
    const refreshToken = localStorage.getItem('refresh_token')
    try { await authApi.logout(refreshToken) } catch {}
    localStorage.clear()
    setProvider(null)
    setUser(null)
  }

  return (
    <AuthContext.Provider value={{
      user,
      login,
      logout,
      refreshUser,
      provider,
      setProvider,
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
