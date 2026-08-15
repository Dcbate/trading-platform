import { useQuery } from '@tanstack/react-query'
import { apiClient } from '../api/client'
import type { PositionResponse } from '../types/api'

export function usePositions(clientId: string | undefined) {
  return useQuery({
    queryKey: ['positions', clientId],
    queryFn: () => apiClient.get<PositionResponse[]>('/v1/positions', { params: { clientId } }).then((r) => r.data),
    enabled: !!clientId,
    refetchInterval: 3000,
    refetchIntervalInBackground: true,
  })
}
