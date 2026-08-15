import { useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { apiClient } from '../api/client'
import type { PriceResponse } from '../types/api'

const POLL_INTERVAL_MS = 3000
const MAX_HISTORY_POINTS = 40

export interface PricePoint {
  time: string
  price: number
}

export interface PriceWithTrend extends PriceResponse {
  trend: 'up' | 'down' | 'flat'
  history: PricePoint[]
}

// Shared by useFxPrices and useStockPrices — same synthetic feed mechanism behind both
// (PriceFeedServiceImpl ticks every 2s server-side for FX pairs and stock symbols alike). Polls
// the real feed and derives trend/history from the actual sequence of prices returned; nothing
// here is invented.
export function useLivePrices(endpoint: string, queryKey: string) {
  const query = useQuery({
    queryKey: [queryKey],
    queryFn: () => apiClient.get<PriceResponse[]>(endpoint).then((r) => r.data),
    refetchInterval: POLL_INTERVAL_MS,
    // A ticker should keep ticking even in a background tab — otherwise React Query pauses
    // polling on visibilitychange and the price looks frozen the moment you switch tabs.
    refetchIntervalInBackground: true,
  })

  const previousPrices = useRef<Map<string, number>>(new Map())
  const [history, setHistory] = useState<Map<string, PricePoint[]>>(new Map())
  const [trends, setTrends] = useState<Map<string, 'up' | 'down' | 'flat'>>(new Map())

  useEffect(() => {
    if (!query.data) return
    const now = new Date().toLocaleTimeString('en-US', { hour12: false })
    const nextTrends = new Map(trends)
    const nextHistory = new Map(history)

    for (const { symbol, price } of query.data) {
      const prev = previousPrices.current.get(symbol)
      nextTrends.set(symbol, prev === undefined || price === prev ? 'flat' : price > prev ? 'up' : 'down')
      previousPrices.current.set(symbol, price)

      const existing = nextHistory.get(symbol) ?? []
      const updated = [...existing, { time: now, price }].slice(-MAX_HISTORY_POINTS)
      nextHistory.set(symbol, updated)
    }

    setTrends(nextTrends)
    setHistory(nextHistory)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query.data])

  const prices: PriceWithTrend[] = (query.data ?? []).map((p) => ({
    ...p,
    trend: trends.get(p.symbol) ?? 'flat',
    history: history.get(p.symbol) ?? [],
  }))

  return { prices, isLoading: query.isLoading, isError: query.isError, dataUpdatedAt: query.dataUpdatedAt }
}
