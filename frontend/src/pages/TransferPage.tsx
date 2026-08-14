import { useState } from 'react'
import { useAuth } from '../hooks/useAuth'
import { useAccounts } from '../hooks/useAccounts'
import { TransferForm } from '../components/TransferForm'
import type { TransferResponse } from '../types/api'

export function TransferPage() {
  const user = useAuth()
  const { data: accounts } = useAccounts(user?.clientId)
  const [lastTransfer, setLastTransfer] = useState<TransferResponse | null>(null)

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">Transfer</h1>
        <p className="text-sm text-slate-500">
          Pay another client at this bank — instant, same-database, no external clearing. For paying a different
          bank, that's a payment, not a transfer (see the API docs).
        </p>
      </div>

      {accounts && accounts.length > 0 ? (
        <TransferForm accounts={accounts} onTransferred={setLastTransfer} />
      ) : (
        <p className="text-sm text-slate-500">You need an account before you can send a transfer.</p>
      )}

      {lastTransfer && (
        <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm">
          <h2 className="mb-2 font-medium text-slate-700">Last transfer</h2>
          <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-slate-600">
            <dt className="text-slate-400">Transfer id</dt>
            <dd>{lastTransfer.transferId}</dd>
            <dt className="text-slate-400">Amount</dt>
            <dd>{lastTransfer.amount}</dd>
            <dt className="text-slate-400">Status</dt>
            <dd>{lastTransfer.status}</dd>
          </dl>
        </div>
      )}
    </div>
  )
}
