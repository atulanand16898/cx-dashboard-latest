import React from 'react'
import modumBrandBoard from '../../assets/modum-brand-board.jpeg'

export default function ModumBrand({
  variant = 'hero',
  eyebrow = 'Field Decisions. Powered by Data.',
  title = 'MODUM IQ',
  subtitle = 'Data-Driven Delivery Decisions',
}) {
  const compact = variant === 'compact'

  if (compact) {
    return (
      <div className="flex min-w-0 items-center gap-4">
        <div className="h-14 w-[88px] flex-shrink-0 overflow-hidden rounded-2xl border border-white/10 bg-[#0b081a] p-1 shadow-[0_18px_32px_rgba(73,56,180,0.28)]">
          <img
            src={modumBrandBoard}
            alt="MODUM brand board"
            className="h-full w-full rounded-xl object-cover object-center"
          />
        </div>
        <div className="min-w-0">
          <div className="truncate text-[10px] font-700 uppercase tracking-[0.24em] text-violet-300/75">
            {eyebrow}
          </div>
          <div className="truncate text-lg font-800 tracking-[0.03em] text-white">{title}</div>
          <div className="truncate text-[11px] text-slate-400">{subtitle}</div>
        </div>
      </div>
    )
  }

  return (
    <div className="space-y-5">
      <div className="overflow-hidden rounded-[28px] border border-white/10 bg-[linear-gradient(135deg,rgba(26,12,61,0.95),rgba(7,10,22,0.92))] p-2 shadow-[0_30px_60px_rgba(35,15,86,0.45)]">
        <img
          src={modumBrandBoard}
          alt="MODUM brand board"
          className="w-full rounded-[22px] object-cover object-center"
        />
      </div>
      <div>
        <div className="text-[11px] font-700 uppercase tracking-[0.28em] text-violet-300/80">
          {eyebrow}
        </div>
        <h1 className="mt-3 text-3xl font-800 tracking-tight text-white sm:text-[2.3rem]">
          {title}
        </h1>
        <p className="mt-2 max-w-xl text-sm leading-6 text-slate-300">{subtitle}</p>
      </div>
    </div>
  )
}
