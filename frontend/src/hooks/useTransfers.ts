import { useMutation, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '../api/client'
import type { TransferResponse } from '../types/api'

interface TransferInput {
  fromAccountId: string
  toAccountId: string
  amount: number
}

// There's no "list transfers for a client" endpoint on the backend — TransferController only
// exposes create and get-by-id (see docs/ACCOUNTS.md §5) — so this is the only transfer hook:
// submit one, get the resulting row back, and invalidate both accounts' balances.
export function useCreateTransfer() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: TransferInput) => apiClient.post<TransferResponse>('/v1/transfers', body).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['account', data.fromAccountId] })
      queryClient.invalidateQueries({ queryKey: ['account', data.toAccountId] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
}
