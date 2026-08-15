import type { AccountType } from '../types/api'

// UK banking vocabulary — a "checking account" and a "brokerage account" are American terms;
// the underlying AccountType enum values stay as-is (API contract, DB rows), this only changes
// what a client reads on screen.
const ACCOUNT_TYPE_LABELS: Record<AccountType, string> = {
  CHECKING: 'Current',
  SAVINGS: 'Savings',
  FX_TRADING: 'FX Trading',
  BROKERAGE: 'Stocks & Shares',
}

export function accountTypeLabel(type: AccountType): string {
  return ACCOUNT_TYPE_LABELS[type]
}

// Real currency symbols via the platform Intl API — a EUR or GBP balance was previously shown
// with a hardcoded "$", which is simply wrong for any non-USD account.
export function formatMoney(amount: number, currency: string): string {
  return new Intl.NumberFormat('en-GB', { style: 'currency', currency }).format(amount)
}

export function formatNumber(amount: number): string {
  return new Intl.NumberFormat('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount)
}

// mm:ss for Game Mode's countdown — never negative, a session that's just ended still needs to render "0:00", not "-0:03".
export function formatCountdown(totalSeconds: number): string {
  const seconds = Math.max(0, Math.round(totalSeconds))
  const minutes = Math.floor(seconds / 60)
  const remainingSeconds = seconds % 60
  return `${minutes}:${remainingSeconds.toString().padStart(2, '0')}`
}
