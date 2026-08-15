import { useState } from 'react'
import toast from 'react-hot-toast'
import { Percent } from 'lucide-react'
import { apiErrorMessage } from '../api/client'
import { useLoanProducts, useOriginateLoan } from '../hooks/useLoans'
import { useAuth } from '../hooks/useAuth'
import { accountLabel, type AccountResponse, type LoanProductType, type LoanResponse } from '../types/api'
import { btnPrimary, card, input, label } from '../lib/styles'

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
          toast.success(`Loan originated: $${loan.principal} at ${loan.interestRateAnnualPercent}%`)
          setPrincipal('')
          onOriginated?.(loan)
        },
        onError: (error) => toast.error(apiErrorMessage(error)),
      },
    )
  }

  return (
    <form onSubmit={handleSubmit} className={`${card} flex flex-col gap-4`}>
      <div className="flex flex-col gap-1.5">
        <label className={label} htmlFor="loanAccountId">
          Disburse to
        </label>
        <select id="loanAccountId" value={accountId} onChange={(e) => setAccountId(e.target.value)} className={input} required>
          {accounts.map((account) => (
            <option key={account.accountId} value={account.accountId}>
              {accountLabel(account)}
            </option>
          ))}
        </select>
      </div>

      <div className="flex flex-col gap-1.5">
        <label className={label} htmlFor="productType">
          Loan product
        </label>
        <select
          id="productType"
          value={productType}
          onChange={(e) => setProductType(e.target.value as LoanProductType)}
          className={input}
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
          <p className="text-xs text-ink-400">
            {selectedProduct.interestRateAnnualPercent}% annual, {selectedProduct.termMonths}-month term — fixed by
            the product.
          </p>
        )}
      </div>

      <div className="flex flex-col gap-1.5">
        <label className={label} htmlFor="principal">
          Principal
        </label>
        <input
          id="principal"
          type="number"
          min="0.01"
          step="0.01"
          value={principal}
          onChange={(e) => setPrincipal(e.target.value)}
          className={input}
          required
        />
      </div>

      <button type="submit" disabled={originateLoan.isPending || !accountId || !productType} className={btnPrimary}>
        <Percent size={15} strokeWidth={2} />
        {originateLoan.isPending ? 'Submitting…' : 'Originate loan'}
      </button>
    </form>
  )
}
