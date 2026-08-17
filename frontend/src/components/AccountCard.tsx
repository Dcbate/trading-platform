import { ChevronRight, Wallet, PiggyBank, TrendingUp, LineChart, Bitcoin } from 'lucide-react'
import type { AccountResponse } from '../types/api'
import { accountTypeLabel, formatMoney } from '../lib/format'
import { Badge } from './Badge'

const typeIcon: Record<AccountResponse['accountType'], typeof Wallet> = {
  CHECKING: Wallet,
  SAVINGS: PiggyBank,
  FX_TRADING: TrendingUp,
  BROKERAGE: LineChart,
  CRYPTO: Bitcoin,
}

// Each account type gets its own colour, not one uniform blue chip — a small bit of the
// pot-colouring personality Monzo/Starling use so the list reads at a glance, not just on hover.
const typeColor: Record<AccountResponse['accountType'], string> = {
  CHECKING: 'bg-primary-50 text-primary-600 dark:bg-primary-500/15 dark:text-primary-300',
  SAVINGS: 'bg-success-50 text-success-600 dark:bg-success-500/15 dark:text-success-500',
  FX_TRADING: 'bg-secondary-50 text-secondary-600 dark:bg-secondary-500/15 dark:text-secondary-400',
  BROKERAGE: 'bg-warning-50 text-warning-600 dark:bg-warning-500/15 dark:text-warning-500',
  CRYPTO: 'bg-orange-50 text-orange-600 dark:bg-orange-500/15 dark:text-orange-400',
}

const statusVariant: Record<AccountResponse['status'], 'success' | 'warning' | 'neutral'> = {
  ACTIVE: 'success',
  FROZEN: 'warning',
  CLOSED: 'neutral',
}

// A list row, not a card — the dashboard's account list is "text + dividers" per the real-bank
// brief; TransactionTable/AccountsPage already share this flatter language.
export function AccountRow({ account, onClick }: { account: AccountResponse; onClick?: () => void }) {
  const Icon = typeIcon[account.accountType]
  return (
    <button
      type="button"
      onClick={onClick}
      className="group -mx-3 flex w-[calc(100%+1.5rem)] items-center gap-4 rounded-lg px-3 py-4 text-left transition hover:bg-canvas"
    >
      <span className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-lg transition group-hover:scale-105 ${typeColor[account.accountType]}`}>
        <Icon size={18} strokeWidth={2} />
      </span>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-semibold text-ink-900">
          {account.nickname ?? accountTypeLabel(account.accountType)}
        </p>
        <p className="text-xs text-ink-400">
          {account.nickname && `${accountTypeLabel(account.accountType)} · `}
          {account.currency}
        </p>
      </div>
      {account.status !== 'ACTIVE' && <Badge variant={statusVariant[account.status]}>{account.status}</Badge>}
      <p className="font-mono text-base font-semibold text-ink-900">{formatMoney(account.balance, account.currency)}</p>
      <ChevronRight size={16} strokeWidth={2} className="shrink-0 text-ink-400 transition group-hover:translate-x-0.5 group-hover:text-ink-900" />
    </button>
  )
}
