import { useEffect, useState } from 'react'
import toast from 'react-hot-toast'
import { apiErrorMessage } from '../api/client'
import { useAuth } from '../hooks/useAuth'
import { useAccounts } from '../hooks/useAccounts'
import { useStockPrices } from '../hooks/useStockPrices'
import { useOrders, useSubmitOrder } from '../hooks/useOrders'
import { usePositions } from '../hooks/usePositions'
import { TransactionTable, type Column } from '../components/TransactionTable'
import { Badge } from '../components/Badge'
import { btnSuccess, btnDanger, card, input, label, pageTitle, sectionTitle } from '../lib/styles'
import { accountTypeLabel, formatMoney, formatQuantity, sanitizeWholeNumberInput } from '../lib/format'
import type { OrderResponse, OrderSide, PositionResponse } from '../types/api'

// US-listed shares trade in USD everywhere, including for a UK client — this isn't the account's
// own currency, so it stays fixed regardless of which currency the funding account holds.
const usd = (amount: number) => formatMoney(amount, 'USD')

function formatUpdatedAgo(updatedAt: number) {
  if (!updatedAt) return '—'
  const seconds = Math.max(0, Math.round((Date.now() - updatedAt) / 1000))
  return seconds <= 1 ? 'just now' : `${seconds}s ago`
}

function OrderForm({ accountId, symbols, priceFor }: { accountId: string; symbols: string[]; priceFor: (symbol: string) => number | undefined }) {
  const user = useAuth()
  const submitOrder = useSubmitOrder()
  const [symbol, setSymbol] = useState(symbols[0] ?? '')
  const [side, setSide] = useState<OrderSide>('BUY')
  const [quantity, setQuantity] = useState('')

  useEffect(() => {
    if (!symbol && symbols.length > 0) setSymbol(symbols[0])
  }, [symbol, symbols])

  const price = priceFor(symbol)
  const estimatedCost = price && quantity ? price * Number(quantity) : undefined

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!user || !price) return
    submitOrder.mutate(
      { clientId: user.clientId, accountId, currencyPair: symbol, side, quantity: Number(quantity), price },
      {
        onSuccess: (order) => {
          toast.success(`Order submitted — ${order.status}`)
          setQuantity('')
        },
        onError: (error) => toast.error(apiErrorMessage(error)),
      },
    )
  }

  return (
    <form onSubmit={handleSubmit} className={`${card} flex flex-wrap items-end gap-3`}>
      <div className="flex flex-col gap-1.5">
        <label className={label}>Symbol</label>
        <select value={symbol} onChange={(e) => setSymbol(e.target.value)} className={input}>
          {symbols.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </div>
      <div className="flex flex-col gap-1.5">
        <label className={label}>Side</label>
        <div className="flex overflow-hidden rounded-xl border border-ink-100">
          <button
            type="button"
            onClick={() => setSide('BUY')}
            className={`px-4 py-2.5 text-sm font-semibold transition ${side === 'BUY' ? 'bg-success-600 text-white' : 'bg-surface text-ink-600 hover:bg-canvas'}`}
          >
            Buy
          </button>
          <button
            type="button"
            onClick={() => setSide('SELL')}
            className={`px-4 py-2.5 text-sm font-semibold transition ${side === 'SELL' ? 'bg-error-600 text-white' : 'bg-surface text-ink-600 hover:bg-canvas'}`}
          >
            Sell
          </button>
        </div>
      </div>
      <div className="flex flex-col gap-1.5">
        <label className={label}>Shares</label>
        <input
          type="number"
          min="1"
          step="1"
          value={quantity}
          onChange={(e) => setQuantity(sanitizeWholeNumberInput(e.target.value))}
          className={`w-28 ${input}`}
          required
        />
      </div>
      <div className="flex flex-col gap-0.5 text-sm text-ink-400">
        <span>Live price: {price ? usd(price) : '—'}</span>
        {estimatedCost !== undefined && (
          <span className="font-mono font-semibold text-ink-900">
            Est. {side === 'BUY' ? 'cost' : 'proceeds'}: {usd(estimatedCost)}
          </span>
        )}
      </div>
      <button type="submit" disabled={submitOrder.isPending || !price || !quantity} className={side === 'BUY' ? btnSuccess : btnDanger}>
        {submitOrder.isPending ? 'Submitting…' : `${side === 'BUY' ? 'Buy' : 'Sell'} ${symbol}`}
      </button>
    </form>
  )
}

