import React from 'react'
import { Link } from 'react-router-dom'
import { ArrowRight } from 'lucide-react'
import ModumLogo from '../components/branding/ModumLogo'
import { DATA_SOURCES } from '../config/dataSources'
import { providerLoginPath } from '../config/appRoutes'
import './ModumExperience.css'

export default function ClientAccessPortalPage() {
  return (
    <div className="modum-portal-page">
      <div className="modum-portal-shell">
        <header className="modum-portal-header">
          <Link to="/" className="modum-portal-header__brand">
            <ModumLogo label="MODUM" sublabel="Client Access Portal" size="md" />
          </Link>
          <Link to="/" className="modum-portal-header__back">Back to Home</Link>
        </header>

        <main className="modum-portal-main">
          <div className="modum-portal-copy">
            <div className="modum-portal-copy__eyebrow">Client Access Portal</div>
            <h1 className="modum-portal-copy__title">Choose a source.</h1>
            <p className="modum-portal-copy__subtitle">
              Select the workspace you want to enter. Once selected, we will take you to the dedicated login page for that source.
            </p>
          </div>

          <div className="modum-portal-grid">
            {DATA_SOURCES.map((source) => {
              const Logo = source.logo
              const available = source.status === 'available'

              return (
                <Link
                  key={source.key}
                  to={providerLoginPath(source.key)}
                  className={`modum-portal-card ${available ? 'modum-portal-card--available' : 'modum-portal-card--locked'}`}
                >
                  <div className="modum-portal-card__status">
                    <span>{available ? 'Available now' : 'Coming soon'}</span>
                  </div>
                  <div className="modum-portal-card__logo">
                    <Logo />
                  </div>
                  <div className="modum-portal-card__eyebrow">{source.eyebrow}</div>
                  <div className="modum-portal-card__title">{source.label}</div>
                  <div className="modum-portal-card__copy">{source.description}</div>
                  <div className="modum-portal-card__footer">
                    <span>{available ? 'Continue to login' : 'View source'}</span>
                    <ArrowRight size={15} />
                  </div>
                </Link>
              )
            })}
          </div>
        </main>
      </div>
    </div>
  )
}
