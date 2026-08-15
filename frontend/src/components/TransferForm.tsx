import { useState } from 'react'
import toast from 'react-hot-toast'
import { Send } from 'lucide-react'
import { apiErrorMessage } from '../api/client'
import { useCreateTransfer } from '../hooks/useTransfers'
import { accountLabel, type AccountResponse, type TransferResponse } from '../types/api'
import { formatMoney } from '../lib/format'
import { btnPrimary, card, input, label } from '../lib/styles'

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
          const currency = accounts.find((a) => a.accountId === transfer.fromAccountId)?.currency ?? 'GBP'
          toast.success(`Sent ${formatMoney(transfer.amount, currency)} — ${transfer.status}`)
          setToAccountId('')
          setAmount('')
          onTransferred?.(transfer)
        },
        onError: (error) => toast.error(apiErrorMessage(error)),
      },
    )
  }

  return (
    <form onSubmit={handleSubmit} className={`${card} flex flex-col gap-4`}>
      <div className="flex flex-col gap-1.5">
        <label className={label} htmlFor="fromAccountId">
          From account
        </label>
        <select id="fromAccountId" value={fromAccountId} onChange={(e) => setFromAccountId(e.target.value)} className={input} required>
          {accounts.map((account) => (
            <option key={account.accountId} value={account.accountId}>
              {accountLabel(account)} · {formatMoney(account.balance, account.currency)}
            </option>
          ))}
        </select>
      </div>

      <div className="flex flex-col gap-1.5">
        <label className={label} htmlFor="toAccountId">
          Recipient account id
        </label>
        <input
          id="toAccountId"
          value={toAccountId}
          onChange={(e) => setToAccountId(e.target.value)}
          placeholder="the other client's account UUID"
          className={input}
          required
        />
      </div>

      <div className="flex flex-col gap-1.5">
        <label className={label} htmlFor="amount">
          Amount
        </label>
        <input
          id="amount"
          type="number"
          min="0.01"
          step="0.01"
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          className={input}
          required
        />
      </div>

      <button type="submit" disabled={createTransfer.isPending || !fromAccountId} className={btnPrimary}>
        <Send size={15} strokeWidth={2} />
        {createTransfer.isPending ? 'Sending…' : 'Send transfer'}
      </button>
    </form>
  )
}
