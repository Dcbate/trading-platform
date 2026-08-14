import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '../api/client'
import type { LoanProductResponse, LoanProductType, LoanResponse } from '../types/api'

export function useLoanProducts() {
  return useQuery({
    queryKey: ['loan-products'],
    queryFn: () => apiClient.get<LoanProductResponse[]>('/v1/loans/products').then((r) => r.data),
    staleTime: Infinity,
  })
}

export function useLoans(clientId: string | undefined) {
  return useQuery({
    queryKey: ['loans', clientId],
    queryFn: () => apiClient.get<LoanResponse[]>('/v1/loans', { params: { clientId } }).then((r) => r.data),
    enabled: !!clientId,
  })
}

interface OriginateLoanInput {
  clientId: string
  accountId: string
  principal: number
  productType: LoanProductType
}

export function useOriginateLoan() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: OriginateLoanInput) => apiClient.post<LoanResponse>('/v1/loans', body).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['loans', data.clientId] })
      queryClient.invalidateQueries({ queryKey: ['account', data.accountId] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
}

export function useRepayLoan() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ loanId, amount }: { loanId: string; amount: number }) =>
      apiClient.post<LoanResponse>(`/v1/loans/${loanId}/repay`, { amount }).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['loans', data.clientId] })
      queryClient.invalidateQueries({ queryKey: ['account', data.accountId] })
      queryClient.invalidateQueries({ queryKey: ['accounts'] })
    },
  })
}
