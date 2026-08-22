import axios, { AxiosError } from 'axios'
import { useAuthStore } from '../store/authStore'
import type { ApiError } from '../types/api'

// withCredentials so the HTTP-only access_token/refresh_token cookies AuthController sets are
// actually sent — there's no token to attach in JS, since that's the whole point of not using
// localStorage. baseURL is empty: dev goes through Vite's proxy (vite.config.ts), and the built
// app is served from the same origin as the API behind nginx (see frontend/nginx.conf) — same
// trick, same reason: SameSite=Strict cookies require it.
export const apiClient = axios.create({
  withCredentials: true,
})

let refreshPromise: Promise<void> | null = null

// One automatic retry on a 401: try /v1/auth/refresh once, and only replay the original request if
// that succeeds. If refresh also 401s, the session is genuinely over — log out instead of looping.
apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config
    if (error.response?.status !== 401 || !original || (original as { _retried?: boolean })._retried) {
      throw error
    }
    if (original.url?.includes('/v1/auth/')) {
      useAuthStore.getState().clear()
      throw error
    }

    ;(original as { _retried?: boolean })._retried = true
    refreshPromise ??= apiClient
      .post('/v1/auth/refresh')
      .then(() => undefined)
      .catch((refreshError) => {
        useAuthStore.getState().clear()
        throw refreshError
      })
      .finally(() => {
        refreshPromise = null
      })

    await refreshPromise
    return apiClient.request(original)
  },
)

// Internal identifiers (account/loan/transfer/session ids) show up embedded in some backend
// exception messages (e.g. "Account not found: 3fa85f64-...") — meaningful for a log line, not
// for a client reading a toast. Strip them and tidy up whatever punctuation is left behind.
function stripInternalIds(message: string): string {
  return message
    .replace(/\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/gi, '')
    .replace(/\s{2,}/g, ' ')
    .replace(/\s*:\s*$/, '')
    .replace(/\s+([,.])/g, '$1')
    .trim()
}

const STATUS_FALLBACKS: Record<number, string> = {
  400: "That request doesn't look right — please check the details and try again.",
  401: 'Your session has expired — please log in again.',
  403: "You don't have permission to do that.",
  404: "That couldn't be found.",
  409: "That couldn't be completed — please check the details and try again.",
}

export function apiErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    if (!error.response) {
      // No response at all: request never reached the server (offline, DNS/CORS failure) or timed
      // out — a raw axios "Network Error" message is meaningless to a client.
      return "Can't reach the bank right now — check your connection and try again."
    }
    const data = error.response.data as ApiError | undefined
    if (data?.messages?.length) {
      return data.messages.map(stripInternalIds).join(', ')
    }
    const status = error.response.status
    if (status in STATUS_FALLBACKS) return STATUS_FALLBACKS[status]
    if (status >= 500) return 'Something went wrong on our end — please try again in a moment.'
  }
  return 'Something went wrong. Please try again.'
}
