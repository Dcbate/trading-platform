import { useState } from 'react'
import toast from 'react-hot-toast'
import { apiErrorMessage } from '../api/client'
import { useAuth } from '../hooks/useAuth'
import { useAccounts } from '../hooks/useAccounts'
import { useLoans, useRepayLoan } from '../hooks/useLoans'
import { LoanForm } from '../components/LoanForm'
import { TransactionTable, type Column } from '../components/TransactionTable'
import type { LoanResponse } from '../types/api'

function RepayCell({ loan }: { loan: LoanResponse }) {
  const [amount, setAmount] = useState('')
  const repay = useRepayLoan()

  if (loan.status === 'PAID_OFF') {
    return <span className="text-xs text-slate-400">Paid off</span>
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
        disabled={repay.isPending || !amount}
        onClick={() =>
          repay.mutate(
            { loanId: loan.loanId, amount: Number(amount) },
            {
              onSuccess: () => {
                toast.success('Repayment applied')
                setAmount('')
              },
              onError: (error) => toast.error(apiErrorMessage(error)),
            },
          )
        }
        className="rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-700 hover:bg-slate-50 disabled:opacity-50"
      >
        Repay
      </button>
    </div>
  )
}

export function LoansPage() {
  const user = useAuth()
  const { data: accounts } = useAccounts(user?.clientId)
  const { data: loans } = useLoans(user?.clientId)

  const columns: Column<LoanResponse>[] = [
    { header: 'Product', render: (l) => l.productType },
    { header: 'Principal', render: (l) => l.principal.toFixed(2) },
    { header: 'Outstanding', render: (l) => l.outstandingPrincipal.toFixed(2) },
    { header: 'Accrued interest', render: (l) => l.accruedInterest.toFixed(2) },
    { header: 'Rate', render: (l) => `${l.interestRateAnnualPercent}%` },
    { header: 'Status', render: (l) => l.status },
    { header: 'Repay', render: (l) => <RepayCell loan={l} /> },
  ]

  return (
    <div className="flex flex-col gap-8">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">Loans</h1>
        <p className="text-sm text-slate-500">
          Rates and terms come from a fixed product catalog — repayments pay down accrued interest first, then
          principal.
        </p>
      </div>

      {accounts && accounts.length > 0 ? (
        <LoanForm accounts={accounts} />
      ) : (
        <p className="text-sm text-slate-500">You need an account before you can originate a loan.</p>
      )}

      <TransactionTable
        rows={loans ?? []}
        columns={columns}
        keyField="loanId"
        emptyMessage="No loans yet — originate one above."
      />
    </div>
  )
}
