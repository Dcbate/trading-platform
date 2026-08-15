import { useState } from 'react'
import toast from 'react-hot-toast'
import { apiErrorMessage } from '../api/client'
import { useAuth } from '../hooks/useAuth'
import { useAccounts } from '../hooks/useAccounts'
import { useLoanProducts, useLoans, useRepayLoan } from '../hooks/useLoans'
import { LoanForm } from '../components/LoanForm'
import { TransactionTable, type Column } from '../components/TransactionTable'
import { Badge } from '../components/Badge'
import { formatMoney } from '../lib/format'
import { btnGhostSm, inputSm, pageTitle } from '../lib/styles'
import type { LoanResponse } from '../types/api'

function RepayCell({ loan }: { loan: LoanResponse }) {
  const [amount, setAmount] = useState('')
  const repay = useRepayLoan()

  if (loan.status === 'PAID_OFF') {
    return <span className="text-xs text-ink-400">Paid off</span>
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
        className={btnGhostSm}
      >
        Repay
      </button>
    </div>
  )
}

export function LoansPage() {
  const user = useAuth()
  const { data: accounts } = useAccounts(user?.clientId)
  const activeAccounts = (accounts ?? []).filter((a) => a.status === 'ACTIVE')
  const { data: loans } = useLoans(user?.clientId)
  const { data: products } = useLoanProducts()

  // A loan doesn't carry its own currency — it's disbursed to (and repaid from) an account, so
  // format its amounts in that account's real currency rather than assuming one.
  const currencyByAccountId = new Map((accounts ?? []).map((a) => [a.accountId, a.currency]))
  const productName = (code: string) => products?.find((p) => p.code === code)?.displayName ?? code

  const columns: Column<LoanResponse>[] = [
    { header: 'Product', render: (l) => productName(l.productType) },
    {
      header: 'Principal',
      render: (l) => <span className="font-mono">{formatMoney(l.principal, currencyByAccountId.get(l.accountId) ?? 'GBP')}</span>,
    },
    {
      header: 'Outstanding',
      render: (l) => (
        <span className="font-mono">{formatMoney(l.outstandingPrincipal, currencyByAccountId.get(l.accountId) ?? 'GBP')}</span>
      ),
    },
    {
      header: 'Accrued interest',
      render: (l) => (
        <span className="font-mono">{formatMoney(l.accruedInterest, currencyByAccountId.get(l.accountId) ?? 'GBP')}</span>
      ),
    },
    { header: 'Rate', render: (l) => `${l.interestRateAnnualPercent}%` },
    { header: 'Status', render: (l) => <Badge variant={l.status === 'ACTIVE' ? 'primary' : 'success'}>{l.status}</Badge> },
    { header: 'Repay', render: (l) => <RepayCell loan={l} /> },
  ]

  return (
    <div className="flex flex-col gap-10">
      <div>
        <h1 className={pageTitle}>Loans</h1>
        <p className="text-sm text-ink-400">
          Rates and terms come from a fixed product catalog — repayments pay down accrued interest first, then
          principal.
        </p>
      </div>

      {activeAccounts.length > 0 ? (
        <LoanForm accounts={activeAccounts} />
      ) : (
        <p className="text-sm text-ink-400">You need an active account before you can originate a loan.</p>
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
