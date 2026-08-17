import { useState } from 'react'
import { useAuth } from '../hooks/useAuth'
import { useAccounts, useBalanceSummary } from '../hooks/useAccounts'
import { useLoans } from '../hooks/useLoans'
import { AccountRow } from '../components/AccountCard'
import { useNavigate } from 'react-router-dom'
import { useAppStore } from '../store/appStore'
import { pageTitle, sectionTitle, listSection, btnGhostSm } from '../lib/styles'
import { accountTypeLabel, formatMoney } from '../lib/format'
import { Percent, Archive } from 'lucide-react'
import type { AccountResponse } from '../types/api'

// Solid fills for the balance bars below — a step more saturated than AccountCard's icon-chip
// tint, since a thin bar needs more contrast against the canvas to read clearly.
const barColor: Record<AccountResponse['accountType'], string> = {
  CHECKING: 'bg-primary-500',
  SAVINGS: 'bg-success-500',
  FX_TRADING: 'bg-secondary-500',
  BROKERAGE: 'bg-warning-500',
  CRYPTO: 'bg-orange-500',
}

// Grouped by currency and never compared across groups — a bar for a £300 GBP account sitting
// next to a $5,000 USD account's bar would silently imply they're the same kind of number, the
// same mistake the total-balance card avoids by never summing currencies together.
function BalanceByAccount({ accounts }: { accounts: AccountResponse[] }) {
  const byCurrency = new Map<string, AccountResponse[]>()
  for (const account of accounts) {
    byCurrency.set(account.currency, [...(byCurrency.get(account.currency) ?? []), account])
  }

  return (
    <div className="flex flex-col gap-6">
      {Array.from(byCurrency.entries()).map(([currency, group]) => {
        const sorted = [...group].sort((a, b) => b.balance - a.balance)
        const max = Math.max(...sorted.map((a) => a.balance), 1)
        return (
          <div key={currency}>
            {byCurrency.size > 1 && <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-400">{currency}</p>}
            <div className="flex flex-col gap-3">
              {sorted.map((account) => (
                <div key={account.accountId} className="flex items-center gap-3">
                  <span className="w-28 shrink-0 truncate text-xs text-ink-400">{account.nickname ?? accountTypeLabel(account.accountType)}</span>
                  <div className="h-2.5 flex-1 overflow-hidden rounded-full bg-canvas">
                    <div
                      className={`h-full rounded-full transition-all ${barColor[account.accountType]}`}
                      style={{ width: `${Math.max(4, (account.balance / max) * 100)}%` }}
                    />
                  </div>
                  <span className="w-24 shrink-0 text-right font-mono text-xs font-semibold text-ink-900">{formatMoney(account.balance, currency)}</span>
                </div>
              ))}
            </div>
          </div>
        )
      })}
    </div>
  )
}

export function DashboardPage() {
  const user = useAuth()
  const navigate = useNavigate()
  const selectAccount = useAppStore((s) => s.selectAccount)
  const { data: accounts, isLoading: accountsLoading } = useAccounts(user?.clientId)
  const { data: balanceSummary } = useBalanceSummary(user?.clientId)
  const { data: loans } = useLoans(user?.clientId)
  const [showClosed, setShowClosed] = useState(false)

  const activeAccounts = (accounts ?? []).filter((a) => a.status === 'ACTIVE')
  const closedAccounts = (accounts ?? []).filter((a) => a.status === 'CLOSED')
  const activeLoans = loans?.filter((l) => l.status === 'ACTIVE') ?? []
  // LoanResponse doesn't carry its own currency — it's disbursed to an account, so look that up
  // rather than assuming one currency (or worse, mislabeling a loan's real currency).
  const currencyByAccountId = new Map((accounts ?? []).map((a) => [a.accountId, a.currency]))
  const outstandingByCurrency = activeLoans.reduce<Record<string, number>>((totals, loan) => {
    const currency = currencyByAccountId.get(loan.accountId) ?? '?'
    totals[currency] = (totals[currency] ?? 0) + loan.outstandingPrincipal
    return totals
  }, {})
  const firstName = user?.email.split('@')[0]

  function goToAccount(accountId: string) {
    selectAccount(accountId)
    navigate('/accounts')
  }

  return (
    <div className="flex flex-col gap-10">
      <div>
        <h1 className={pageTitle}>Welcome back{firstName ? `, ${firstName}` : ''}</h1>
        <p className="text-sm text-ink-400">Here's where your money is right now.</p>
      </div>

      {accountsLoading && <p className="text-sm text-ink-400">Loading accounts…</p>}

      {balanceSummary && balanceSummary.balances.length > 0 && (
        <div className="rounded-2xl bg-gradient-to-br from-primary-600 via-primary-600 to-secondary-600 p-6 shadow-lg shadow-primary-900/20">
          <p className="text-xs font-semibold uppercase tracking-wide text-white/70">Total balance</p>

          {balanceSummary.balances.length === 1 ? (
            <p className="mt-2 font-mono text-4xl font-bold text-white">{formatMoney(balanceSummary.balances[0].totalBalance, balanceSummary.balances[0].currency)}</p>
          ) : (
            <div className="mt-3 flex flex-wrap gap-3">
              {balanceSummary.balances.map((b) => (
                <div key={b.currency} className="rounded-xl bg-white/15 px-4 py-2.5 backdrop-blur-sm">
                  <p className="text-[11px] font-semibold uppercase tracking-wide text-white/70">{b.currency}</p>
                  <p className="font-mono text-lg font-bold text-white">{formatMoney(b.totalBalance, b.currency)}</p>
                </div>
              ))}
            </div>
          )}

          <p className="mt-3 text-xs text-white/70">
            Across {balanceSummary.activeAccountCount} active account{balanceSummary.activeAccountCount === 1 ? '' : 's'}
            {balanceSummary.balances.length > 1 ? ' — currencies shown separately, not converted or added together' : ''}.
          </p>
        </div>
      )}

      {accounts && accounts.length > 0 && (
        <div>
          <h2 className={sectionTitle}>Your accounts</h2>
          <div className={listSection}>
            {activeAccounts.map((account) => (
              <AccountRow key={account.accountId} account={account} onClick={() => goToAccount(account.accountId)} />
            ))}
          </div>

          {closedAccounts.length > 0 && (
            <div className="mt-3">
              <button type="button" onClick={() => setShowClosed((v) => !v)} className={btnGhostSm}>
                <Archive size={13} strokeWidth={2} />
                {showClosed ? 'Hide' : 'Show'} closed accounts ({closedAccounts.length})
              </button>
              {showClosed && (
                <div className={`mt-2 ${listSection}`}>
                  {closedAccounts.map((account) => (
                    <AccountRow key={account.accountId} account={account} onClick={() => goToAccount(account.accountId)} />
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {accounts && accounts.length === 0 && (
        <p className="text-sm text-ink-400">You don't have any accounts yet — open one from the Accounts page.</p>
      )}

      {activeLoans.length > 0 && (
        <div>
          <h2 className={sectionTitle}>Active loans</h2>
          <div className={`${listSection} mt-1`}>
            <div className="flex items-center gap-4 py-4">
              <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-secondary-50 text-secondary-600 dark:bg-secondary-500/15 dark:text-secondary-400">
                <Percent size={18} strokeWidth={2} />
              </span>
              <p className="text-sm text-ink-700">
                {activeLoans.length} active loan{activeLoans.length === 1 ? '' : 's'}, totaling{' '}
                <span className="font-mono font-semibold text-ink-900">
                  {Object.entries(outstandingByCurrency)
                    .map(([currency, total]) => formatMoney(total, currency))
                    .join(' + ')}
                </span>{' '}
                outstanding principal.
              </p>
            </div>
          </div>
        </div>
      )}

      {activeAccounts.length > 1 && (
        <div>
          <h2 className={sectionTitle}>Balance by account</h2>
          <div className="mt-3">
            <BalanceByAccount accounts={activeAccounts} />
          </div>
        </div>
      )}
    </div>
  )
}
