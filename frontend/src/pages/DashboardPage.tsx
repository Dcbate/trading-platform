import { useState } from 'react'
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { useAuth } from '../hooks/useAuth'
import { useAccounts } from '../hooks/useAccounts'
import { useLoans } from '../hooks/useLoans'
import { AccountCard } from '../components/AccountCard'
import { useNavigate } from 'react-router-dom'
import { useAppStore } from '../store/appStore'
import { card, btnGhostSm } from '../lib/styles'
import { Percent, Archive } from 'lucide-react'

export function DashboardPage() {
  const user = useAuth()
  const navigate = useNavigate()
  const selectAccount = useAppStore((s) => s.selectAccount)
  const { data: accounts, isLoading: accountsLoading } = useAccounts(user?.clientId)
  const { data: loans } = useLoans(user?.clientId)
  const [showClosed, setShowClosed] = useState(false)

  const activeAccounts = (accounts ?? []).filter((a) => a.status === 'ACTIVE')
  const closedAccounts = (accounts ?? []).filter((a) => a.status === 'CLOSED')
  const activeLoans = loans?.filter((l) => l.status === 'ACTIVE') ?? []
  const chartData = activeAccounts.map((a) => ({
    name: a.nickname ?? `${a.accountType} (${a.currency})`,
    balance: a.balance,
  }))
  const firstName = user?.email.split('@')[0]

  const totalsByCurrency = activeAccounts.reduce<Record<string, number>>((totals, a) => {
    totals[a.currency] = (totals[a.currency] ?? 0) + a.balance
    return totals
  }, {})
  const currencyTotals = Object.entries(totalsByCurrency)

  return (
    <div className="flex flex-col gap-8">
      <div>
        <h1 className="text-2xl font-semibold text-ink-900">Welcome back{firstName ? `, ${firstName}` : ''}</h1>
        <p className="text-sm text-ink-400">Here's where your money is right now.</p>
      </div>

      {accountsLoading && <p className="text-sm text-ink-400">Loading accounts…</p>}

      {currencyTotals.length > 0 && (
        <div className={`${card} bg-ink-950`}>
          <p className="text-xs font-semibold uppercase tracking-wide text-ink-400">Total balance</p>
          <div className="mt-2 flex flex-wrap items-baseline gap-x-6 gap-y-1">
            {currencyTotals.map(([currency, total]) => (
              <p key={currency} className="font-mono text-3xl font-bold text-white">
                {total.toFixed(2)} <span className="text-lg font-semibold text-ink-400">{currency}</span>
              </p>
            ))}
          </div>
          <p className="mt-1 text-xs text-ink-400">
            Across {activeAccounts.length} active account{activeAccounts.length === 1 ? '' : 's'}
            {currencyTotals.length > 1 ? ' — shown per currency, not converted' : ''}.
          </p>
        </div>
      )}

      {accounts && accounts.length > 0 && (
        <>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {activeAccounts.map((account) => (
              <AccountCard
                key={account.accountId}
                account={account}
                onClick={() => {
                  selectAccount(account.accountId)
                  navigate('/accounts')
                }}
              />
            ))}
          </div>

          {closedAccounts.length > 0 && (
            <div>
              <button type="button" onClick={() => setShowClosed((v) => !v)} className={btnGhostSm}>
                <Archive size={13} strokeWidth={2} />
                {showClosed ? 'Hide' : 'Show'} closed accounts ({closedAccounts.length})
              </button>
              {showClosed && (
                <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                  {closedAccounts.map((account) => (
                    <AccountCard
                      key={account.accountId}
                      account={account}
                      onClick={() => {
                        selectAccount(account.accountId)
                        navigate('/accounts')
                      }}
                    />
                  ))}
                </div>
              )}
            </div>
          )}

          {chartData.length > 0 && (
            <div className={card}>
              <h2 className="mb-4 text-sm font-semibold text-ink-700">Balance by account</h2>
              <ResponsiveContainer width="100%" height={240}>
                <BarChart data={chartData}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#eef0f3" vertical={false} />
                  <XAxis dataKey="name" tick={{ fontSize: 12, fill: '#6b7690' }} axisLine={false} tickLine={false} />
                  <YAxis tick={{ fontSize: 12, fill: '#6b7690' }} axisLine={false} tickLine={false} />
                  <Tooltip cursor={{ fill: '#f6f7fb' }} contentStyle={{ borderRadius: 12, border: '1px solid #e6e9f0' }} />
                  <Bar dataKey="balance" fill="#0284c7" radius={[8, 8, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </>
      )}

      {accounts && accounts.length === 0 && (
        <div className={`${card} text-center`}>
          <p className="text-sm text-ink-400">You don't have any accounts yet — open one from the Accounts page.</p>
        </div>
      )}

      {activeLoans.length > 0 && (
        <div className={`${card} flex items-center gap-4`}>
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-secondary-50 text-secondary-600">
            <Percent size={18} strokeWidth={2} />
          </span>
          <div>
            <h2 className="text-sm font-semibold text-ink-700">Active loans</h2>
            <p className="text-sm text-ink-400">
              {activeLoans.length} active loan{activeLoans.length === 1 ? '' : 's'}, totaling{' '}
              <span className="font-mono font-semibold text-ink-700">
                ${activeLoans.reduce((sum, l) => sum + l.outstandingPrincipal, 0).toFixed(2)}
              </span>{' '}
              outstanding principal.
            </p>
          </div>
        </div>
      )}
    </div>
  )
}
