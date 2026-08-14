import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { useAuth } from '../hooks/useAuth'
import { useAccounts } from '../hooks/useAccounts'
import { useLoans } from '../hooks/useLoans'
import { AccountCard } from '../components/AccountCard'
import { useNavigate } from 'react-router-dom'
import { useAppStore } from '../store/appStore'

export function DashboardPage() {
  const user = useAuth()
  const navigate = useNavigate()
  const selectAccount = useAppStore((s) => s.selectAccount)
  const { data: accounts, isLoading: accountsLoading } = useAccounts(user?.clientId)
  const { data: loans } = useLoans(user?.clientId)

  const activeLoans = loans?.filter((l) => l.status === 'ACTIVE') ?? []
  const chartData = (accounts ?? []).map((a) => ({
    name: `${a.accountType} (${a.currency})`,
    balance: a.balance,
  }))

  return (
    <div className="flex flex-col gap-8">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">Welcome back{user ? `, ${user.email}` : ''}</h1>
        <p className="text-sm text-slate-500">Here's where your money is right now.</p>
      </div>

      {accountsLoading && <p className="text-sm text-slate-400">Loading accounts…</p>}

      {accounts && accounts.length > 0 && (
        <>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {accounts.map((account) => (
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

          <div className="rounded-lg border border-slate-200 bg-white p-4">
            <h2 className="mb-4 text-sm font-medium text-slate-700">Balance by account</h2>
            <ResponsiveContainer width="100%" height={240}>
              <BarChart data={chartData}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                <XAxis dataKey="name" tick={{ fontSize: 12 }} />
                <YAxis tick={{ fontSize: 12 }} />
                <Tooltip />
                <Bar dataKey="balance" fill="#0f172a" radius={[4, 4, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>
        </>
      )}

      {accounts && accounts.length === 0 && (
        <p className="text-sm text-slate-500">You don't have any accounts yet — open one from the Accounts page.</p>
      )}

      {activeLoans.length > 0 && (
        <div className="rounded-lg border border-slate-200 bg-white p-4">
          <h2 className="mb-2 text-sm font-medium text-slate-700">Active loans</h2>
          <p className="text-sm text-slate-500">
            {activeLoans.length} active loan{activeLoans.length === 1 ? '' : 's'}, totaling{' '}
            {activeLoans.reduce((sum, l) => sum + l.outstandingPrincipal, 0).toFixed(2)} outstanding principal.
          </p>
        </div>
      )}
    </div>
  )
}
