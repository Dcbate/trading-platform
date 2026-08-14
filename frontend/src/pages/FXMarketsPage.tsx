import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { useFxPrices, type PriceWithTrend } from '../hooks/useFxPrices'

const trendColor: Record<PriceWithTrend['trend'], string> = {
  up: 'text-success',
  down: 'text-error',
  flat: 'text-slate-400',
}

const trendArrow: Record<PriceWithTrend['trend'], string> = {
  up: '▲',
  down: '▼',
  flat: '—',
}

function formatUpdatedAgo(updatedAt: number) {
  if (!updatedAt) return '—'
  const seconds = Math.max(0, Math.round((Date.now() - updatedAt) / 1000))
  return seconds <= 1 ? 'just now' : `${seconds}s ago`
}

function PairCard({ price, selected, onSelect }: { price: PriceWithTrend; selected: boolean; onSelect: () => void }) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={`flex flex-col gap-2 rounded-lg border bg-white p-4 text-left shadow-sm transition hover:border-primary-300 hover:shadow ${
        selected ? 'border-primary-500 ring-1 ring-primary-500' : 'border-slate-200'
      }`}
    >
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-slate-900">{price.currencyPair}</span>
        <span className={`text-sm font-medium ${trendColor[price.trend]}`}>{trendArrow[price.trend]}</span>
      </div>
      <span className="font-mono text-2xl font-semibold text-slate-900">{price.price.toFixed(4)}</span>
      {price.history.length > 1 && (
        <ResponsiveContainer width="100%" height={40}>
          <LineChart data={price.history}>
            <Line
              type="monotone"
              dataKey="price"
              stroke={price.trend === 'down' ? '#dc2626' : '#16a34a'}
              strokeWidth={1.5}
              dot={false}
              isAnimationActive={false}
            />
          </LineChart>
        </ResponsiveContainer>
      )}
    </button>
  )
}

function DetailChart({ price }: { price: PriceWithTrend }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <div className="mb-4 flex items-baseline justify-between">
        <div>
          <h2 className="text-lg font-semibold text-slate-900">{price.currencyPair}</h2>
          <p className="font-mono text-3xl font-bold text-slate-900">{price.price.toFixed(4)}</p>
        </div>
        <span className={`text-lg font-medium ${trendColor[price.trend]}`}>
          {trendArrow[price.trend]} {price.trend === 'flat' ? 'unchanged' : price.trend}
        </span>
      </div>
      {price.history.length > 1 ? (
        <ResponsiveContainer width="100%" height={220}>
          <LineChart data={price.history} margin={{ top: 5, right: 10, left: 0, bottom: 5 }}>
            <XAxis dataKey="time" tick={{ fontSize: 11 }} minTickGap={30} />
            <YAxis domain={['auto', 'auto']} tick={{ fontSize: 11 }} width={70} tickFormatter={(v: number) => v.toFixed(4)} />
            <Tooltip formatter={(value) => (typeof value === 'number' ? value.toFixed(4) : value)} />
            <Line
              type="monotone"
              dataKey="price"
              stroke="#0284c7"
              strokeWidth={2}
              dot={false}
              isAnimationActive={false}
            />
          </LineChart>
        </ResponsiveContainer>
      ) : (
        <p className="py-16 text-center text-sm text-slate-400">Collecting ticks — the chart fills in as prices come in.</p>
      )}
      <p className="mt-4 text-sm text-slate-500">
        Want to actually convert? Head to{' '}
        <Link to="/accounts" className="font-medium text-primary-700 underline">
          Accounts
        </Link>{' '}
        — conversion uses this exact rate.
      </p>
    </div>
  )
}

export function FXMarketsPage() {
  const { prices, isLoading, isError, dataUpdatedAt } = useFxPrices()
  const [selectedPair, setSelectedPair] = useState<string | null>(null)

  const selected = prices.find((p) => p.currencyPair === selectedPair) ?? prices[0]

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">FX Markets</h1>
        <p className="text-sm text-slate-500">
          Live prices from the bank's own FX desk feed — updated every 3s, last tick {formatUpdatedAgo(dataUpdatedAt)}.
        </p>
        <p className="mt-1 text-xs text-slate-400">
          This is the same synthetic price feed the conversion endpoint uses, not a third-party market data
          subscription — see docs/DESIGN_DECISIONS.md if you're curious how it's generated. I'm not showing
          bid/ask spread, volume, or sentiment here because none of that is actually modeled; a real rate and a
          real trend is more useful than invented numbers that look precise.
        </p>
      </div>

      {isError && <p className="text-sm text-red-600">Couldn't reach the FX price feed. Retrying…</p>}
      {isLoading && <p className="text-sm text-slate-400">Loading live rates…</p>}

      {prices.length > 0 && (
        <>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {prices.map((price) => (
              <PairCard
                key={price.currencyPair}
                price={price}
                selected={price.currencyPair === selected?.currencyPair}
                onSelect={() => setSelectedPair(price.currencyPair)}
              />
            ))}
          </div>

          {selected && <DetailChart price={selected} />}
        </>
      )}
    </div>
  )
}
