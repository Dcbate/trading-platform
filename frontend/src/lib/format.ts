import type { AccountType, StatementEntryType } from '../types/api'

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

const STATEMENT_ENTRY_TYPE_LABELS: Record<StatementEntryType, string> = {
  FX_ORDER: 'FX order',
  PAYMENT: 'Payment',
  TRANSFER_OUT: 'Transfer sent',
  TRANSFER_IN: 'Transfer received',
  DEPOSIT: 'Deposit',
  WITHDRAWAL: 'Withdrawal',
  CONVERSION: 'Conversion',
  ACCOUNT_CLOSURE: 'Account closed',
  LOAN_ORIGINATED: 'Loan originated',
  LOAN_REPAYMENT: 'Loan repayment',
}

export function statementEntryTypeLabel(type: StatementEntryType): string {
  return STATEMENT_ENTRY_TYPE_LABELS[type]
}

// No currency on an entry (FX orders, payments, transfers — see BankStatementEntry's javadoc for
// why) means there's no single account balance being moved in a currency-safe way to format
// against, so this shows a bare signed number rather than guessing a currency symbol.
export function formatSignedAmount(amount: number, currency: string | null): string {
  if (currency) {
    return formatMoney(amount, currency)
  }
  const sign = amount > 0 ? '+' : ''
  return sign + new Intl.NumberFormat('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount)
}

// Real currency symbols via the platform Intl API — a EUR or GBP balance was previously shown
// with a hardcoded "$", which is simply wrong for any non-USD account.
export function formatMoney(amount: number, currency: string): string {
  return new Intl.NumberFormat('en-GB', { style: 'currency', currency }).format(amount)
}

// Share/unit quantities come off the backend as BigDecimal with up to 8 decimal places, which
// can carry tiny precision noise (e.g. 2.00000002) that isn't a real fractional share — round to
// 4 decimals and drop trailing zeros so a whole-share buy just reads "2", not "2.00000002".
export function formatQuantity(quantity: number): string {
  return (Math.round(quantity * 10000) / 10000).toString()
}

// Keys a whole-shares input to digits only, typed live. Stripping "." and keeping every digit
// (a naive [^0-9] replace) turns "2.5" into "25", not "2" — silently 10x-ing the value instead of
// rejecting the fraction. Truncating at the first "." before stripping avoids that.
export function sanitizeWholeNumberInput(value: string): string {
  return value.split('.')[0].replace(/[^0-9]/g, '')
}

// mm:ss for Game Mode's countdown — never negative, a session that's just ended still needs to render "0:00", not "-0:03".
export function formatCountdown(totalSeconds: number): string {
  const seconds = Math.max(0, Math.round(totalSeconds))
  const minutes = Math.floor(seconds / 60)
  const remainingSeconds = seconds % 60
  return `${minutes}:${remainingSeconds.toString().padStart(2, '0')}`
}
