import { useState } from 'react'
import toast from 'react-hot-toast'
import { apiErrorMessage } from '../api/client'
import { useLoanProducts, useOriginateLoan } from '../hooks/useLoans'
import { useAuth } from '../hooks/useAuth'
import { accountLabel, type AccountResponse, type LoanProductType, type LoanResponse } from '../types/api'

export function LoanForm({
  accounts,
  onOriginated,
}: {
  accounts: AccountResponse[]
  onOriginated?: (loan: LoanResponse) => void
}) {
  const user = useAuth()
  const { data: products } = useLoanProducts()
  const [accountId, setAccountId] = useState(accounts[0]?.accountId ?? '')
  const [productType, setProductType] = useState<LoanProductType | ''>('')
  const [principal, setPrincipal] = useState('')
  const originateLoan = useOriginateLoan()

  const selectedProduct = products?.find((p) => p.code === productType)

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!user || !productType) return
    originateLoan.mutate(
      { clientId: user.clientId, accountId, principal: Number(principal), productType },
      {
        onSuccess: (loan) => {
          toast.success(`Loan originated: ${loan.principal} at ${loan.interestRateAnnualPercent}%`)
          setPrincipal('')
          onOriginated?.(loan)
        },
        onError: (error) => toast.error(apiErrorMessage(error)),
      },
    )
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4 rounded-lg border border-slate-200 bg-white p-4">
      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium text-slate-700" htmlFor="loanAccountId">
          Disburse to
        </label>
        <select
          id="loanAccountId"
          value={accountId}
          onChange={(e) => setAccountId(e.target.value)}
          className="rounded-md border border-slate-300 px-3 py-2 text-sm"
          required
        >
          {accounts.map((account) => (
            <option key={account.accountId} value={account.accountId}>
              {accountLabel(account)}
            </option>
          ))}
        </select>
      </div>

      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium text-slate-700" htmlFor="productType">
          Loan product
        </label>
        <select
          id="productType"
          value={productType}
          onChange={(e) => setProductType(e.target.value as LoanProductType)}
          className="rounded-md border border-slate-300 px-3 py-2 text-sm"
          required
        >
          <option value="" disabled>
            Choose a product
          </option>
          {products?.map((p) => (
            <option key={p.code} value={p.code}>
              {p.displayName} — {p.interestRateAnnualPercent}% / {p.termMonths}mo
            </option>
          ))}
        </select>
        {selectedProduct && (
          <p className="text-xs text-slate-400">
            {selectedProduct.interestRateAnnualPercent}% annual, {selectedProduct.termMonths}-month term — the rate is
            fixed by the product, not something you can set yourself.
          </p>
        )}
      </div>

      <div className="flex flex-col gap-1">
        <label className="text-sm font-medium text-slate-700" htmlFor="principal">
          Principal
        </label>
        <input
          id="principal"
          type="number"
          min="0.01"
          step="0.01"
          value={principal}
          onChange={(e) => setPrincipal(e.target.value)}
          className="rounded-md border border-slate-300 px-3 py-2 text-sm"
          required
        />
      </div>

      <button
        type="submit"
        disabled={originateLoan.isPending || !accountId || !productType}
        className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-50"
      >
        {originateLoan.isPending ? 'Submitting…' : 'Originate loan'}
      </button>
    </form>
  )
}
