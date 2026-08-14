// A generic list table, reused for accounts (AccountsPage) and loans (LoansPage). The backend
// doesn't expose a per-account transaction-history endpoint (deposits/transfers/loan repayments
// are Kafka-published for audit, not queryable as a list — see docs/ACCOUNTS.md §8), so this
// deliberately isn't a fake "recent transactions" feed; it's whatever real, queryable rows the
// page hands it.
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
    return <p className="py-8 text-center text-sm text-slate-400">{emptyMessage}</p>
  }

  return (
    <div className="overflow-x-auto rounded-lg border border-slate-200">
      <table className="min-w-full divide-y divide-slate-200 text-sm">
        <thead className="bg-slate-50">
          <tr>
            {columns.map((col) => (
              <th key={col.header} className="px-4 py-2 text-left font-medium text-slate-500">
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100 bg-white">
          {rows.map((row) => (
            <tr key={String(row[keyField])}>
              {columns.map((col) => (
                <td key={col.header} className="px-4 py-2 text-slate-700">
                  {col.render(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
