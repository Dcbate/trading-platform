import { useQuery } from '@tanstack/react-query'
import { apiClient } from '../api/client'
import type { BankStatementResponse } from '../types/api'

// accountId is optional — omit it for the full cross-account feed, or pass one to scope the
// statement to a single account (e.g. a per-account "view statement" link from AccountsPage).
export function useBankStatement(clientId: string | undefined, accountId?: string) {
  return useQuery({
    queryKey: ['statement', clientId, accountId],
    queryFn: () => apiClient.get<BankStatementResponse>('/v1/statement', { params: { clientId, accountId } }).then((r) => r.data),
    enabled: !!clientId,
  })
}
