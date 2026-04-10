import React, { useId } from 'react'

export function ModumMark({ size = 40 }) {
  const clipId = useId()

  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 64 64"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <defs>
        <clipPath id={clipId}>
          <circle cx="32" cy="32" r="30" />
        </clipPath>
      </defs>

      <circle cx="32" cy="32" r="30" fill="#03040A" />

      <g clipPath={`url(#${clipId})`} fill="none" strokeLinecap="round">
        <path d="M-6 14C12 3 24 4 36 18" stroke="#6F86FF" strokeWidth="7.5" />
        <path d="M-6 28C12 17 24 18 36 32" stroke="#7A8EFF" strokeWidth="7.5" />
        <path d="M-6 42C12 31 24 32 36 46" stroke="#8A95FF" strokeWidth="7.5" />

        <path d="M28 5C39 17 49 24 70 32" stroke="#8B79FF" strokeWidth="7.5" />
        <path d="M22 19C33 31 43 38 64 46" stroke="#9B72FF" strokeWidth="7.5" />
        <path d="M16 33C27 45 37 52 58 60" stroke="#B163F5" strokeWidth="7.5" />
      </g>
    </svg>
  )
}

export default function ModumLogo({
  label = 'MODUM',
  sublabel = '',
  size = 'md',
  className = '',
  layout = 'row',
}) {
  const scale = {
    sm: { mark: 34, title: 18, subtitle: 10, gap: 10 },
    md: { mark: 44, title: 22, subtitle: 11, gap: 12 },
    lg: { mark: 58, title: 30, subtitle: 12, gap: 14 },
    hero: { mark: 138, title: 58, subtitle: 16, gap: 22 },
  }[size] || { mark: 44, title: 22, subtitle: 11, gap: 12 }

  const stacked = layout === 'stack'
  const hasIqSuffix = label.endsWith(' IQ')
  const baseLabel = hasIqSuffix ? label.slice(0, -3) : label

  return (
    <div
      className={`flex min-w-0 ${stacked ? 'flex-col items-center text-center' : 'items-center'} ${className}`.trim()}
      style={{ gap: scale.gap }}
    >
      <div
        style={{
          borderRadius: '999px',
          boxShadow: size === 'hero'
            ? '0 26px 60px rgba(90, 99, 220, 0.3)'
            : '0 12px 26px rgba(60, 70, 160, 0.18)',
        }}
      >
        <ModumMark size={scale.mark} />
      </div>

      <div className="min-w-0">
        <div
          className="truncate font-800 tracking-[0.08em] text-white"
          style={{
            fontSize: scale.title,
            lineHeight: 1,
            letterSpacing: hasIqSuffix ? '0.08em' : '0.06em',
          }}
        >
          {baseLabel}
          {hasIqSuffix ? (
            <span
              style={{
                background: 'linear-gradient(135deg, #6d84ff 0%, #b163f5 100%)',
                WebkitBackgroundClip: 'text',
                WebkitTextFillColor: 'transparent',
                marginLeft: 4,
              }}
            >
              IQ
            </span>
          ) : null}
        </div>

        {sublabel ? (
          <div
            className={`${stacked ? 'mx-auto' : ''} truncate text-slate-400`}
            style={{
              fontSize: scale.subtitle,
              lineHeight: 1.3,
              marginTop: size === 'hero' ? 10 : 4,
              maxWidth: stacked ? 420 : 'none',
            }}
          >
            {sublabel}
          </div>
        ) : null}
      </div>
    </div>
  )
}
