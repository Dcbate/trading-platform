import { useEffect, useState } from 'react'
import toast from 'react-hot-toast'
import { apiErrorMessage } from '../api/client'
import { usePlaceGameTrade } from '../hooks/useGame'
import { REACTION_WINDOW_MS } from '../lib/gameConstants'
import { formatMoney, formatQuantity, sanitizeWholeNumberInput } from '../lib/format'
import { btnDanger, btnGhost, btnGhostSm, btnSuccess, card, inputSm, label } from '../lib/styles'
import type { GameMarketEventResponse, GameSessionResponse, OrderSide } from '../types/api'

// The interactive counterpart to the passive news ticker — a market event you'd otherwise have to
// notice yourself now surfaces as a blocking decision with a live countdown matching the server's
// real reaction window (see GameServiceImpl.REACTION_WINDOW_SECONDS), so it's a genuine "act now
// or don't" moment rather than a toast that quietly expires. First modal in this codebase — see
// docs/GAME_MODE.md's actionable-prompts section for why nothing existing was reusable here.
export function GameEventPromptModal({
  event,
  sessionId,
  cash,
  heldQuantity,
  currentPrice,
  onDismiss,
  onTraded,
}: {
  event: GameMarketEventResponse
  sessionId: string
  cash: number
  heldQuantity: number
  currentPrice: number | undefined
  onDismiss: () => void
  onTraded: (session: GameSessionResponse) => void
}) {
  const [side, setSide] = useState<OrderSide>(event.priceUp ? 'BUY' : 'SELL')
  const [quantity, setQuantity] = useState('')
  const [secondsLeft, setSecondsLeft] = useState(() => remainingSeconds(event))
  const placeTrade = usePlaceGameTrade(sessionId)

  useEffect(() => {
    const interval = setInterval(() => {
      const remaining = remainingSeconds(event)
      setSecondsLeft(remaining)
      if (remaining <= 0) onDismiss()
    }, 250)
    return () => clearInterval(interval)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [event])

  const maxQuantity = side === 'BUY' ? (currentPrice ? Math.floor(cash / currentPrice) : 0) : Math.floor(heldQuantity)
  const estimated = currentPrice && quantity ? currentPrice * Number(quantity) : undefined

  function submitTrade(rawQuantity: number) {
    if (rawQuantity <= 0) return
    const roundedQuantity = Math.round(rawQuantity)
    placeTrade.mutate(
      { symbol: event.symbol, side, quantity: roundedQuantity },
      {
        onSuccess: (session) => {
          toast.success(`⚡ Fast reaction — fee waived on ${roundedQuantity} ${event.symbol}`)
          onTraded(session)
          onDismiss()
        },
        onError: (error) => toast.error(apiErrorMessage(error)),
      },
    )
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink-950/60 px-4" role="dialog" aria-modal="true">
      <div className={`${card} w-full max-w-sm`}>
        <div className="flex items-start justify-between gap-3">
          <p className="text-sm font-semibold leading-snug text-ink-900">
            <span className={event.priceUp ? 'text-success-600' : 'text-error-600'}>{event.priceUp ? '▲' : '▼'} {event.symbol}</span> — {event.headline}
          </p>
          <span className="shrink-0 rounded-full bg-canvas px-2 py-0.5 font-mono text-xs font-bold text-ink-700">{secondsLeft}s</span>
        </div>

        <div className="mt-4 flex items-center gap-1.5">
          <button
            type="button"
            onClick={() => setSide('BUY')}
            className={`flex-1 rounded-lg px-3 py-2 text-sm font-semibold transition ${side === 'BUY' ? 'bg-success-600 text-white' : 'border border-ink-100 bg-surface text-ink-600 hover:bg-canvas'}`}
          >
            Buy
          </button>
          <button
            type="button"
            onClick={() => setSide('SELL')}
            className={`flex-1 rounded-lg px-3 py-2 text-sm font-semibold transition ${side === 'SELL' ? 'bg-error-600 text-white' : 'border border-ink-100 bg-surface text-ink-600 hover:bg-canvas'}`}
          >
            Sell
          </button>
        </div>

        <div className="mt-3 flex items-center gap-1.5">
          <input
            type="number"
            min="1"
            step="1"
            value={quantity}
            onChange={(e) => setQuantity(sanitizeWholeNumberInput(e.target.value))}
            placeholder="Quantity"
            className={`flex-1 ${inputSm}`}
          />
          <button type="button" onClick={() => setQuantity(String(maxQuantity))} disabled={maxQuantity <= 0} className={btnGhostSm}>
            Max
          </button>
        </div>

        <div className="mt-2 flex items-center justify-between text-xs text-ink-400">
          <span className={label}>{side === 'SELL' ? `Held: ${formatQuantity(heldQuantity)}` : `Cash: ${formatMoney(cash, 'GBP')}`}</span>
          {estimated !== undefined && <span className="font-mono font-semibold text-ink-900">≈ {formatMoney(estimated, 'GBP')}</span>}
        </div>

        <div className="mt-4 flex gap-2">
          <button
            type="button"
            onClick={() => submitTrade(Number(quantity))}
            disabled={!quantity || Number(quantity) <= 0 || placeTrade.isPending}
            className={`flex-1 ${side === 'BUY' ? btnSuccess : btnDanger}`}
          >
            {placeTrade.isPending ? 'Submitting…' : `${side === 'BUY' ? 'Buy' : 'Sell'} now`}
          </button>
          <button type="button" onClick={onDismiss} className={btnGhost}>
            Dismiss
          </button>
        </div>
      </div>
    </div>
  )
}

function remainingSeconds(event: GameMarketEventResponse): number {
  const elapsedMs = Date.now() - new Date(event.occurredAt).getTime()
  return Math.max(0, Math.ceil((REACTION_WINDOW_MS - elapsedMs) / 1000))
}
