import { useState } from 'react'
import toast from 'react-hot-toast'
import { apiErrorMessage } from '../api/client'
import { useAuth } from '../hooks/useAuth'
import { useAccounts, useConvert, useCurrencies, useDeposit, useOpenAccount, useWithdraw } from '../hooks/useAccounts'
import { useAppStore } from '../store/appStore'
import { TransactionTable, type Column } from '../components/TransactionTable'
import { accountLabel, type AccountResponse, type AccountType } from '../types/api'

const ACCOUNT_TYPES: AccountType[] = ['CHECKING', 'SAVINGS', 'FX_TRADING']

function OpenAccountForm({ clientId }: { clientId: string }) {
  const { data: currencies } = useCurrencies()
  const openAccount = useOpenAccount()
  const [accountType, setAccountType] = useState<AccountType>('CHECKING')
  const [currency, setCurrency] = useState('')
  const [nickname, setNickname] = useState('')
  const [openingBalance, setOpeningBalance] = useState('0')

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    openAccount.mutate(
      { clientId, accountType, currency, nickname: nickname.trim() || null, openingBalance: Number(openingBalance) },
      {
        onSuccess: () => {
          toast.success('Account opened')
          setNickname('')
        },
        onError: (error) => toast.error(apiErrorMessage(error)),
      },
    )
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-wrap items-end gap-3 rounded-lg border border-slate-200 bg-white p-4">
      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium text-slate-700" htmlFor="accountType">
          Type
        </label>
        <select
          id="accountType"
          value={accountType}
          onChange={(e) => setAccountType(e.target.value as AccountType)}
          className="rounded-md border border-slate-300 px-3 py-2 text-sm"
        >
          {ACCOUNT_TYPES.map((type) => (
            <option key={type} value={type}>
              {type}
            </option>
          ))}
        </select>
      </div>
      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium text-slate-700" htmlFor="currency">
          Currency
        </label>
        <select
          id="currency"
          value={currency}
          onChange={(e) => setCurrency(e.target.value)}
          className="rounded-md border border-slate-300 px-3 py-2 text-sm"
          required
        >
          <option value="" disabled>
            Select
          </option>
          {currencies?.map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </select>
      </div>
      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium text-slate-700" htmlFor="nickname">
          Name <span className="font-normal text-slate-400">(optional)</span>
        </label>
        <input
          id="nickname"
          type="text"
          maxLength={64}
          value={nickname}
          onChange={(e) => setNickname(e.target.value)}
          placeholder="e.g. Rent fund"
          className="w-40 rounded-md border border-slate-300 px-3 py-2 text-sm"
        />
      </div>
      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium text-slate-700" htmlFor="openingBalance">
          Opening balance
        </label>
        <input
          id="openingBalance"
          type="number"
          min="0"
          step="0.01"
          value={openingBalance}
          onChange={(e) => setOpeningBalance(e.target.value)}
          className="w-32 rounded-md border border-slate-300 px-3 py-2 text-sm"
        />
      </div>
      <button
        type="submit"
        disabled={openAccount.isPending || !currency}
        className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-50"
      >
        {openAccount.isPending ? 'Opening…' : 'Open account'}
      </button>
    </form>
  )
}

function AccountActions({ account }: { account: AccountResponse }) {
  const [amount, setAmount] = useState('')
  const deposit = useDeposit()
  const withdraw = useWithdraw()

  function run(mutation: typeof deposit, verb: string) {
    const value = Number(amount)
    if (!value) return
    mutation.mutate(
      { accountId: account.accountId, amount: value },
      {
        onSuccess: () => {
          toast.success(`${verb} succeeded`)
          setAmount('')
        },
        onError: (error) => toast.error(apiErrorMessage(error)),
      },
    )
  }

  return (
    <div className="flex items-center gap-2">
      <input
        type="number"
        min="0.01"
        step="0.01"
        value={amount}
        onChange={(e) => setAmount(e.target.value)}
        placeholder="amount"
        className="w-24 rounded-md border border-slate-300 px-2 py-1 text-xs"
      />
      <button
        type="button"
        onClick={() => run(deposit, 'Deposit')}
        disabled={deposit.isPending}
        className="rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-700 hover:bg-slate-50 disabled:opacity-50"
      >
        Deposit
      </button>
      <button
        type="button"
        onClick={() => run(withdraw, 'Withdrawal')}
        disabled={withdraw.isPending}
        className="rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-700 hover:bg-slate-50 disabled:opacity-50"
      >
        Withdraw
      </button>
    </div>
  )
}

