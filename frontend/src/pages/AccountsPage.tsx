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
import { Badge } from '../components/Badge'
import { accountLabel, type AccountResponse, type AccountType } from '../types/api'
import { accountTypeLabel, formatMoney } from '../lib/format'
import { btnDangerSm, btnGhostSm, btnPrimary, btnSuccessSm, card, input, inputSm, label, pageTitle, sectionTitle } from '../lib/styles'

const ACCOUNT_TYPES: AccountType[] = ['CHECKING', 'SAVINGS', 'FX_TRADING', 'BROKERAGE']

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
    <form onSubmit={handleSubmit} className={`${card} flex flex-wrap items-end gap-3`}>
      <div className="flex flex-col gap-1.5">
        <label className={label} htmlFor="accountType">
          Type
        </label>
        <select id="accountType" value={accountType} onChange={(e) => setAccountType(e.target.value as AccountType)} className={input}>
          {ACCOUNT_TYPES.map((type) => (
            <option key={type} value={type}>
              {accountTypeLabel(type)}
            </option>
          ))}
        </select>
      </div>
      <div className="flex flex-col gap-1.5">
        <label className={label} htmlFor="currency">
          Currency
        </label>
        <select id="currency" value={currency} onChange={(e) => setCurrency(e.target.value)} className={input} required>
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
      <div className="flex flex-col gap-1.5">
        <label className={label} htmlFor="nickname">
          Name <span className="font-normal normal-case text-ink-400">(optional)</span>
        </label>
        <input
          id="nickname"
          type="text"
          maxLength={64}
          value={nickname}
          onChange={(e) => setNickname(e.target.value)}
          placeholder="e.g. Rent fund"
          className={`w-40 ${input}`}
        />
      </div>
      <div className="flex flex-col gap-1.5">
        <label className={label} htmlFor="openingBalance">
          Opening balance
        </label>
        <input
          id="openingBalance"
          type="number"
          min="0"
          step="0.01"
          value={openingBalance}
          onChange={(e) => setOpeningBalance(e.target.value)}
          className={`w-32 ${input}`}
        />
      </div>
      <button type="submit" disabled={openAccount.isPending || !currency} className={btnPrimary}>
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
    <div className="flex flex-col gap-2 rounded-xl border border-warning-100 bg-warning-50 p-3 text-xs dark:border-warning-500/20 dark:bg-warning-500/10">
      {hasBalance ? (
        <>
          <p className="text-ink-600">
            This account has a balance of {formatMoney(account.balance, account.currency)} — where should it go?
          </p>
          {siblings.length > 0 ? (
            <select value={ownDestination} onChange={(e) => setOwnDestination(e.target.value)} className={inputSm}>
              {siblings.map((s) => (
                <option key={s.accountId} value={s.accountId}>
                  {accountLabel(s)}
                </option>
              ))}
              <option value={OTHER_DESTINATION}>A different account (enter its id)…</option>
            </select>
          ) : (
            <p className="text-ink-400">You don't have another {account.currency} account — enter the outside account id to send it to:</p>
          )}
          {(siblings.length === 0 || ownDestination === OTHER_DESTINATION) && (
            <input
              type="text"
              value={otherDestinationId}
              onChange={(e) => setOtherDestinationId(e.target.value)}
              placeholder="destination account id"
              className={inputSm}
            />
          )}
        </>
      ) : (
        <p className="text-ink-600">Close this account? This can't be undone.</p>
      )}
      <div className="flex gap-2">
        <button type="button" onClick={handleConfirm} disabled={closeAccount.isPending || !canConfirm} className={btnDangerSm}>
          {closeAccount.isPending ? 'Closing…' : 'Confirm close'}
        </button>
        <button type="button" onClick={onDone} className={btnGhostSm}>
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
    return <span className="text-xs text-ink-400">{account.status}</span>
  }

  if (closing) {
    const siblings = allAccounts.filter(
      (a) => a.accountId !== account.accountId && a.status === 'ACTIVE' && a.currency === account.currency,
    )
    return <CloseAccountPanel account={account} siblings={siblings} onDone={() => setClosing(false)} />
  }

  return (
    <div className="flex items-center gap-1.5">
      <input
        type="number"
        min="0.01"
        step="0.01"
        value={amount}
        onChange={(e) => setAmount(e.target.value)}
        placeholder="amount"
        className={`w-24 ${inputSm}`}
      />
      <button type="button" onClick={() => run(deposit, 'Deposit')} disabled={deposit.isPending} className={btnSuccessSm}>
        Deposit
      </button>
      <button type="button" onClick={() => run(withdraw, 'Withdrawal')} disabled={withdraw.isPending} className={btnGhostSm}>
        Withdraw
      </button>
      <button type="button" onClick={() => setClosing(true)} disabled={closeAccount.isPending} className={btnDangerSm}>
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
    <form onSubmit={handleSubmit} className={`${card} flex flex-wrap items-end gap-3`}>
      <div className="flex flex-col gap-1.5">
        <label className={label}>Sell from</label>
        <select value={fromAccountId} onChange={(e) => setFromAccountId(e.target.value)} className={input}>
          {accounts.map((a) => (
            <option key={a.accountId} value={a.accountId}>
              {accountLabel(a)}
            </option>
          ))}
        </select>
      </div>
      <div className="flex flex-col gap-1.5">
        <label className={label}>Buy into</label>
        <select value={toAccountId} onChange={(e) => setToAccountId(e.target.value)} className={input} required>
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
      <div className="flex flex-col gap-1.5">
        <label className={label}>Amount</label>
        <input
          type="number"
          min="0.01"
          step="0.01"
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          className={`w-32 ${input}`}
          required
        />
      </div>
      <button type="submit" disabled={convert.isPending || !toAccountId} className={btnPrimary}>
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
    { header: 'Name', render: (a) => a.nickname ?? <span className="text-ink-400">—</span> },
    { header: 'Type', render: (a) => accountTypeLabel(a.accountType) },
    { header: 'Currency', render: (a) => a.currency },
    { header: 'Id', render: (a) => <span className="font-mono text-xs text-ink-400">{a.accountId.slice(0, 8)}</span> },
    { header: 'Balance', render: (a) => <span className="font-mono font-semibold">{formatMoney(a.balance, a.currency)}</span> },
    {
      header: 'Status',
      render: (a) => <Badge variant={a.status === 'ACTIVE' ? 'success' : a.status === 'FROZEN' ? 'warning' : 'neutral'}>{a.status}</Badge>,
    },
    { header: 'Actions', render: (a) => <AccountActions account={a} allAccounts={accounts ?? []} /> },
  ]

  return (
    <div className="flex flex-col gap-10">
      <div>
        <h1 className={pageTitle}>Accounts</h1>
        <p className="text-sm text-ink-400">Open accounts, deposit, withdraw, and convert between your own balances.</p>
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
          <h2 className={sectionTitle}>Convert (sell balance)</h2>
          <div className="mt-2">
            <ConvertPanel accounts={activeAccounts} />
          </div>
        </div>
      )}

      {selectedAccountId && (
        <p className="text-xs text-ink-400">
          Came from the dashboard for account {selectedAccountId.slice(0, 8)} — deposit/withdraw it above.
        </p>
      )}
    </div>
  )
}
