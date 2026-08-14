import { useState } from 'react'
import toast from 'react-hot-toast'
import { apiErrorMessage } from '../api/client'
import { useCreateTransfer } from '../hooks/useTransfers'
import { accountLabel, type AccountResponse, type TransferResponse } from '../types/api'

export function TransferForm({
  accounts,
  onTransferred,
}: {
  accounts: AccountResponse[]
  onTransferred?: (transfer: TransferResponse) => void
}) {
  const [fromAccountId, setFromAccountId] = useState(accounts[0]?.accountId ?? '')
  const [toAccountId, setToAccountId] = useState('')
  const [amount, setAmount] = useState('')
  const createTransfer = useCreateTransfer()

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    createTransfer.mutate(
      { fromAccountId, toAccountId, amount: Number(amount) },
      {
        onSuccess: (transfer) => {
          toast.success(`Sent ${transfer.amount} — status ${transfer.status}`)
          setToAccountId('')
          setAmount('')
          onTransferred?.(transfer)
        },
        onError: (error) => toast.error(apiErrorMessage(error)),
      },
    )
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4 rounded-lg border border-slate-200 bg-white p-4">
      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium text-slate-700" htmlFor="fromAccountId">
          From account
        </label>
        <select
          id="fromAccountId"
          value={fromAccountId}
          onChange={(e) => setFromAccountId(e.target.value)}
          className="rounded-md border border-slate-300 px-3 py-2 text-sm"
          required
        >
          {accounts.map((account) => (
            <option key={account.accountId} value={account.accountId}>
              {accountLabel(account)} · {account.balance}
            </option>
          ))}
        </select>
      </div>

      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium text-slate-700" htmlFor="toAccountId">
          Recipient account id
        </label>
        <input
          id="toAccountId"
          value={toAccountId}
          onChange={(e) => setToAccountId(e.target.value)}
          placeholder="the other client's account UUID"
          className="rounded-md border border-slate-300 px-3 py-2 text-sm"
          required
        />
      </div>

      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium text-slate-700" htmlFor="amount">
          Amount
        </label>
        <input
          id="amount"
          type="number"
          min="0.01"
          step="0.01"
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          className="rounded-md border border-slate-300 px-3 py-2 text-sm"
          required
        />
      </div>

      <button
        type="submit"
        disabled={createTransfer.isPending || !fromAccountId}
        className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-50"
      >
        {createTransfer.isPending ? 'Sending…' : 'Send transfer'}
      </button>
    </form>
  )
}
