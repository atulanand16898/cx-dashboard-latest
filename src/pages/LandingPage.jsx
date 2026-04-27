import React, { useState } from 'react'
import { Link } from 'react-router-dom'
import { X } from 'lucide-react'
import toast from 'react-hot-toast'
import ModumLogo from '../components/branding/ModumLogo'
import ServerRoomBackdrop from '../components/branding/ServerRoomBackdrop'
import { PRIVATE_LOGIN_PATH } from '../config/appRoutes'
import { authApi } from '../services/api'
import './ModumExperience.css'

const EMPTY_LEAD_FORM = {
  fullName: '',
  email: '',
  phone: '',
  company: '',
}

export default function LandingPage() {
  const [leadModalOpen, setLeadModalOpen] = useState(false)
  const [leadSubmitting, setLeadSubmitting] = useState(false)
  const [leadSubmitted, setLeadSubmitted] = useState(false)
  const [leadForm, setLeadForm] = useState(EMPTY_LEAD_FORM)

  const openLeadModal = () => {
    setLeadSubmitted(false)
    setLeadModalOpen(true)
  }

  const closeLeadModal = () => {
    if (leadSubmitting) {
      return
    }
    setLeadModalOpen(false)
  }

  const handleLeadChange = (field) => (event) => {
    setLeadForm((current) => ({
      ...current,
      [field]: event.target.value,
    }))
  }

  const handleLeadSubmit = async (event) => {
    event.preventDefault()

    if (!leadForm.fullName.trim() || !leadForm.email.trim() || !leadForm.phone.trim()) {
      toast.error('Enter your name, email, and phone number')
      return
    }

    setLeadSubmitting(true)
    try {
      const response = await authApi.captureLead({
        ...leadForm,
        source: 'landing-contact-modal',
      })
      setLeadForm(EMPTY_LEAD_FORM)
      setLeadSubmitted(true)
      toast.success(
        response?.data?.data?.notificationSent
          ? 'Thanks. We will be in touch shortly.'
          : 'Thanks. Your request has been captured.'
      )
    } catch (error) {
      toast.error(error.response?.data?.message || 'Could not submit your details')
    } finally {
      setLeadSubmitting(false)
    }
  }

  return (
    <div className="modum-public-page">
      <header className="modum-public-nav">
        <Link to="/" className="modum-public-nav__brand">
          <ModumLogo label="MODUM" sublabel="Data-Driven Delivery" size="md" />
        </Link>

        <div className="modum-public-nav__links">
          <button type="button" className="modum-public-nav__link modum-public-nav__link--button" onClick={openLeadModal}>
            Contact Us
          </button>
          <Link to={PRIVATE_LOGIN_PATH} className="modum-public-nav__link">
            Login
          </Link>
        </div>
      </header>

      <ServerRoomBackdrop className="modum-public-hero">
        <div className="modum-public-hero__scrim" />
        <div className="modum-public-hero__content">
          <div className="modum-public-hero__panel">
            <div className="modum-public-hero__brand">
              <ModumLogo
                label="MODUM"
                sublabel="Data-Driven Delivery"
                size="hero"
                layout="stack"
              />
            </div>

            <h1 className="modum-public-hero__title">
              <span className="modum-public-hero__title-line">Consultency Services</span>
              <span className="modum-public-hero__title-line modum-public-hero__title-line--accent">
                Powered by AI &amp; Data
              </span>
            </h1>

            <p className="modum-public-hero__subtitle">
              Built for mission critical delivery, technical oversight, and connected regional support.
            </p>
          </div>
        </div>
      </ServerRoomBackdrop>

      {leadModalOpen ? (
        <div className="modum-public-modal-backdrop" onClick={closeLeadModal}>
          <div className="modum-public-modal modum-public-modal--compact" onClick={(event) => event.stopPropagation()}>
            <button
              type="button"
              className="modum-public-modal__close"
              onClick={closeLeadModal}
              aria-label="Close contact form"
            >
              <X size={18} />
            </button>

            <div className="modum-public-modal__eyebrow">Contact Us</div>
            {leadSubmitted ? (
              <div className="modum-public-modal__success">
                <h2 className="modum-public-modal__title">Thanks. Your message has been received.</h2>
                <p className="modum-public-modal__copy">
                  We&apos;ll review your details and come back to you shortly.
                </p>
                <div className="modum-public-modal__actions">
                  <button
                    type="button"
                    className="modum-public-button modum-public-modal__submit"
                    onClick={closeLeadModal}
                  >
                    Close
                  </button>
                </div>
              </div>
            ) : (
              <>
                <h2 className="modum-public-modal__title">Let&apos;s talk.</h2>
                <p className="modum-public-modal__copy">
                  Share your details and we&apos;ll get in touch about consultancy support, delivery oversight, or digital workflow enablement.
                </p>

                <form className="modum-public-modal__form" onSubmit={handleLeadSubmit}>
                  <div className="modum-public-modal__grid modum-public-modal__grid--compact">
                    <label className="modum-public-modal__field">
                      <span className="modum-public-modal__label">Full Name</span>
                      <input
                        type="text"
                        value={leadForm.fullName}
                        onChange={handleLeadChange('fullName')}
                        className="modum-public-modal__input"
                        placeholder="Your full name"
                        autoComplete="name"
                      />
                    </label>

                    <label className="modum-public-modal__field">
                      <span className="modum-public-modal__label">Email Address</span>
                      <input
                        type="email"
                        value={leadForm.email}
                        onChange={handleLeadChange('email')}
                        className="modum-public-modal__input"
                        placeholder="name@company.com"
                        autoComplete="email"
                      />
                    </label>

                    <label className="modum-public-modal__field">
                      <span className="modum-public-modal__label">Phone Number</span>
                      <input
                        type="tel"
                        value={leadForm.phone}
                        onChange={handleLeadChange('phone')}
                        className="modum-public-modal__input"
                        placeholder="+971..."
                        autoComplete="tel"
                      />
                    </label>

                    <label className="modum-public-modal__field">
                      <span className="modum-public-modal__label">Company</span>
                      <input
                        type="text"
                        value={leadForm.company}
                        onChange={handleLeadChange('company')}
                        className="modum-public-modal__input"
                        placeholder="Optional"
                        autoComplete="organization"
                      />
                    </label>
                  </div>

                  <div className="modum-public-modal__actions">
                    <button
                      type="button"
                      className="modum-public-modal__secondary"
                      onClick={closeLeadModal}
                      disabled={leadSubmitting}
                    >
                      Cancel
                    </button>
                    <button type="submit" className="modum-public-button modum-public-modal__submit" disabled={leadSubmitting}>
                      {leadSubmitting ? 'Submitting...' : 'Send'}
                    </button>
                  </div>
                </form>
              </>
            )}
          </div>
        </div>
      ) : null}
    </div>
  )
}
