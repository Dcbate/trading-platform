import { ShieldCheck, LogOut, Sun, Moon } from 'lucide-react'
import { useAuth, useLogout } from '../hooks/useAuth'
import { useThemeStore } from '../store/themeStore'
import { listSection, pageTitle } from '../lib/styles'

export function SettingsPage() {
  const user = useAuth()
  const logout = useLogout()
  const theme = useThemeStore((s) => s.theme)
  const toggleTheme = useThemeStore((s) => s.toggleTheme)

  return (
    <div className="flex flex-col gap-8">
      <div>
        <h1 className={pageTitle}>Settings</h1>
        <p className="text-sm text-ink-400">Your profile and session.</p>
      </div>

      <div className={listSection}>
        <div className="flex items-center gap-4 py-4">
          <span className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-primary-50 text-lg font-semibold text-primary-700 dark:bg-primary-500/15 dark:text-primary-300">
            {user?.email.slice(0, 1).toUpperCase()}
          </span>
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold text-ink-900">{user?.email}</p>
            <p className="truncate font-mono text-xs text-ink-400">{user?.clientId}</p>
          </div>
        </div>

        <button type="button" onClick={toggleTheme} className="flex w-full items-center gap-4 py-4 text-left">
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-secondary-50 text-secondary-600 dark:bg-secondary-500/15 dark:text-secondary-400">
            {theme === 'dark' ? <Sun size={18} strokeWidth={2} /> : <Moon size={18} strokeWidth={2} />}
          </span>
          <div className="flex-1">
            <p className="text-sm font-semibold text-ink-900">Appearance</p>
            <p className="text-xs text-ink-400">{theme === 'dark' ? 'Dark' : 'Light'} mode</p>
          </div>
          <span className="text-xs font-semibold text-primary-600">Switch to {theme === 'dark' ? 'light' : 'dark'}</span>
        </button>

        <div className="py-4">
          <div className="mb-3 flex items-center gap-2">
            <ShieldCheck size={16} strokeWidth={2} className="text-secondary-600" />
            <h2 className="text-sm font-semibold text-ink-900">Session</h2>
          </div>
          <p className="mb-4 text-sm text-ink-400">
            Your session is a short-lived access token in an HTTP-only cookie, refreshed automatically while you're
            active. Logging out revokes it server-side.
          </p>
          <button
            type="button"
            onClick={() => logout.mutate()}
            className="inline-flex items-center gap-1.5 rounded-lg border border-error-100 bg-error-50 px-4 py-2.5 text-sm font-semibold text-error-700 transition hover:bg-error-100 dark:bg-error-500/15 dark:hover:bg-error-500/25"
          >
            <LogOut size={15} strokeWidth={2} />
            Log out
          </button>
        </div>
      </div>
    </div>
  )
}
