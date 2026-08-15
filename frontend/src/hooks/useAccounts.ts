import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '../api/client'
import type { AccountResponse, AccountType, BalanceSummaryResponse } from '../types/api'

export function useAccounts(clientId: string | undefined) {
  return useQuery({
    queryKey: ['accounts', clientId],
    queryFn: () => apiClient.get<AccountResponse[]>('/v1/accounts', { params: { clientId } }).then((r) => r.data),
    enabled: !!clientId,
  })
}

export function useBalanceSummary(clientId: string | undefined) {
  return useQuery({
    queryKey: ['accounts', 'balances', clientId],
    queryFn: () => apiClient.get<BalanceSummaryResponse>('/v1/accounts/balances', { params: { clientId } }).then((r) => r.data),
    enabled: !!clientId,
  })
}

export function useAccount(accountId: string | undefined) {
  return useQuery({
    queryKey: ['account', accountId],
    queryFn: () => apiClient.get<AccountResponse>(`/v1/accounts/${accountId}`).then((r) => r.data),
    enabled: !!accountId,
  })
}

export function useCurrencies() {
  return useQuery({
    queryKey: ['currencies'],
    queryFn: () => apiClient.get<string[]>('/v1/accounts/currencies').then((r) => r.data),
    staleTime: Infinity,
  })
}

interface OpenAccountInput {
  clientId: string
  accountType: AccountType
  currency: string
  nickname: string | null
  openingBalance: number
}

export function useOpenAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: OpenAccountInput) => apiClient.post<AccountResponse>('/v1/accounts', body).then((r) => r.data),
    onSuccess: (_data, variables) => {
      queryClient.invalidateQueries({ queryKey: ['accounts', variables.clientId] })
      queryClient.invalidateQueries({ queryKey: ['accounts', 'balances', variables.clientId] })
    },
  })
}

function useAccountMutation(action: 'deposit' | 'withdraw') {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ accountId, amount }: { accountId: string; amount: number }) =>
      apiClient.post<AccountResponse>(`/v1/accounts/${accountId}/${action}`, { amount }).then((r) => r.data),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['account', data.accountId] })
      queryClient.invalidateQueries({ queryKey: ['accounts', data.clientId] })
      queryClient.invalidateQueries({ queryKey: ['accounts', 'balances', data.clientId] })
    },
  })
}

export function useDeposit() {
  return useAccountMutation('deposit')
}

export function useWithdraw() {
  return useAccountMutation('withdraw')
}

export function useConvert() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ fromAccountId, toAccountId, amount }: { fromAccountId: string; toAccountId: string; amount: number }) =>
      apiClient
        .post<AccountResponse>(`/v1/accounts/${fromAccountId}/convert`, { toAccountId, amount })
        .then((r) => r.data),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['accounts', data.clientId] })
      queryClient.invalidateQueries({ queryKey: ['accounts', 'balances', data.clientId] })
    },
  })
}

// destinationAccountId is only required by the backend when the account being closed has a
// positive balance — for a zero-balance account this can be omitted entirely.
export function useCloseAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ accountId, destinationAccountId }: { accountId: string; destinationAccountId?: string }) =>
      apiClient
        .post<AccountResponse>(`/v1/accounts/${accountId}/close`, { destinationAccountId: destinationAccountId ?? null })
        .then((r) => r.data),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: ['account', data.accountId] })
      queryClient.invalidateQueries({ queryKey: ['accounts', data.clientId] })
      queryClient.invalidateQueries({ queryKey: ['accounts', 'balances', data.clientId] })
    },
  })
}
