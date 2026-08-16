// A generic list table, reused for accounts (AccountsPage), loans (LoansPage), orders and
// positions (TradingPage), and the unified deposit/transfer/loan/order feed (BankStatementPage,
// GET /v1/statement — see docs/ACCOUNTS.md §8). Not a fake "recent transactions" feed; it's
// whatever real, queryable rows the page hands it.
export interface Column<T> {
  header: string
  render: (row: T) => React.ReactNode
}

export function TransactionTable<T>({
  rows,
  columns,
  keyField,
  emptyMessage = 'Nothing here yet.',
}: {
  rows: T[]
  columns: Column<T>[]
  keyField: keyof T
  emptyMessage?: string
}) {
  if (rows.length === 0) {
    return (
      <div className="rounded-xl border border-dashed border-ink-100 py-10 text-center text-sm text-ink-400">
        {emptyMessage}
      </div>
    )
  }

  return (
    <div className="overflow-hidden rounded-xl border border-ink-100 bg-surface">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-ink-100">
              {columns.map((col) => (
                <th key={col.header} className="px-5 py-3 text-left text-xs font-semibold uppercase tracking-wide text-ink-400">
                  {col.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-ink-100">
            {rows.map((row) => (
              <tr key={String(row[keyField])} className="transition hover:bg-canvas">
                {columns.map((col) => (
                  <td key={col.header} className="px-5 py-3.5 text-ink-700">
                    {col.render(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
