import { useAuth } from '../hooks/useAuth'
import { useBankStatement } from '../hooks/useBankStatement'
import { TransactionTable, type Column } from '../components/TransactionTable'
import { Badge } from '../components/Badge'
import { formatSignedAmount, statementEntryTypeLabel } from '../lib/format'
import { pageTitle } from '../lib/styles'
import type { BankStatementEntry, StatementEntryType } from '../types/api'

const BADGE_VARIANT: Record<StatementEntryType, 'success' | 'warning' | 'error' | 'neutral' | 'primary'> = {
  FX_ORDER: 'primary',
  PAYMENT: 'neutral',
  TRANSFER_OUT: 'neutral',
  TRANSFER_IN: 'neutral',
  DEPOSIT: 'success',
  WITHDRAWAL: 'warning',
  CONVERSION: 'primary',
  ACCOUNT_CLOSURE: 'error',
  LOAN_ORIGINATED: 'success',
  LOAN_REPAYMENT: 'warning',
}

function AmountCell({ entry }: { entry: BankStatementEntry }) {
  if (entry.amount === null) {
    return <span className="text-ink-400">—</span>
  }
  const positive = entry.amount > 0
  return (
    <span className={`font-mono font-semibold ${positive ? 'text-success-600 dark:text-success-500' : 'text-ink-900'}`}>
      {formatSignedAmount(entry.amount, entry.currency)}
    </span>
  )
}

// A transfer between two of the caller's own accounts produces both a TRANSFER_OUT and a
// TRANSFER_IN row sharing the same `reference` (the one Transfer row) — type+reference is the
// real unique key, not reference alone.
type StatementRow = BankStatementEntry & { key: string }

export function BankStatementPage() {
  const user = useAuth()
  const { data: statement, isLoading } = useBankStatement(user?.clientId)
  const rows: StatementRow[] = (statement?.entries ?? []).map((e) => ({ ...e, key: `${e.type}-${e.reference}` }))

  const columns: Column<StatementRow>[] = [
    {
      header: 'Date',
      render: (e) => (
        <span className="whitespace-nowrap text-ink-400">
          {new Date(e.occurredAt).toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' })}
        </span>
      ),
    },
    { header: 'Type', render: (e) => <Badge variant={BADGE_VARIANT[e.type]}>{statementEntryTypeLabel(e.type)}</Badge> },
    { header: 'Description', render: (e) => e.description },
    { header: 'Amount', render: (e) => <AmountCell entry={e} /> },
  ]

  return (
    <div className="flex flex-col gap-10">
      <div>
        <h1 className={pageTitle}>Bank Statement</h1>
        <p className="text-sm text-ink-400">
          Every order, payment, transfer, deposit, withdrawal, conversion, and loan event across all your
          accounts, newest first.
        </p>
      </div>

      <TransactionTable
        rows={rows}
        columns={columns}
        keyField="key"
        emptyMessage={isLoading ? 'Loading…' : 'Nothing on your statement yet.'}
      />
    </div>
  )
}
