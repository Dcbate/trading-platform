const GUEST_ID_KEY = 'bate-banking-guest-id'

// Game Mode is playable without an account — a guest still needs *some* stable identifier so
// "your stats" means something across page reloads (otherwise every game would start from zero
// personal history). Generated once per browser and persisted in localStorage; if a real user is
// logged in their actual clientId is used instead (see useGameClientId), so this only ever
// applies to genuinely anonymous play.
export function getOrCreateGuestId(): string {
  const existing = localStorage.getItem(GUEST_ID_KEY)
  if (existing) return existing
  const id = `guest-${crypto.randomUUID()}`
  localStorage.setItem(GUEST_ID_KEY, id)
  return id
}
