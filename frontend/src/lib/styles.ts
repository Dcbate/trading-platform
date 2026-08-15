// Shared class strings so every card/button/input across the app looks like it belongs to the
// same product instead of each page inventing its own spacing/radius/shadow. Kept deliberately
// flat — a border and a background, no drop shadows — per the "real bank, not a template" brief:
// cards are for the few things that actually deserve visual weight (the total-balance hero,
// warnings), not the default container for every list and form.
export const card = 'rounded-xl border border-ink-100 bg-surface p-5'
export const cardTight = 'rounded-xl border border-ink-100 bg-surface p-4'

export const btnPrimary =
  'inline-flex items-center justify-center gap-1.5 rounded-lg bg-primary-600 px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-50'
export const btnSuccess =
  'inline-flex items-center justify-center gap-1.5 rounded-lg bg-success-600 px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-success-700 disabled:cursor-not-allowed disabled:opacity-50'
export const btnDanger =
  'inline-flex items-center justify-center gap-1.5 rounded-lg bg-error-600 px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-error-700 disabled:cursor-not-allowed disabled:opacity-50'
export const btnGhost =
  'inline-flex items-center justify-center gap-1.5 rounded-lg border border-ink-100 bg-surface px-4 py-2.5 text-sm font-semibold text-ink-700 transition hover:bg-canvas disabled:cursor-not-allowed disabled:opacity-50'
export const btnGhostSm =
  'inline-flex items-center justify-center gap-1 rounded-md border border-ink-100 bg-surface px-2.5 py-1.5 text-xs font-semibold text-ink-700 transition hover:bg-canvas disabled:cursor-not-allowed disabled:opacity-50'
export const btnSuccessSm =
  'inline-flex items-center justify-center gap-1 rounded-md bg-success-600 px-2.5 py-1.5 text-xs font-semibold text-white transition hover:bg-success-700 disabled:cursor-not-allowed disabled:opacity-50'
export const btnDangerSm =
  'inline-flex items-center justify-center gap-1 rounded-md bg-error-600 px-2.5 py-1.5 text-xs font-semibold text-white transition hover:bg-error-700 disabled:cursor-not-allowed disabled:opacity-50'

export const input =
  'rounded-lg border border-ink-100 bg-surface px-3.5 py-2.5 text-sm text-ink-900 outline-none transition placeholder:text-ink-400 focus:border-primary-400 focus:ring-2 focus:ring-primary-100'
export const inputSm =
  'rounded-md border border-ink-100 bg-surface px-2.5 py-1.5 text-xs text-ink-900 outline-none transition placeholder:text-ink-400 focus:border-primary-400 focus:ring-2 focus:ring-primary-100'
export const label = 'text-xs font-semibold uppercase tracking-wide text-ink-400'

// List-row layout: the default for accounts/loans/transactions now — text and dividers, not a
// card per item. Use `card` only for something that should stand out (totals, warnings).
export const listSection = 'flex flex-col divide-y divide-ink-100'
export const listRow = 'flex flex-wrap items-center justify-between gap-3 py-4'
export const sectionTitle = 'text-base font-semibold text-ink-900'
export const pageTitle = 'text-[28px] font-semibold text-ink-900'
