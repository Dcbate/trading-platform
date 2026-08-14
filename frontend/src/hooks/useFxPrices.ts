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

// Polls the real synthetic price feed (PriceFeedServiceImpl ticks every 2s server-side) and
// derives trend/history from the actual sequence of prices returned — nothing here is invented;
// it's the same cache AccountServiceImpl.convert() reads for real conversions.
export function useFxPrices() {
  const query = useQuery({
    queryKey: ['fx-prices'],
    queryFn: () => apiClient.get<PriceResponse[]>('/v1/fx/prices').then((r) => r.data),
    refetchInterval: POLL_INTERVAL_MS,
  })

  const previousPrices = useRef<Map<string, number>>(new Map())
  const [history, setHistory] = useState<Map<string, PricePoint[]>>(new Map())
  const [trends, setTrends] = useState<Map<string, 'up' | 'down' | 'flat'>>(new Map())

  useEffect(() => {
    if (!query.data) return
    const now = new Date().toLocaleTimeString('en-US', { hour12: false })
    const nextTrends = new Map(trends)
    const nextHistory = new Map(history)

    for (const { currencyPair, price } of query.data) {
      const prev = previousPrices.current.get(currencyPair)
      nextTrends.set(currencyPair, prev === undefined || price === prev ? 'flat' : price > prev ? 'up' : 'down')
      previousPrices.current.set(currencyPair, price)

      const existing = nextHistory.get(currencyPair) ?? []
      const updated = [...existing, { time: now, price }].slice(-MAX_HISTORY_POINTS)
      nextHistory.set(currencyPair, updated)
    }

    setTrends(nextTrends)
    setHistory(nextHistory)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query.data])

  const prices: PriceWithTrend[] = (query.data ?? []).map((p) => ({
    ...p,
    trend: trends.get(p.currencyPair) ?? 'flat',
    history: history.get(p.currencyPair) ?? [],
  }))

  return { prices, isLoading: query.isLoading, isError: query.isError, dataUpdatedAt: query.dataUpdatedAt }
}
