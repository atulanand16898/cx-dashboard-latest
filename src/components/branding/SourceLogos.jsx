import React from 'react'

export function CxAlloyLogo() {
  return (
    <div className="source-logo source-logo--cxalloy" aria-label="CxAlloy">
      <div className="source-logo__cxalloy-wordmark">
        <span className="source-logo__cxalloy-cx">Cx</span>
        <span className="source-logo__cxalloy-alloy">Alloy</span>
      </div>
      <svg className="source-logo__cxalloy-mark" viewBox="0 0 74 74" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
        <path d="M11 18C27 8 46 11 63 25" stroke="#0B72C9" strokeWidth="10" strokeLinecap="round" />
        <path d="M22 52C39 61 55 57 66 43" stroke="#49A62E" strokeWidth="10" strokeLinecap="round" />
        <path d="M46 18L58 7L63 23" fill="#0B72C9" />
      </svg>
    </div>
  )
}

export function FacilityGridLogo() {
  const dots = Array.from({ length: 15 }, (_, index) => index)
  return (
    <div className="source-logo source-logo--facilitygrid" aria-label="Facility Grid">
      <div className="source-logo__facilitygrid-dotfield" aria-hidden="true">
        {dots.map((dot) => (
          <span
            key={dot}
            className={`source-logo__facilitygrid-dot ${
              [2, 4, 7, 8, 9, 12].includes(dot) ? 'source-logo__facilitygrid-dot--green' : ''
            }`}
          />
        ))}
      </div>
      <div className="source-logo__facilitygrid-text">
        <div className="source-logo__facilitygrid-title">FACILITY GRID</div>
        <div className="source-logo__facilitygrid-tagline">Construct. Validate. Sustain.</div>
      </div>
    </div>
  )
}

export function PrimaveraLogo() {
  return (
    <div className="source-logo source-logo--coming" aria-label="Primavera">
      <div className="source-logo__coming-mark source-logo__coming-mark--orange">P6</div>
      <div className="source-logo__coming-text">
        <div className="source-logo__coming-title">Primavera</div>
        <div className="source-logo__coming-tagline">Oracle project controls</div>
      </div>
    </div>
  )
}

export function AutodeskConstructionCloudLogo() {
  return (
    <div className="source-logo source-logo--coming" aria-label="Autodesk Construction Cloud">
      <div className="source-logo__coming-mark source-logo__coming-mark--red">ACC</div>
      <div className="source-logo__coming-text">
        <div className="source-logo__coming-title">Autodesk Construction Cloud</div>
        <div className="source-logo__coming-tagline">Connected construction platform</div>
      </div>
    </div>
  )
}