function ConvertPanel({ accounts }: { accounts: AccountResponse[] }) {
  const convert = useConvert()
  const [fromAccountId, setFromAccountId] = useState(accounts[0]?.accountId ?? '')
  const [toAccountId, setToAccountId] = useState('')
  const [amount, setAmount] = useState('')

  if (accounts.length < 2) return null

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    convert.mutate(
      { fromAccountId, toAccountId, amount: Number(amount) },
      {
        onSuccess: () => {
          toast.success('Converted')
          setAmount('')
        },
        onError: (error) => toast.error(apiErrorMessage(error)),
      },
    )
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-wrap items-end gap-3 rounded-lg border border-slate-200 bg-white p-4">
      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium text-slate-700">Sell from</label>
        <select
          value={fromAccountId}
          onChange={(e) => setFromAccountId(e.target.value)}
          className="rounded-md border border-slate-300 px-3 py-2 text-sm"
        >
          {accounts.map((a) => (
            <option key={a.accountId} value={a.accountId}>
              {accountLabel(a)}
            </option>
          ))}
        </select>
      </div>
      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium text-slate-700">Buy into</label>
        <select
          value={toAccountId}
          onChange={(e) => setToAccountId(e.target.value)}
          className="rounded-md border border-slate-300 px-3 py-2 text-sm"
          required
        >
          <option value="" disabled>
            Select
          </option>
          {accounts
            .filter((a) => a.accountId !== fromAccountId)
            .map((a) => (
              <option key={a.accountId} value={a.accountId}>
                {accountLabel(a)}
              </option>
            ))}
        </select>
      </div>
      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium text-slate-700">Amount</label>
        <input
          type="number"
          min="0.01"
          step="0.01"
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          className="w-32 rounded-md border border-slate-300 px-3 py-2 text-sm"
          required
        />
      </div>
      <button
        type="submit"
        disabled={convert.isPending || !toAccountId}
        className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-50"
      >
        {convert.isPending ? 'Converting…' : 'Convert balance'}
      </button>
    </form>
  )
}

export function AccountsPage() {
  const user = useAuth()
  const { data: accounts } = useAccounts(user?.clientId)
  const selectedAccountId = useAppStore((s) => s.selectedAccountId)

  const columns: Column<AccountResponse>[] = [
    { header: 'Name', render: (a) => a.nickname ?? <span className="text-slate-400">—</span> },
    { header: 'Type', render: (a) => a.accountType },
    { header: 'Currency', render: (a) => a.currency },
    { header: 'Id', render: (a) => <span className="font-mono text-xs text-slate-400">{a.accountId.slice(0, 8)}</span> },
    { header: 'Balance', render: (a) => a.balance.toFixed(2) },
    { header: 'Status', render: (a) => a.status },
    { header: 'Actions', render: (a) => <AccountActions account={a} /> },
  ]

  return (
    <div className="flex flex-col gap-8">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">Accounts</h1>
        <p className="text-sm text-slate-500">Open accounts, deposit, withdraw, and convert between your own balances.</p>
      </div>

      {user && <OpenAccountForm clientId={user.clientId} />}

      <TransactionTable
        rows={accounts ?? []}
        columns={columns}
        keyField="accountId"
        emptyMessage="No accounts yet — open one above."
      />

      {accounts && accounts.length >= 2 && (
        <div>
          <h2 className="mb-2 text-sm font-medium text-slate-700">Convert (sell balance)</h2>
          <ConvertPanel accounts={accounts} />
        </div>
      )}

      {selectedAccountId && (
        <p className="text-xs text-slate-400">
          Came from the dashboard for account {selectedAccountId.slice(0, 8)} — deposit/withdraw it above.
        </p>
      )}
    </div>
  )
}
