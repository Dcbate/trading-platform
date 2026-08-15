import { ShieldCheck, LogOut } from 'lucide-react'
import { useAuth, useLogout } from '../hooks/useAuth'
import { card } from '../lib/styles'

export function SettingsPage() {
  const user = useAuth()
  const logout = useLogout()

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold text-ink-900">Settings</h1>
        <p className="text-sm text-ink-400">Your profile and session.</p>
      </div>

      <div className={card}>
        <div className="flex items-center gap-4">
          <span className="flex h-12 w-12 items-center justify-center rounded-full bg-primary-50 text-lg font-semibold text-primary-700">
            {user?.email.slice(0, 1).toUpperCase()}
          </span>
          <div>
            <p className="text-sm font-semibold text-ink-900">{user?.email}</p>
            <p className="font-mono text-xs text-ink-400">{user?.clientId}</p>
          </div>
        </div>
      </div>

      <div className={card}>
        <div className="mb-3 flex items-center gap-2">
          <ShieldCheck size={16} strokeWidth={2} className="text-secondary-600" />
          <h2 className="text-sm font-semibold text-ink-700">Session</h2>
        </div>
        <p className="mb-4 text-sm text-ink-400">
          Your session is a short-lived access token in an HTTP-only cookie, refreshed automatically while you're
          active. Logging out revokes it server-side.
        </p>
        <button
          type="button"
          onClick={() => logout.mutate()}
          className="inline-flex items-center gap-1.5 rounded-xl border border-error-100 bg-error-50 px-4 py-2.5 text-sm font-semibold text-error-700 transition hover:bg-error-100"
        >
          <LogOut size={15} strokeWidth={2} />
          Log out
        </button>
      </div>
    </div>
  )
}
