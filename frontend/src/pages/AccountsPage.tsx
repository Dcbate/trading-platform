import { useState } from 'react'
import toast from 'react-hot-toast'
import { apiErrorMessage } from '../api/client'
import { useAuth } from '../hooks/useAuth'
import {
  useAccounts,
  useCloseAccount,
  useConvert,
  useCurrencies,
  useDeposit,
  useOpenAccount,
  useWithdraw,
} from '../hooks/useAccounts'
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
        className="rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700 disabled:opacity-50"
      >
        {openAccount.isPending ? 'Opening…' : 'Open account'}
      </button>
    </form>
  )
}

const OTHER_DESTINATION = '__other__'

function CloseAccountPanel({
  account,
  siblings,
  onDone,
}: {
  account: AccountResponse
  siblings: AccountResponse[]
  onDone: () => void
}) {
  const closeAccount = useCloseAccount()
  const hasBalance = account.balance > 0
  const [ownDestination, setOwnDestination] = useState(siblings[0]?.accountId ?? OTHER_DESTINATION)
  const [otherDestinationId, setOtherDestinationId] = useState('')

  const destinationAccountId = ownDestination === OTHER_DESTINATION ? otherDestinationId.trim() : ownDestination
  const canConfirm = !hasBalance || !!destinationAccountId

  function handleConfirm() {
    if (!canConfirm) return
    closeAccount.mutate(
      { accountId: account.accountId, destinationAccountId: hasBalance ? destinationAccountId : undefined },
      {
        onSuccess: () => {
          toast.success('Account closed')
          onDone()
        },
        onError: (error) => toast.error(apiErrorMessage(error)),
      },
    )
  }

  return (
    <div className="flex flex-col gap-2 rounded-md border border-amber-200 bg-amber-50 p-2 text-xs">
      {hasBalance ? (
        <>
          <p className="text-slate-600">
            This account has a balance of {account.balance.toFixed(2)} {account.currency} — where should it go?
          </p>
          {siblings.length > 0 ? (
            <select
              value={ownDestination}
              onChange={(e) => setOwnDestination(e.target.value)}
              className="rounded-md border border-slate-300 px-2 py-1"
            >
              {siblings.map((s) => (
                <option key={s.accountId} value={s.accountId}>
                  {accountLabel(s)}
                </option>
              ))}
              <option value={OTHER_DESTINATION}>A different account (enter its id)…</option>
            </select>
          ) : (
            <p className="text-slate-500">
              You don't have another {account.currency} account — enter the outside account id to send it to:
            </p>
          )}
          {(siblings.length === 0 || ownDestination === OTHER_DESTINATION) && (
            <input
              type="text"
              value={otherDestinationId}
              onChange={(e) => setOtherDestinationId(e.target.value)}
              placeholder="destination account id"
              className="rounded-md border border-slate-300 px-2 py-1"
            />
          )}
        </>
      ) : (
        <p className="text-slate-600">Close this account? This can't be undone.</p>
      )}
      <div className="flex gap-2">
        <button
          type="button"
          onClick={handleConfirm}
          disabled={closeAccount.isPending || !canConfirm}
          className="rounded-md bg-red-700 px-2 py-1 text-white hover:bg-red-800 disabled:opacity-50"
        >
          {closeAccount.isPending ? 'Closing…' : 'Confirm close'}
        </button>
        <button type="button" onClick={onDone} className="rounded-md border border-slate-300 px-2 py-1 text-slate-600 hover:bg-slate-50">
          Cancel
        </button>
      </div>
    </div>
  )
}

function AccountActions({ account, allAccounts }: { account: AccountResponse; allAccounts: AccountResponse[] }) {
  const [amount, setAmount] = useState('')
  const [closing, setClosing] = useState(false)
  const deposit = useDeposit()
  const withdraw = useWithdraw()
  const closeAccount = useCloseAccount()

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

  if (account.status !== 'ACTIVE') {
    return <span className="text-xs text-slate-400">{account.status}</span>
  }

  if (closing) {
    const siblings = allAccounts.filter(
      (a) => a.accountId !== account.accountId && a.status === 'ACTIVE' && a.currency === account.currency,
    )
    return <CloseAccountPanel account={account} siblings={siblings} onDone={() => setClosing(false)} />
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
      <button
        type="button"
        onClick={() => setClosing(true)}
        disabled={closeAccount.isPending}
        className="rounded-md border border-red-200 px-2 py-1 text-xs text-red-700 hover:bg-red-50 disabled:opacity-50"
      >
        Close
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
        className="rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700 disabled:opacity-50"
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
  const activeAccounts = (accounts ?? []).filter((a) => a.status === 'ACTIVE')

  const columns: Column<AccountResponse>[] = [
    { header: 'Name', render: (a) => a.nickname ?? <span className="text-slate-400">—</span> },
    { header: 'Type', render: (a) => a.accountType },
    { header: 'Currency', render: (a) => a.currency },
    { header: 'Id', render: (a) => <span className="font-mono text-xs text-slate-400">{a.accountId.slice(0, 8)}</span> },
    { header: 'Balance', render: (a) => a.balance.toFixed(2) },
    { header: 'Status', render: (a) => a.status },
    { header: 'Actions', render: (a) => <AccountActions account={a} allAccounts={accounts ?? []} /> },
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

      {activeAccounts.length >= 2 && (
        <div>
          <h2 className="mb-2 text-sm font-medium text-slate-700">Convert (sell balance)</h2>
          <ConvertPanel accounts={activeAccounts} />
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
