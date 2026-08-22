import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '../api/client'
import type {
  GameDebriefResponse,
  GameDifficultyCode,
  GameDifficultyResponse,
  GameLeaderboardResponse,
  GameLeaderboardSortBy,
  GameMarketEventResponse,
  GamePriceResponse,
  GameSessionResponse,
  GameStatsResponse,
  GameTradeResponse,
  OrderSide,
} from '../types/api'

// Matches GameMarketScheduler's 5-second server tick — polling faster than the server actually
// changes anything just adds requests without fresher data, and this is deliberately slower than
// the real desk's 2-3s cadence so a trend regime is actually readable before it's gone.
const GAME_POLL_INTERVAL_MS = 5000

export function useGameDifficulties() {
  return useQuery({
    queryKey: ['game', 'difficulties'],
    queryFn: () => apiClient.get<GameDifficultyResponse[]>('/v1/game/difficulties').then((r) => r.data),
    staleTime: Infinity,
  })
}

export function useGameMarket(difficulty: GameDifficultyCode | undefined) {
  return useQuery({
    queryKey: ['game', 'market', difficulty],
    queryFn: () => apiClient.get<GamePriceResponse[]>('/v1/game/market', { params: { difficulty } }).then((r) => r.data),
    enabled: !!difficulty,
    refetchInterval: GAME_POLL_INTERVAL_MS,
    // Same fix as useLivePrices — without this the ticker (and the whole point of "the clock is
    // ticking") silently freezes the moment the tab isn't the active one.
    refetchIntervalInBackground: true,
  })
}

export function useGameMarketEvents(difficulty: GameDifficultyCode | undefined) {
  return useQuery({
    queryKey: ['game', 'market-events', difficulty],
    queryFn: () => apiClient.get<GameMarketEventResponse[]>('/v1/game/market/events', { params: { difficulty } }).then((r) => r.data),
    enabled: !!difficulty,
    refetchInterval: GAME_POLL_INTERVAL_MS,
    refetchIntervalInBackground: true,
  })
}

export function useGameLeaderboard(
  difficulty: GameDifficultyCode | undefined,
  sortBy: GameLeaderboardSortBy,
  clientId: string | undefined,
) {
  return useQuery({
    queryKey: ['game', 'leaderboard', difficulty, sortBy, clientId],
    queryFn: () =>
      apiClient.get<GameLeaderboardResponse>('/v1/game/leaderboard', { params: { difficulty, sortBy, clientId } }).then((r) => r.data),
    enabled: !!difficulty,
  })
}

export function useStartGame() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ clientId, difficulty }: { clientId: string; difficulty: GameDifficultyCode }) =>
      apiClient.post<GameSessionResponse>('/v1/game/sessions', { clientId, difficulty }).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.setQueryData(['game', 'session', data.sessionId], data)
    },
  })
}

export function useGameSession(sessionId: string | undefined) {
  return useQuery({
    queryKey: ['game', 'session', sessionId],
    queryFn: () => apiClient.get<GameSessionResponse>(`/v1/game/sessions/${sessionId}`).then((r) => r.data),
    enabled: !!sessionId,
    // Stop polling once the game has actually ended server-side — the win/lose screen is the
    // final state, there's nothing left to refresh.
    refetchInterval: (query) => (query.state.data?.status === 'IN_PROGRESS' ? GAME_POLL_INTERVAL_MS : false),
    refetchIntervalInBackground: true,
  })
}

export function useGameTrades(sessionId: string | undefined) {
  return useQuery({
    queryKey: ['game', 'trades', sessionId],
    queryFn: () => apiClient.get<GameTradeResponse[]>(`/v1/game/sessions/${sessionId}/trades`).then((r) => r.data),
    enabled: !!sessionId,
    refetchInterval: GAME_POLL_INTERVAL_MS,
    refetchIntervalInBackground: true,
  })
}

export function useGameDebrief(sessionId: string | undefined) {
  return useQuery({
    queryKey: ['game', 'debrief', sessionId],
    queryFn: () => apiClient.get<GameDebriefResponse>(`/v1/game/sessions/${sessionId}/debrief`).then((r) => r.data),
    enabled: !!sessionId,
    // A finished session's trade/loan history never changes — one fetch is all this needs, and
    // it saves calling out to Claude again on every accidental re-render.
    staleTime: Infinity,
    retry: false,
  })
}

export function useGameStats(clientId: string | undefined) {
  return useQuery({
    queryKey: ['game', 'stats', clientId],
    queryFn: () => apiClient.get<GameStatsResponse>('/v1/game/stats', { params: { clientId } }).then((r) => r.data),
    enabled: !!clientId,
  })
}

export function useTakeGameLoan(sessionId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (amount: number) =>
      apiClient.post<GameSessionResponse>(`/v1/game/sessions/${sessionId}/loans`, { amount }).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.setQueryData(['game', 'session', sessionId], data)
    },
  })
}

export function useActivateGameSpeedBoost(sessionId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiClient.post<GameSessionResponse>(`/v1/game/sessions/${sessionId}/speed-boost`, {}).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.setQueryData(['game', 'session', sessionId], data)
    },
  })
}

export function useHireGameAdvisor(sessionId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiClient.post<GameSessionResponse>(`/v1/game/sessions/${sessionId}/advisor/hire`, {}).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.setQueryData(['game', 'session', sessionId], data)
    },
  })
}

export function usePurchaseGameInsurance(sessionId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (symbol: string) =>
      apiClient.post<GameSessionResponse>(`/v1/game/sessions/${sessionId}/insurance`, { symbol }).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.setQueryData(['game', 'session', sessionId], data)
    },
  })
}

export function useRepayGameLoan(sessionId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ gameLoanId, amount }: { gameLoanId: string; amount: number }) =>
      apiClient.post<GameSessionResponse>(`/v1/game/sessions/${sessionId}/loans/${gameLoanId}/repay`, { amount }).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.setQueryData(['game', 'session', sessionId], data)
    },
  })
}

export function useDepositToGameSavings(sessionId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (amount: number) =>
      apiClient.post<GameSessionResponse>(`/v1/game/sessions/${sessionId}/savings/deposit`, { amount }).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.setQueryData(['game', 'session', sessionId], data)
    },
  })
}

export function useWithdrawFromGameSavings(sessionId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (amount: number) =>
      apiClient.post<GameSessionResponse>(`/v1/game/sessions/${sessionId}/savings/withdraw`, { amount }).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.setQueryData(['game', 'session', sessionId], data)
    },
  })
}

export function usePlaceGameTrade(sessionId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ symbol, side, quantity }: { symbol: string; side: OrderSide; quantity: number }) =>
      apiClient.post<GameSessionResponse>(`/v1/game/sessions/${sessionId}/trades`, { symbol, side, quantity }).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.setQueryData(['game', 'session', sessionId], data)
      queryClient.invalidateQueries({ queryKey: ['game', 'trades', sessionId] })
    },
  })
}
