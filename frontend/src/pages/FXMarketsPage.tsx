import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { useFxPrices } from '../hooks/useFxPrices'
import type { PriceWithTrend } from '../hooks/useLivePrices'
import { Badge } from '../components/Badge'
import { card } from '../lib/styles'

const trendColor: Record<PriceWithTrend['trend'], string> = {
  up: 'text-success-600',
  down: 'text-error-600',
  flat: 'text-ink-400',
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
      className={`flex flex-col gap-2 rounded-2xl border bg-white p-4 text-left shadow-sm shadow-ink-900/[0.02] transition hover:-translate-y-0.5 hover:shadow-md ${
        selected ? 'border-primary-400 ring-1 ring-primary-400' : 'border-ink-100'
      }`}
    >
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-ink-900">{price.symbol}</span>
        <span className={`text-sm font-medium ${trendColor[price.trend]}`}>{trendArrow[price.trend]}</span>
      </div>
      <span className="font-mono text-2xl font-semibold text-ink-900">{price.price.toFixed(4)}</span>
      {price.history.length > 1 && (
        <ResponsiveContainer width="100%" height={40}>
          <LineChart data={price.history}>
            <Line
              type="monotone"
              dataKey="price"
              stroke={price.trend === 'down' ? '#dc2626' : '#10b981'}
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
    <div className={card}>
      <div className="mb-4 flex items-baseline justify-between">
        <div>
          <h2 className="text-lg font-semibold text-ink-900">{price.symbol}</h2>
          <p className="font-mono text-3xl font-bold text-ink-900">{price.price.toFixed(4)}</p>
        </div>
        <span className={`text-lg font-medium ${trendColor[price.trend]}`}>
          {trendArrow[price.trend]} {price.trend === 'flat' ? 'unchanged' : price.trend}
        </span>
      </div>
      {price.history.length > 1 ? (
        <ResponsiveContainer width="100%" height={220}>
          <LineChart data={price.history} margin={{ top: 5, right: 10, left: 0, bottom: 5 }}>
            <XAxis dataKey="time" tick={{ fontSize: 11, fill: '#6b7690' }} minTickGap={30} axisLine={false} tickLine={false} />
            <YAxis
              domain={['auto', 'auto']}
              tick={{ fontSize: 11, fill: '#6b7690' }}
              width={70}
              tickFormatter={(v: number) => v.toFixed(4)}
              axisLine={false}
              tickLine={false}
            />
            <Tooltip
              formatter={(value) => (typeof value === 'number' ? value.toFixed(4) : value)}
              contentStyle={{ borderRadius: 12, border: '1px solid #e6e9f0' }}
            />
            <Line type="monotone" dataKey="price" stroke="#0284c7" strokeWidth={2} dot={false} isAnimationActive={false} />
          </LineChart>
        </ResponsiveContainer>
      ) : (
        <p className="py-16 text-center text-sm text-ink-400">Collecting ticks — the chart fills in as prices come in.</p>
      )}
      <p className="mt-4 text-sm text-ink-400">
        Want to actually convert?{' '}
        <Link to="/accounts" className="font-semibold text-primary-600 hover:text-primary-700">
          Head to Accounts
        </Link>{' '}
        — conversion uses this exact rate.
      </p>
    </div>
  )
}

export function FXMarketsPage() {
  const { prices, isLoading, isError, dataUpdatedAt } = useFxPrices()
  const [selectedPair, setSelectedPair] = useState<string | null>(null)

  const selected = prices.find((p) => p.symbol === selectedPair) ?? prices[0]

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h1 className="text-2xl font-semibold text-ink-900">FX Markets</h1>
          <p className="text-sm text-ink-400">Live prices — updated every 3s, last tick {formatUpdatedAgo(dataUpdatedAt)}.</p>
        </div>
        <Badge variant="warning">No real markets</Badge>
      </div>

      {isError && <p className="text-sm text-error-600">Couldn't reach the FX price feed. Retrying…</p>}
      {isLoading && <p className="text-sm text-ink-400">Loading live rates…</p>}

      {prices.length > 0 && (
        <>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {prices.map((price) => (
              <PairCard
                key={price.symbol}
                price={price}
                selected={price.symbol === selected?.symbol}
                onSelect={() => setSelectedPair(price.symbol)}
              />
            ))}
          </div>

          {selected && <DetailChart price={selected} />}
        </>
      )}
    </div>
  )
}