export function TradingPage() {
  const user = useAuth()
  const { data: accounts } = useAccounts(user?.clientId)
  const brokerageAccounts = (accounts ?? []).filter((a) => a.accountType === 'BROKERAGE' && a.status === 'ACTIVE')
  const [selectedAccountId, setSelectedAccountId] = useState<string>('')
  const accountId = selectedAccountId || brokerageAccounts[0]?.accountId || ''

  const { prices, isLoading, isError, dataUpdatedAt } = useStockPrices()
  const { data: orders } = useOrders(user?.clientId)
  const { data: positions } = usePositions(user?.clientId)

  const priceFor = (symbol: string) => prices.find((p) => p.symbol === symbol)?.price

  const orderColumns: Column<OrderResponse>[] = [
    { header: 'Symbol', render: (o) => <span className="font-semibold text-ink-900">{o.currencyPair}</span> },
    { header: 'Side', render: (o) => <Badge variant={o.side === 'BUY' ? 'success' : 'error'}>{o.side}</Badge> },
    { header: 'Shares', render: (o) => formatQuantity(o.quantity) },
    { header: 'Price', render: (o) => <span className="font-mono">{usd(o.price)}</span> },
    {
      header: 'Status',
      render: (o) => (
        <Badge variant={o.status === 'FILLED' ? 'success' : o.status === 'REJECTED' ? 'error' : 'primary'}>{o.status}</Badge>
      ),
    },
    { header: 'Submitted', render: (o) => new Date(o.createdAt).toLocaleTimeString() },
  ]

  const positionColumns: Column<PositionResponse>[] = [
    { header: 'Symbol', render: (p) => <span className="font-semibold text-ink-900">{p.symbol}</span> },
    { header: 'Shares', render: (p) => formatQuantity(p.quantity) },
    { header: 'Avg cost', render: (p) => <span className="font-mono">{usd(p.avgCost)}</span> },
    { header: 'Current price', render: (p) => (priceFor(p.symbol) ? <span className="font-mono">{usd(priceFor(p.symbol)!)}</span> : '—') },
    {
      header: 'Market value',
      render: (p) => {
        const current = priceFor(p.symbol)
        return current ? <span className="font-mono font-semibold">{usd(current * p.quantity)}</span> : '—'
      },
    },
    {
      header: 'Unrealized P&L',
      render: (p) => {
        const current = priceFor(p.symbol)
        if (!current) return '—'
        const pnl = (current - p.avgCost) * p.quantity
        const pnlPercent = p.avgCost > 0 ? (pnl / (p.avgCost * p.quantity)) * 100 : 0
        return (
          <span className={`font-mono font-semibold ${pnl >= 0 ? 'text-success-600' : 'text-error-600'}`}>
            {pnl >= 0 ? '▲' : '▼'} {usd(Math.abs(pnl))} ({pnlPercent.toFixed(1)}%)
          </span>
        )
      },
    },
  ]

  return (
    <div className="flex flex-col gap-8">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h1 className={pageTitle}>Stocks & Shares</h1>
          <p className="text-sm text-ink-400">
            Buy and sell shares — fills settle for real. Last tick {formatUpdatedAgo(dataUpdatedAt)}.
          </p>
        </div>
        <Badge variant="warning">No real markets</Badge>
      </div>

      {isError && <p className="text-sm text-error-600">Couldn't reach the stock price feed. Retrying…</p>}
      {isLoading && <p className="text-sm text-ink-400">Loading live prices…</p>}

      {prices.length > 0 && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-7">
          {prices.map((p) => (
            <div key={p.symbol} className="rounded-lg border border-ink-100 bg-surface p-3">
              <p className="text-xs font-semibold text-ink-400">{p.symbol}</p>
              <p className="font-mono text-lg font-semibold text-ink-900">{usd(p.price)}</p>
              <p className={`text-xs ${p.trend === 'up' ? 'text-success-600' : p.trend === 'down' ? 'text-error-600' : 'text-ink-400'}`}>
                {p.trend === 'up' ? '▲' : p.trend === 'down' ? '▼' : '—'}
              </p>
            </div>
          ))}
        </div>
      )}

      {brokerageAccounts.length === 0 ? (
        <p className="text-sm text-ink-400">
          You need a {accountTypeLabel('BROKERAGE')} account before you can trade — open one from the Accounts page.
        </p>
      ) : (
        <div className="flex flex-col gap-3">
          {brokerageAccounts.length > 1 && (
            <div className="flex flex-col gap-1.5">
              <label className={label}>Trading account</label>
              <select value={accountId} onChange={(e) => setSelectedAccountId(e.target.value)} className={`w-64 ${input}`}>
                {brokerageAccounts.map((a) => (
                  <option key={a.accountId} value={a.accountId}>
                    {a.nickname ?? a.accountId.slice(0, 8)} — {formatMoney(a.balance, a.currency)}
                  </option>
                ))}
              </select>
            </div>
          )}
          <OrderForm accountId={accountId} symbols={prices.map((p) => p.symbol)} priceFor={priceFor} />
        </div>
      )}

      <div>
        <h2 className={`${sectionTitle} mb-2`}>My positions</h2>
        <TransactionTable
          rows={positions ?? []}
          columns={positionColumns}
          keyField="positionId"
          emptyMessage="No positions yet — buy your first share above."
        />
      </div>

      <div>
        <h2 className={`${sectionTitle} mb-2`}>My orders</h2>
        <TransactionTable rows={orders ?? []} columns={orderColumns} keyField="orderId" emptyMessage="No orders yet." />
      </div>
    </div>
  )
}
