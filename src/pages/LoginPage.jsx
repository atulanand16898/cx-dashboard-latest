import React, { useMemo, useState } from 'react'
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom'
import { ArrowRight, ChevronLeft, Eye, EyeOff, Lock, ShieldCheck, User } from 'lucide-react'
import toast from 'react-hot-toast'
import ModumLogo from '../components/branding/ModumLogo'
import { useAuth } from '../context/AuthContext'
import { getDataSource } from '../config/dataSources'
import { PRIVATE_LOGIN_PATH } from '../config/appRoutes'
import './ModumExperience.css'

export default function LoginPage() {
  const { providerKey } = useParams()
  const selectedSource = useMemo(() => getDataSource(providerKey), [providerKey])
  const { login } = useAuth()
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [loading, setLoading] = useState(false)

  if (!selectedSource) {
    return <Navigate to={PRIVATE_LOGIN_PATH} replace />
  }

  const available = selectedSource.status === 'available'
  const Logo = selectedSource.logo

  const handleSubmit = async (event) => {
    event.preventDefault()

    const cleanUsername = username.trim()
    const cleanPassword = password.trim()

    if (!available) {
      toast.error('This source is not live yet')
      return
    }

    if (!cleanUsername || !cleanPassword) {
      toast.error('Fill in all fields')
      return
    }

    setLoading(true)
    try {
      await login(cleanUsername, cleanPassword, selectedSource.key)
      navigate('/tracker-pulse')
      toast.success('Welcome back!')
    } catch (error) {
      toast.error(error.response?.data?.message || error.message || 'Invalid credentials')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="modum-provider-page">
      <div className="modum-provider-shell">
        <header className="modum-provider-header">
          <Link to={PRIVATE_LOGIN_PATH} className="modum-provider-header__back">
            <ChevronLeft size={15} />
            Back to Sources
          </Link>
          <Link to="/" className="modum-provider-header__home">Home</Link>
        </header>

        <main className="modum-provider-main">
          <section className="modum-provider-brand">
            <ModumLogo label="MODUM" sublabel="Client Access Portal" size="lg" />
            <div className="modum-provider-brand__eyebrow">{selectedSource.eyebrow}</div>
            <h1 className="modum-provider-brand__title">{selectedSource.label}</h1>
            <p className="modum-provider-brand__copy">{selectedSource.description}</p>
            <div className="modum-provider-brand__source">
              <Logo />
            </div>
          </section>

          <section className="modum-provider-panel">
            <div className="modum-provider-panel__eyebrow">
              {available ? 'Sign In' : 'Source Status'}
            </div>
            <h2 className="modum-provider-panel__title">
              {available ? `Enter ${selectedSource.label}` : `${selectedSource.label} is coming soon`}
            </h2>
            <p className="modum-provider-panel__copy">
              {available
                ? 'Use the dedicated credentials for this source. After login, you will be taken straight into the MODUM IQ workspace.'
                : 'This integration is not available yet. Return to the source selection screen or contact us from the landing page for rollout updates.'}
            </p>

            {available ? (
              <form onSubmit={handleSubmit} className="modum-provider-form">
                <label className="modum-provider-form__field">
                  <span className="modum-provider-form__label">Username or Email</span>
                  <div className="modum-provider-form__input-wrap">
                    <User size={15} className="modum-provider-form__icon" />
                    <input
                      type="text"
                      value={username}
                      onChange={(event) => setUsername(event.target.value)}
                      className="modum-provider-form__input"
                      placeholder={selectedSource.usernameHint}
                      autoComplete="username"
                    />
                  </div>
                </label>

                <label className="modum-provider-form__field">
                  <span className="modum-provider-form__label">Password</span>
                  <div className="modum-provider-form__input-wrap">
                    <Lock size={15} className="modum-provider-form__icon" />
                    <input
                      type={showPassword ? 'text' : 'password'}
                      value={password}
                      onChange={(event) => setPassword(event.target.value)}
                      className="modum-provider-form__input modum-provider-form__input--password"
                      placeholder="........"
                      autoComplete="current-password"
                    />
                    <button
                      type="button"
                      onClick={() => setShowPassword((open) => !open)}
                      className="modum-provider-form__toggle"
                      aria-label={showPassword ? 'Hide password' : 'Show password'}
                    >
                      {showPassword ? <EyeOff size={15} /> : <Eye size={15} />}
                    </button>
                  </div>
                </label>

                <div className="modum-provider-form__helper">
                  <div className="modum-provider-form__helper-title">
                    <ShieldCheck size={15} />
                    Source-specific access
                  </div>
                  <div>{selectedSource.helper}</div>
                </div>

                <button type="submit" disabled={loading} className="modum-provider-form__submit">
                  <span>{loading ? 'Signing in...' : `Sign in to ${selectedSource.label}`}</span>
                  {!loading ? <ArrowRight size={16} /> : null}
                </button>
              </form>
            ) : (
              <div className="modum-provider-panel__empty">
                <div className="modum-provider-panel__empty-copy">
                  This workspace has been listed in the portal, but client login is not enabled yet.
                </div>
                <Link to={PRIVATE_LOGIN_PATH} className="modum-provider-panel__link">
                  Return to source selection
                </Link>
              </div>
            )}
          </section>
        </main>
      </div>
    </div>
  )
}
