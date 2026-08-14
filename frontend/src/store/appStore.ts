import { create } from 'zustand'

interface AppState {
  selectedAccountId: string | null
  selectAccount: (accountId: string | null) => void
}

// Small piece of cross-page UI state: which account the Transfer/Loans pages should default to,
// set from AccountsPage when the client clicks into one. Everything else (accounts, transfers,
// loans data) lives in React Query's cache, not here — this store is only for state React Query
// has no opinion about.
export const useAppStore = create<AppState>((set) => ({
  selectedAccountId: null,
  selectAccount: (accountId) => set({ selectedAccountId: accountId }),
}))
