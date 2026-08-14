import { useMutation, useQueryClient } from '@tanstack/react-query'
import { apiClient } from '../api/client'
import { useAuthStore } from '../store/authStore'
import type { AuthResponse } from '../types/api'

interface Credentials {
  email: string
  password: string
}

export function useSignup() {
  const setUser = useAuthStore((s) => s.setUser)
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: Credentials) => apiClient.post<AuthResponse>('/auth/signup', body).then((r) => r.data),
    onSuccess: (data) => {
      setUser(data)
      queryClient.clear()
    },
  })
}

export function useLogin() {
  const setUser = useAuthStore((s) => s.setUser)
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: Credentials) => apiClient.post<AuthResponse>('/auth/login', body).then((r) => r.data),
    onSuccess: (data) => {
      setUser(data)
      queryClient.clear()
    },
  })
}

export function useLogout() {
  const clear = useAuthStore((s) => s.clear)
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiClient.post('/auth/logout'),
    onSettled: () => {
      clear()
      queryClient.clear()
    },
  })
}

export function useAuth() {
  return useAuthStore((s) => s.user)
}
