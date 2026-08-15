import { useState } from 'react'
import { CheckCircle2 } from 'lucide-react'
import { useAuth } from '../hooks/useAuth'
import { useAccounts } from '../hooks/useAccounts'
import { TransferForm } from '../components/TransferForm'
import { Badge } from '../components/Badge'
import { card } from '../lib/styles'
import type { TransferResponse } from '../types/api'

export function TransferPage() {
  const user = useAuth()
  const { data: accounts } = useAccounts(user?.clientId)
  const activeAccounts = (accounts ?? []).filter((a) => a.status === 'ACTIVE')
  const [lastTransfer, setLastTransfer] = useState<TransferResponse | null>(null)

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold text-ink-900">Transfer</h1>
        <p className="text-sm text-ink-400">
          Pay another client at this bank — instant, no external clearing. Paying a different bank is a payment,
          not a transfer.
        </p>
      </div>

      {activeAccounts.length > 0 ? (
        <TransferForm accounts={activeAccounts} onTransferred={setLastTransfer} />
      ) : (
        <p className="text-sm text-ink-400">You need an active account before you can send a transfer.</p>
      )}

      {lastTransfer && (
        <div className={`${card} flex items-center gap-4`}>
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-success-50 text-success-600">
            <CheckCircle2 size={18} strokeWidth={2} />
          </span>
          <div className="flex-1">
            <div className="flex items-center gap-2">
              <p className="text-sm font-semibold text-ink-700">Transfer sent</p>
              <Badge variant={lastTransfer.status === 'COMPLETED' ? 'success' : 'error'}>{lastTransfer.status}</Badge>
            </div>
            <p className="font-mono text-lg font-semibold text-ink-900">${lastTransfer.amount.toFixed(2)}</p>
            <p className="text-xs text-ink-400">{lastTransfer.transferId}</p>
          </div>
        </div>
      )}
    </div>
  )
}
