import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '../api/client'
import type { OrderResponse, OrderSide } from '../types/api'

const POLL_INTERVAL_MS = 3000

export function useOrders(clientId: string | undefined) {
  return useQuery({
    queryKey: ['orders', clientId],
    queryFn: () => apiClient.get<OrderResponse[]>('/v1/orders', { params: { clientId } }).then((r) => r.data),
    enabled: !!clientId,
    // No WebSocket here on purpose: OrderStreamHandler broadcasts every order update to every
    // connected session with no per-client filtering (fine for the FX dealer desk it was built
    // for, not safe to expose to retail clients without adding server-side scoping first — see
    // docs/TRADING_SYSTEM.md). Polling is the honest tradeoff until that's built.
    refetchInterval: POLL_INTERVAL_MS,
    refetchIntervalInBackground: true,
  })
}

interface SubmitOrderInput {
  clientId: string
  accountId: string
  currencyPair: string
  side: OrderSide
  quantity: number
  price: number
}

export function useSubmitOrder() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: SubmitOrderInput) => apiClient.post<OrderResponse>('/v1/orders', body).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['orders', data.clientId] })
      queryClient.invalidateQueries({ queryKey: ['positions', data.clientId] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
}
