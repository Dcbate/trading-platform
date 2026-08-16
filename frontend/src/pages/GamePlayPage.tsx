import { useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import toast from 'react-hot-toast'
import { Line, LineChart, ReferenceDot, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { ArrowLeft, Landmark, PartyPopper, SkullIcon, Sparkles, TimerOff } from 'lucide-react'
import { apiErrorMessage } from '../api/client'
import {
  useGameDebrief,
  useGameDifficulties,
  useGameMarket,
  useGameSession,
  useGameTrades,
  useStartGame,
  useTakeGameLoan,
  useRepayGameLoan,
  usePlaceGameTrade,
} from '../hooks/useGame'
import { formatCountdown, formatMoney, formatQuantity, sanitizeWholeNumberInput } from '../lib/format'
import { btnDanger, btnGhost, btnGhostSm, btnPrimary, btnSuccess, card, input, inputSm, label, listSection, sectionTitle } from '../lib/styles'
import type {
  GameLoanResponse,
  GamePriceResponse,
  GameSessionResponse,
  GameSymbolPerformanceResponse,
  GameTradeResponse,
  OrderSide,
} from '../types/api'

interface PricePoint {
  time: string
  price: number
  // Epoch ms when this point was polled — used to match a trade onto the nearest history point.
  // `time` alone isn't enough for that: it's a local-time-of-day display string, while a trade's
  // `createdAt` is a server UTC instant, so comparing them directly drifts by the browser's
  // timezone offset (e.g. every marker would land ~1 hour off in BST) and can even wrap around
  // midnight incorrectly. Comparing real epoch instants sidesteps both problems.
  t: number
}

function GoalProgress({ session }: { session: GameSessionResponse }) {
  const progressPercent = Math.max(0, Math.min(100, (session.netWorth / session.goalAmount) * 100))
  const positive = session.netWorth >= 0
  return (
    <div className={card}>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div>
          <p className="text-xs font-semibold uppercase tracking-wide text-ink-400">Net worth</p>
          <p className={`font-mono text-3xl font-bold ${positive ? 'text-ink-900' : 'text-error-600'}`}>{formatMoney(session.netWorth, 'GBP')}</p>
          <p className="mt-0.5 text-xs text-ink-400">Cash + shares held − loans owed</p>
        </div>
        <p className="text-sm text-ink-400">
          Goal <span className="font-mono font-semibold text-ink-700">{formatMoney(session.goalAmount, 'GBP')}</span>
        </p>
      </div>
      <div className="mt-3 h-2.5 w-full overflow-hidden rounded-full bg-canvas">
        <div className="h-full rounded-full bg-gradient-to-r from-primary-600 to-secondary-600 transition-all" style={{ width: `${progressPercent}%` }} />
      </div>
      <div className="mt-4 flex items-center gap-2 border-t border-ink-100 pt-3">
        <span className="text-xs font-semibold uppercase tracking-wide text-ink-400">Cash to spend</span>
        <span className="font-mono text-sm font-semibold text-ink-900">{formatMoney(session.cash, 'GBP')}</span>
        <span className="text-xs text-ink-400">— not the same as net worth above</span>
      </div>
    </div>
  )
}

function MarketRow({
  price,
  trend,
  selected,
  onSelect,
}: {
  price: GamePriceResponse
  trend: 'up' | 'down' | 'flat'
  selected: boolean
  onSelect: () => void
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className={`flex items-center justify-between gap-3 rounded-lg border px-3 py-2.5 text-left transition ${
        selected ? 'border-primary-400 ring-1 ring-primary-400' : 'border-ink-100 hover:border-ink-400'
      }`}
    >
      <span className="text-sm font-semibold text-ink-900">{price.symbol}</span>
      <span className={`font-mono text-sm font-semibold ${trend === 'up' ? 'text-success-600' : trend === 'down' ? 'text-error-600' : 'text-ink-700'}`}>
        {price.symbol.includes('/') ? price.price.toFixed(4) : formatMoney(price.price, 'USD')}
      </span>
    </button>
  )
}

// Trades are timestamped by the server (ISO instant); history points carry both a display string
// and the real epoch ms they were polled at (see the market-tracking effect below). Matching a
// trade onto the chart means finding whichever history point's epoch instant is closest to the
// trade's, then marking the chart at that point's x position (recharts positions a ReferenceDot
// on a category axis by matching the display string against the axis's own categories, not by
// interpolating between them).
function nearestHistoryTime(tradeCreatedAt: string, history: PricePoint[]): string | undefined {
  if (history.length === 0) return undefined
  const tradeMs = new Date(tradeCreatedAt).getTime()
  let closest = history[0]
  let closestDiff = Infinity
  for (const point of history) {
    const diff = Math.abs(point.t - tradeMs)
    if (diff < closestDiff) {
      closestDiff = diff
      closest = point
    }
  }
  return closest.time
}

function DetailChart({
  symbol,
  price,
  trend,
  history,
  trades,
}: {
  symbol: string
  price: number | undefined
  trend: 'up' | 'down' | 'flat'
  history: PricePoint[]
  trades: GameTradeResponse[]
}) {
  const isFx = symbol.includes('/')
  const formatted = price === undefined ? '—' : isFx ? price.toFixed(4) : formatMoney(price, 'USD')
  const markers = trades
    .map((t) => ({ side: t.side, price: t.price, time: nearestHistoryTime(t.createdAt, history) }))
    .filter((m): m is { side: OrderSide; price: number; time: string } => m.time !== undefined)
  return (
    <div className={card}>
      <div className="mb-3 flex items-baseline justify-between">
        <div>
          <h2 className="text-sm font-semibold text-ink-400">{symbol}</h2>
          <p className="font-mono text-2xl font-bold text-ink-900">{formatted}</p>
        </div>
        <span className={`text-sm font-medium ${trend === 'up' ? 'text-success-600' : trend === 'down' ? 'text-error-600' : 'text-ink-400'}`}>
          {trend === 'up' ? '▲ trending up' : trend === 'down' ? '▼ trending down' : '— flat'}
        </span>
      </div>
      {history.length > 1 ? (
        <ResponsiveContainer width="100%" height={160}>
          <LineChart data={history} margin={{ top: 5, right: 10, left: 0, bottom: 5 }}>
            <XAxis dataKey="time" tick={{ fontSize: 10, fill: 'var(--color-ink-400)' }} minTickGap={40} axisLine={false} tickLine={false} />
            <YAxis
              domain={['auto', 'auto']}
              tick={{ fontSize: 10, fill: 'var(--color-ink-400)' }}
              width={isFx ? 55 : 45}
              tickFormatter={(v: number) => (isFx ? v.toFixed(4) : v.toFixed(0))}
              axisLine={false}
              tickLine={false}
            />
            <Tooltip
              formatter={(value) => (typeof value === 'number' ? (isFx ? value.toFixed(4) : formatMoney(value, 'USD')) : value)}
              contentStyle={{ borderRadius: 8, border: '1px solid var(--color-ink-100)', background: 'var(--color-surface)' }}
            />
            <Line
              type="monotone"
              dataKey="price"
              stroke={trend === 'down' ? '#dc2626' : '#10b981'}
              strokeWidth={2}
              dot={false}
              isAnimationActive={false}
            />
            {markers.map((m, i) => (
              <ReferenceDot
                key={i}
                x={m.time}
                y={m.price}
                r={5}
                fill={m.side === 'BUY' ? '#10b981' : '#dc2626'}
                stroke="white"
                strokeWidth={1.5}
              />
            ))}
          </LineChart>
        </ResponsiveContainer>
      ) : (
        <p className="py-8 text-center text-xs text-ink-400">Collecting ticks — the chart fills in as prices come in.</p>
      )}
      {markers.length > 0 && (
        <div className="mt-2 flex items-center gap-4 text-xs text-ink-400">
          <span className="flex items-center gap-1.5">
            <span className="h-2.5 w-2.5 rounded-full border border-white bg-success-600" /> Buy
          </span>
          <span className="flex items-center gap-1.5">
            <span className="h-2.5 w-2.5 rounded-full border border-white bg-error-600" /> Sell
          </span>
        </div>
      )}
    </div>
  )
}

function LoanPanel({ sessionId, ratePercent }: { sessionId: string; ratePercent: number }) {
  const [amount, setAmount] = useState('')
  const [open, setOpen] = useState(false)
  const takeLoan = useTakeGameLoan(sessionId)

  if (!open) {
    return (
      <button type="button" onClick={() => setOpen(true)} className={btnGhost}>
        <Landmark size={15} strokeWidth={2} />
        Take a loan
      </button>
    )
  }

  return (
    <div className={`${card} flex flex-wrap items-end gap-3`}>
      <div className="flex flex-col gap-1.5">
        <label className={label}>Loan amount</label>
        <input
          type="number"
          min="1"
          step="1"
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          className={`w-32 ${input}`}
          autoFocus
        />
      </div>
      <p className="text-xs text-ink-400">
        {ratePercent}% of the outstanding balance accrues as interest every minute it's unpaid — repay it any time from "My loans" below.
      </p>
      <div className="flex gap-2">
        <button
          type="button"
          disabled={takeLoan.isPending || !amount}
          onClick={() =>
            takeLoan.mutate(Number(amount), {
              onSuccess: () => {
                toast.success(`Borrowed ${formatMoney(Number(amount), 'GBP')}`)
                setAmount('')
                setOpen(false)
              },
              onError: (error) => toast.error(apiErrorMessage(error)),
            })
          }
          className={btnPrimary}
        >
          {takeLoan.isPending ? 'Borrowing…' : 'Confirm loan'}
        </button>
        <button type="button" onClick={() => setOpen(false)} className={btnGhostSm}>
          Cancel
        </button>
      </div>
    </div>
  )
}

function LoanRepayRow({ sessionId, loan }: { sessionId: string; loan: GameLoanResponse }) {
  const [amount, setAmount] = useState('')
  const repay = useRepayGameLoan(sessionId)
  const totalOwed = loan.outstandingPrincipal + loan.interestOwed

  return (
    <div className={`${card} flex flex-wrap items-center justify-between gap-3`}>
      <div>
        <p className="font-mono text-sm font-semibold text-ink-900">{formatMoney(totalOwed, 'GBP')} owed</p>
        <p className="text-xs text-ink-400">
          {formatMoney(loan.outstandingPrincipal, 'GBP')} principal + {formatMoney(loan.interestOwed, 'GBP')} interest ·{' '}
          {loan.rateAnnualPercent}% of principal / min
        </p>
      </div>
      <div className="flex items-center gap-1.5">
        <input
          type="number"
          min="0.01"
          step="0.01"
          value={amount}
          onChange={(e) => setAmount(e.target.value)}
          placeholder="amount"
          className={`w-24 ${inputSm}`}
        />
        <button
          type="button"
          disabled={repay.isPending || !amount}
          onClick={() =>
            repay.mutate(
              { gameLoanId: loan.gameLoanId, amount: Number(amount) },
              {
                onSuccess: () => {
                  toast.success('Repayment applied')
                  setAmount('')
                },
                onError: (error) => toast.error(apiErrorMessage(error)),
              },
            )
          }
          className={btnGhostSm}
        >
          Repay
        </button>
        <button
          type="button"
          disabled={repay.isPending}
          onClick={() =>
            // Repaying more than what's owed is fine — the server clamps to the real total, so
            // sending a large amount reliably clears the loan even as interest ticks up mid-click.
            repay.mutate(
              { gameLoanId: loan.gameLoanId, amount: totalOwed * 2 + 1 },
              {
                onSuccess: () => toast.success('Loan paid off'),
                onError: (error) => toast.error(apiErrorMessage(error)),
              },
            )
          }
          className={btnGhostSm}
        >
          Pay off
        </button>
      </div>
    </div>
  )
}

function MyLoans({ sessionId, loans }: { sessionId: string; loans: GameLoanResponse[] }) {
  if (loans.length === 0) {
    return null
  }
  return (
    <div className="flex flex-col gap-2">
      <h2 className={sectionTitle}>My loans</h2>
      {loans.map((loan) => (
        <LoanRepayRow key={loan.gameLoanId} sessionId={sessionId} loan={loan} />
      ))}
    </div>
  )
}

function TradeForm({
  sessionId,
  symbol,
  price,
  cash,
  heldQuantity,
}: {
  sessionId: string
  symbol: string | null
  price: number | undefined
  cash: number
  heldQuantity: number
}) {
  const [side, setSide] = useState<OrderSide>('BUY')
  const [quantity, setQuantity] = useState('')
  const placeTrade = usePlaceGameTrade(sessionId)

  const estimated = price && quantity ? price * Number(quantity) : undefined
  const maxQuantity = side === 'BUY' ? (price ? Math.floor(cash / price) : 0) : Math.floor(heldQuantity)

  if (!symbol) {
    return <p className="text-sm text-ink-400">Pick a symbol from the market list to trade it.</p>
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!symbol || !quantity) return
    placeTrade.mutate(
      { symbol, side, quantity: Math.round(Number(quantity)) },
      {
        onSuccess: (session) => {
          toast.success(`${side === 'BUY' ? 'Bought' : 'Sold'} ${quantity} ${symbol}`)
          setQuantity('')
          if (session.status !== 'IN_PROGRESS') {
            toast(session.status === 'WON' ? 'You reached the goal!' : 'Game over', { icon: session.status === 'WON' ? '🎉' : '⏱️' })
          }
        },
        onError: (error) => toast.error(apiErrorMessage(error)),
      },
    )
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-wrap items-end gap-3">
      <div className="flex flex-col gap-1.5">
        <label className={label}>Symbol</label>
        <p className="rounded-lg border border-ink-100 bg-canvas px-3.5 py-2.5 text-sm font-semibold text-ink-900">{symbol}</p>
      </div>
      <div className="flex flex-col gap-1.5">
        <label className={label}>Side</label>
        <div className="flex overflow-hidden rounded-lg border border-ink-100">
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
        <div className="flex items-center gap-1.5">
          <input
            type="number"
            min="1"
            step="1"
            value={quantity}
            onChange={(e) => setQuantity(sanitizeWholeNumberInput(e.target.value))}
            className={`w-24 ${inputSm}`}
            required
          />
          <button
            type="button"
            onClick={() => setQuantity(String(maxQuantity))}
            disabled={maxQuantity <= 0}
            className={btnGhostSm}
            title={side === 'BUY' ? 'Fill with the most you can afford' : 'Fill with everything you hold'}
          >
            Max
          </button>
        </div>
      </div>
      <div className="flex flex-col gap-0.5 text-xs text-ink-400">
        <span>Cash: {formatMoney(cash, 'GBP')}</span>
        {side === 'SELL' && <span>Held: {formatQuantity(heldQuantity)}</span>}
        {estimated !== undefined && (
          <span className="font-mono font-semibold text-ink-900">
            {side === 'BUY' ? 'Cost' : 'Proceeds'} ≈ {formatMoney(estimated, 'USD')}
          </span>
        )}
      </div>
      <button type="submit" disabled={placeTrade.isPending || !quantity || !price} className={side === 'BUY' ? btnSuccess : btnDanger}>
        {placeTrade.isPending ? 'Submitting…' : `${side === 'BUY' ? 'Buy' : 'Sell'} ${symbol}`}
      </button>
    </form>
  )
}

// One row per symbol traded or still held, sorted best-to-worst — bars scale against whichever
// symbol moved the most so a +£40 and a +£4,000 session don't render as visually identical.
function SymbolPerformanceChart({ symbolPerformance }: { symbolPerformance: GameSymbolPerformanceResponse[] }) {
  if (symbolPerformance.length === 0) {
    return null
  }
  const maxAbs = Math.max(...symbolPerformance.map((p) => Math.abs(p.totalPnl)), 1)
  return (
    <div className={card}>
      <h2 className={sectionTitle}>Biggest gains and losses</h2>
      <div className="mt-3 flex flex-col gap-2.5">
        {symbolPerformance.map((p) => {
          const positive = p.totalPnl >= 0
          const widthPercent = Math.max(4, (Math.abs(p.totalPnl) / maxAbs) * 100)
          return (
            <div key={p.symbol} className="flex items-center gap-3">
              <span className="w-16 shrink-0 font-mono text-xs font-semibold text-ink-700">{p.symbol}</span>
              <div className="h-5 flex-1 overflow-hidden rounded bg-canvas">
                <div className={`h-full rounded ${positive ? 'bg-success-500' : 'bg-error-500'}`} style={{ width: `${widthPercent}%` }} />
              </div>
              <span className={`w-24 shrink-0 text-right font-mono text-xs font-semibold ${positive ? 'text-success-600' : 'text-error-600'}`}>
                {formatMoney(p.totalPnl, 'USD')}
              </span>
              <span className="w-28 shrink-0 text-right text-xs text-ink-400">
                {p.quantityHeld > 0 ? `${formatQuantity(p.quantityHeld)} still held` : 'closed out'}
              </span>
            </div>
          )
        })}
      </div>
    </div>
  )
}

function EndScreen({ session, sessionId }: { session: GameSessionResponse; sessionId: string }) {
  const navigate = useNavigate()
  const startGame = useStartGame()
  const { data: trades } = useGameTrades(sessionId)
  const { data: debrief, isLoading: debriefLoading } = useGameDebrief(sessionId)

  const won = session.status === 'WON'
  const bankrupt = session.status === 'LOST_BANKRUPT'
  const closedTrades = (trades ?? []).filter((t) => t.realizedPnl !== null)
  const winningTrades = closedTrades.filter((t) => (t.realizedPnl ?? 0) > 0)
  const bestTrade = closedTrades.reduce<number | null>((best, t) => (best === null || (t.realizedPnl ?? 0) > best ? (t.realizedPnl ?? 0) : best), null);

  const largestPosition = session.positions.reduce<number>((max, p) => Math.max(max, p.marketValue), 0)
  const concentrationRisk = session.netWorth > 0 && largestPosition / session.netWorth > 0.5
  const loanDrag = bankrupt && session.loans.length > 0

  function playAgain() {
    startGame.mutate(
      { clientId: session.clientId, difficulty: session.difficulty },
      {
        onSuccess: (newSession) => navigate(`/game/play/${newSession.sessionId}`),
        onError: (error) => toast.error(apiErrorMessage(error)),
      },
    )
  }

  return (
    <div className="flex flex-col gap-6">
      <div className={`${card} flex flex-col items-center gap-3 py-10 text-center`}>
        <span className={`flex h-14 w-14 items-center justify-center rounded-full ${won ? 'bg-success-50 text-success-600 dark:bg-success-500/15 dark:text-success-500' : 'bg-error-50 text-error-600 dark:bg-error-500/15 dark:text-error-500'}`}>
          {won ? <PartyPopper size={28} strokeWidth={2} /> : bankrupt ? <SkullIcon size={28} strokeWidth={2} /> : <TimerOff size={28} strokeWidth={2} />}
        </span>
        <h1 className="text-2xl font-bold text-ink-900">{won ? 'You reached the goal!' : bankrupt ? 'Bankrupt' : "Time's up"}</h1>
        <p className="font-mono text-4xl font-bold text-ink-900">{formatMoney(session.netWorth, 'GBP')}</p>
        <p className="text-sm text-ink-400">
          Goal was {formatMoney(session.goalAmount, 'GBP')}
          {!won && session.goalAmount > 0 && <> — {Math.max(0, (session.netWorth / session.goalAmount) * 100).toFixed(0)}% of the way there</>}
        </p>
      </div>

      <div className={card}>
        <div className="flex items-center gap-2">
          <h2 className={sectionTitle}>Debrief</h2>
          {debrief?.aiGenerated && (
            <span className="flex items-center gap-1 rounded-full bg-primary-50 px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-primary-600 dark:bg-primary-500/15 dark:text-primary-400">
              <Sparkles size={10} strokeWidth={2.5} />
              AI-written
            </span>
          )}
        </div>
        <p className="mt-2 text-sm text-ink-600">
          {debriefLoading ? 'Putting together your debrief…' : (debrief?.summary ?? "Couldn't generate a debrief for this session.")}
        </p>
      </div>

      {debrief && <SymbolPerformanceChart symbolPerformance={debrief.symbolPerformance} />}

      {closedTrades.length > 0 && (
        <div className={card}>
          <h2 className={sectionTitle}>Trade summary</h2>
          <div className="mt-2 flex flex-wrap gap-x-8 gap-y-2 text-sm">
            <span>
              Closed trades: <span className="font-mono font-semibold text-ink-900">{closedTrades.length}</span>
            </span>
            <span>
              Win rate:{' '}
              <span className="font-mono font-semibold text-ink-900">{((winningTrades.length / closedTrades.length) * 100).toFixed(0)}%</span>
            </span>
            {bestTrade !== null && (
              <span>
                Best trade: <span className="font-mono font-semibold text-success-600">{formatMoney(bestTrade, 'USD')}</span>
              </span>
            )}
          </div>
        </div>
      )}

      {(concentrationRisk || loanDrag) && (
        <div className={card}>
          <h2 className={sectionTitle}>What happened</h2>
          <ul className="mt-2 flex flex-col gap-2 text-sm text-ink-600">
            {concentrationRisk && <li>More than half your net worth was in a single position — a bad move there had nowhere to be cushioned.</li>}
            {loanDrag && <li>Loan interest kept accruing the whole time it was outstanding — it added up faster than the trades covered it.</li>}
          </ul>
        </div>
      )}

      <div className="flex gap-3">
        <button type="button" onClick={playAgain} disabled={startGame.isPending} className={btnPrimary}>
          {startGame.isPending ? 'Starting…' : 'Play again'}
        </button>
        <button type="button" onClick={() => navigate('/game')} className={btnGhost}>
          Back to Game Mode
        </button>
      </div>
    </div>
  )
}

export function GamePlayPage() {
  const { sessionId } = useParams<{ sessionId: string }>()
  const navigate = useNavigate()
  const { data: session } = useGameSession(sessionId)
  const { data: difficulties } = useGameDifficulties()
  const { data: market } = useGameMarket(session?.difficulty)
  const { data: trades } = useGameTrades(sessionId)
  const [selectedSymbol, setSelectedSymbol] = useState<string | null>(null)
  const [timeRemaining, setTimeRemaining] = useState<number | null>(null)

  const previousPrices = useRef<Map<string, number>>(new Map())
  const [trends, setTrends] = useState<Map<string, 'up' | 'down' | 'flat'>>(new Map())
  const [history, setHistory] = useState<Map<string, PricePoint[]>>(new Map())

  useEffect(() => {
    if (session) setTimeRemaining(session.timeRemainingSeconds)
  }, [session?.timeRemainingSeconds]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (session?.status !== 'IN_PROGRESS') return
    const interval = setInterval(() => setTimeRemaining((t) => (t !== null ? Math.max(0, t - 1) : t)), 1000)
    return () => clearInterval(interval)
  }, [session?.status])

  useEffect(() => {
    if (!market) return
    const nowMs = Date.now()
    const now = new Date(nowMs).toLocaleTimeString('en-GB', { hour12: false })
    const nextTrends = new Map(trends)
    const nextHistory = new Map(history)
    for (const { symbol, price } of market) {
      const prev = previousPrices.current.get(symbol)
      nextTrends.set(symbol, prev === undefined || price === prev ? 'flat' : price > prev ? 'up' : 'down')
      previousPrices.current.set(symbol, price)

      const existing = nextHistory.get(symbol) ?? []
      nextHistory.set(symbol, [...existing, { time: now, price, t: nowMs }].slice(-60))
    }
    setTrends(nextTrends)
    setHistory(nextHistory)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [market])

  if (!session || !sessionId) {
    return <p className="text-sm text-ink-400">Loading your session…</p>
  }

  if (session.status !== 'IN_PROGRESS') {
    return <EndScreen session={session} sessionId={sessionId} />
  }

  const difficultyLabel = difficulties?.find((d) => d.code === session.difficulty)?.displayName ?? session.difficulty
  const selectedPrice = market?.find((p) => p.symbol === selectedSymbol)?.price

  return (
    <div className="flex flex-col gap-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <button type="button" onClick={() => navigate('/game')} className="flex items-center gap-1.5 text-sm font-medium text-ink-400 hover:text-ink-900">
          <ArrowLeft size={15} strokeWidth={2} />
          {difficultyLabel}
        </button>
        <div className="flex items-center gap-2 rounded-xl bg-ink-950 px-4 py-2 text-white">
          <span className="text-xs font-semibold uppercase tracking-wide text-ink-400">Time left</span>
          <span className="font-mono text-lg font-bold">{formatCountdown(timeRemaining ?? session.timeRemainingSeconds)}</span>
        </div>
      </div>

      <GoalProgress session={session} />

      <div className="flex flex-wrap items-center gap-3">
        <LoanPanel sessionId={sessionId} ratePercent={difficulties?.find((d) => d.code === session.difficulty)?.loanRateAnnualPercent ?? 0} />
      </div>

      <MyLoans sessionId={sessionId} loans={session.loans} />

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <div>
          <h2 className={`${sectionTitle} mb-2`}>Live market</h2>
          <p className="mb-2 text-xs text-ink-400">Click a symbol to chart it and trade it.</p>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
            {market?.map((p) => (
              <MarketRow key={p.symbol} price={p} trend={trends.get(p.symbol) ?? 'flat'} selected={p.symbol === selectedSymbol} onSelect={() => setSelectedSymbol(p.symbol)} />
            ))}
          </div>
        </div>
        <div className="flex flex-col gap-4">
          {selectedSymbol && (
            <DetailChart
              symbol={selectedSymbol}
              price={selectedPrice}
              trend={trends.get(selectedSymbol) ?? 'flat'}
              history={history.get(selectedSymbol) ?? []}
              trades={(trades ?? []).filter((t) => t.symbol === selectedSymbol)}
            />
          )}
          <div className={card}>
            <h2 className={`${sectionTitle} mb-3`}>Trade</h2>
            <TradeForm
              sessionId={sessionId}
              symbol={selectedSymbol}
              price={selectedPrice}
              cash={session.cash}
              heldQuantity={session.positions.find((p) => p.symbol === selectedSymbol)?.quantity ?? 0}
            />
          </div>
        </div>
      </div>

      <div>
        <h2 className={`${sectionTitle} mb-2`}>My positions</h2>
        {session.positions.length === 0 ? (
          <p className="text-sm text-ink-400">No positions yet — buy something from the market above.</p>
        ) : (
          <div className={listSection}>
            {session.positions.map((p) => (
              <div key={p.symbol} className="flex items-center justify-between py-3 text-sm">
                <span className="font-semibold text-ink-900">{p.symbol}</span>
                <span className="text-ink-400">{formatQuantity(p.quantity)} @ {formatMoney(p.avgCost, 'USD')}</span>
                <span className={`font-mono font-semibold ${p.unrealizedPnl >= 0 ? 'text-success-600' : 'text-error-600'}`}>
                  {p.unrealizedPnl >= 0 ? '▲' : '▼'} {formatMoney(Math.abs(p.unrealizedPnl), 'USD')}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      {session.loans.length > 0 && (
        <div>
          <h2 className={`${sectionTitle} mb-2`}>My loans</h2>
          <div className={listSection}>
            {session.loans.map((l) => (
              <div key={l.gameLoanId} className="flex items-center justify-between py-3 text-sm">
                <span className="text-ink-700">{formatMoney(l.principal, 'GBP')} at {l.rateAnnualPercent}% APR</span>
                <span className="font-mono font-semibold text-error-600">+{formatMoney(l.interestOwed, 'GBP')} owed</span>
              </div>
            ))}
          </div>
        </div>
      )}

      <div>
        <h2 className={`${sectionTitle} mb-2`}>Recent trades</h2>
        {trades && trades.length > 0 ? (
          <div className={listSection}>
            {trades.slice(0, 8).map((t) => (
              <div key={t.tradeId} className="flex items-center justify-between py-3 text-sm">
                <span className={`font-semibold ${t.side === 'BUY' ? 'text-success-600' : 'text-error-600'}`}>{t.side}</span>
                <span className="text-ink-700">{formatQuantity(t.quantity)} {t.symbol} @ {formatMoney(t.price, 'USD')}</span>
                <span className="text-ink-400">{new Date(t.createdAt).toLocaleTimeString()}</span>
              </div>
            ))}
          </div>
        ) : (
          <p className="text-sm text-ink-400">No trades yet.</p>
        )}
      </div>
    </div>
  )
}
