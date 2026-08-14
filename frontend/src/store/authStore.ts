import { create } from 'zustand'
import { persist } from 'zustand/middleware'

interface AuthUser {
  clientId: string
  email: string
}

interface AuthState {
  user: AuthUser | null
  setUser: (user: AuthUser) => void
  clear: () => void
}

// Persists only clientId/email to localStorage — never a token, those live exclusively in the
// HTTP-only cookies AuthController sets. This is just "was someone logged in last time we
// checked," so the app can show the right UI before the first API call resolves; ProtectedRoute
// still treats every 401 as authoritative regardless of what's cached here.
export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      user: null,
      setUser: (user) => set({ user }),
      clear: () => set({ user: null }),
    }),
    { name: 'trading-platform-auth' },
  ),
)
