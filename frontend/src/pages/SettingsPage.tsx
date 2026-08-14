import { useAuth, useLogout } from '../hooks/useAuth'

export function SettingsPage() {
  const user = useAuth()
  const logout = useLogout()

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">Settings</h1>
        <p className="text-sm text-slate-500">Your profile and session.</p>
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm">
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-slate-600">
          <dt className="text-slate-400">Email</dt>
          <dd>{user?.email}</dd>
          <dt className="text-slate-400">Client id</dt>
          <dd className="font-mono text-xs">{user?.clientId}</dd>
        </dl>
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-4">
        <h2 className="mb-2 text-sm font-medium text-slate-700">Session</h2>
        <p className="mb-3 text-sm text-slate-500">
          Your session is a short-lived access token in an HTTP-only cookie, refreshed automatically while you're
          active. Logging out revokes it server-side.
        </p>
        <button
          type="button"
          onClick={() => logout.mutate()}
          className="rounded-md border border-red-200 px-4 py-2 text-sm font-medium text-red-700 hover:bg-red-50"
        >
          Log out
        </button>
      </div>
    </div>
  )
}
