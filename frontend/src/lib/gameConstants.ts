// Mirrors GameServiceImpl.REACTION_WINDOW_SECONDS — used client-side only to decide which toast
// to show / whether the actionable event prompt is still "live"; the actual fee waiver is always
// enforced server-side regardless of what the client detects.
export const REACTION_WINDOW_MS = 10_000
