import { useState } from 'react'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { useAuth } from '../hooks/useAuth'
import { useAccounts, useBalanceSummary } from '../hooks/useAccounts'
import { useLoans } from '../hooks/useLoans'
import { AccountRow } from '../components/AccountCard'
import { useNavigate } from 'react-router-dom'
import { useAppStore } from '../store/appStore'
import { pageTitle, sectionTitle, listSection, btnGhostSm } from '../lib/styles'
import { formatMoney } from '../lib/format'
import { Percent, Archive } from 'lucide-react'

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
  const chartData = activeAccounts.map((a) => ({
    name: a.nickname ?? `${a.accountType} (${a.currency})`,
    balance: a.balance,
  }))
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

      {chartData.length > 0 && (
        <div>
          <h2 className={sectionTitle}>Balance by account</h2>
          <ResponsiveContainer width="100%" height={240}>
            <BarChart data={chartData}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--color-ink-100)" vertical={false} />
              <XAxis dataKey="name" tick={{ fontSize: 12, fill: 'var(--color-ink-400)' }} axisLine={false} tickLine={false} />
              <YAxis tick={{ fontSize: 12, fill: 'var(--color-ink-400)' }} axisLine={false} tickLine={false} />
              <Tooltip cursor={{ fill: 'var(--color-canvas)' }} contentStyle={{ borderRadius: 8, border: '1px solid var(--color-ink-100)' }} />
              <Bar dataKey="balance" fill="#0284c7" radius={[6, 6, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  )
}
