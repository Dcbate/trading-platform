import type { AccountResponse } from '../types/api'

const typeLabel: Record<AccountResponse['accountType'], string> = {
  CHECKING: 'Checking',
  SAVINGS: 'Savings',
  FX_TRADING: 'FX Trading',
}

const statusColor: Record<AccountResponse['status'], string> = {
  ACTIVE: 'bg-green-100 text-green-800',
  FROZEN: 'bg-amber-100 text-amber-800',
  CLOSED: 'bg-slate-100 text-slate-600',
}

function formatMoney(amount: number, currency: string) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(amount)
}

export function AccountCard({ account, onClick }: { account: AccountResponse; onClick?: () => void }) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex w-full flex-col gap-2 rounded-lg border border-slate-200 bg-white p-4 text-left shadow-sm transition hover:border-slate-300 hover:shadow"
    >
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium text-slate-500">
          {account.nickname ?? typeLabel[account.accountType]}
        </span>
        <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${statusColor[account.status]}`}>
          {account.status}
        </span>
      </div>
      <span className="text-2xl font-semibold text-slate-900">{formatMoney(account.balance, account.currency)}</span>
      <span className="text-xs text-slate-400">
        {account.nickname && `${typeLabel[account.accountType]} · `}
        {account.currency} · {account.accountId.slice(0, 8)}
      </span>
    </button>
  )
}
