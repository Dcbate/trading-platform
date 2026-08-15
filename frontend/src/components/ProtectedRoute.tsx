import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '../hooks/useAuth'
import { Sidebar } from './Sidebar'

// Gate is the cached authStore user, not a live check — it's just "did we log in this session,"
// not "is the cookie still valid." The real enforcement is server-side: any request past this
// point that gets a 401 goes through apiClient's interceptor, which tries one silent refresh and,
// failing that, clears the store — the next render redirects here anyway. So this component is a
// UX convenience (skip the flash of protected content before the API says no), not the security
// boundary.
export function ProtectedRoute() {
  const user = useAuth()

  if (!user) {
    return <Navigate to="/login" replace />
  }

  return (
    <div className="flex min-h-screen bg-canvas">
      <Sidebar />
      <main className="min-w-0 flex-1 px-10 py-8">
        <div className="mx-auto max-w-5xl">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
