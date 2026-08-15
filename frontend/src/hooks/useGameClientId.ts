import { useAuth } from './useAuth'
import { getOrCreateGuestId } from '../lib/guestId'

// A real logged-in client's own id if they have one, otherwise a stable per-browser guest id —
// Game Mode works either way, so the id it plays under shouldn't require being logged in.
export function useGameClientId(): { clientId: string; isGuest: boolean } {
  const user = useAuth()
  if (user) return { clientId: user.clientId, isGuest: false }
  return { clientId: getOrCreateGuestId(), isGuest: true }
}
