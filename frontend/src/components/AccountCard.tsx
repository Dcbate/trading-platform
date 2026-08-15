import { Wallet, PiggyBank, TrendingUp, LineChart } from 'lucide-react'
import type { AccountResponse } from '../types/api'
import { Badge } from './Badge'

const typeLabel: Record<AccountResponse['accountType'], string> = {
  CHECKING: 'Checking',
  SAVINGS: 'Savings',
  FX_TRADING: 'FX Trading',
  BROKERAGE: 'Brokerage',
}

const typeIcon: Record<AccountResponse['accountType'], typeof Wallet> = {
  CHECKING: Wallet,
  SAVINGS: PiggyBank,
  FX_TRADING: TrendingUp,
  BROKERAGE: LineChart,
}

const statusVariant: Record<AccountResponse['status'], 'success' | 'warning' | 'neutral'> = {
  ACTIVE: 'success',
  FROZEN: 'warning',
  CLOSED: 'neutral',
}

function formatMoney(amount: number, currency: string) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(amount)
}

export function AccountCard({ account, onClick }: { account: AccountResponse; onClick?: () => void }) {
  const Icon = typeIcon[account.accountType]
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex w-full flex-col gap-4 rounded-2xl border border-ink-100 bg-white p-5 text-left shadow-sm shadow-ink-900/[0.02] transition hover:-translate-y-0.5 hover:shadow-md"
    >
      <div className="flex items-center justify-between">
        <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-primary-50 text-primary-600">
          <Icon size={18} strokeWidth={2} />
        </span>
        <Badge variant={statusVariant[account.status]}>{account.status}</Badge>
      </div>
      <div>
        <p className="text-sm font-medium text-ink-400">{account.nickname ?? typeLabel[account.accountType]}</p>
        <p className="font-mono text-3xl font-semibold tracking-tight text-ink-900">
          {formatMoney(account.balance, account.currency)}
        </p>
      </div>
      <p className="text-xs text-ink-400">
        {account.nickname && `${typeLabel[account.accountType]} · `}
        {account.currency} · {account.accountId.slice(0, 8)}
      </p>
    </button>
  )
}
