import { useQuery } from '@tanstack/react-query'
import { apiClient } from '../api/client'
import type { BankStatementResponse } from '../types/api'

export function useBankStatement(clientId: string | undefined) {
  return useQuery({
    queryKey: ['statement', clientId],
    queryFn: () => apiClient.get<BankStatementResponse>('/v1/statement', { params: { clientId } }).then((r) => r.data),
    enabled: !!clientId,
  })
}
