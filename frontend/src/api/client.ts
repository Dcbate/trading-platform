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

// One automatic retry on a 401: try /auth/refresh once, and only replay the original request if
// that succeeds. If refresh also 401s, the session is genuinely over — log out instead of looping.
apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config
    if (error.response?.status !== 401 || !original || (original as { _retried?: boolean })._retried) {
      throw error
    }
    if (original.url?.includes('/auth/')) {
      useAuthStore.getState().clear()
      throw error
    }

    ;(original as { _retried?: boolean })._retried = true
    refreshPromise ??= apiClient
      .post('/auth/refresh')
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

export function apiErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as ApiError | undefined
    if (data?.messages?.length) return data.messages.join(', ')
  }
  return 'Something went wrong. Please try again.'
}
