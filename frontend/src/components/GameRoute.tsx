import { Link, Outlet } from 'react-router-dom'
import { useAuth } from '../hooks/useAuth'
import { Sidebar } from './Sidebar'

// Game Mode is the one part of the app reachable without logging in. A logged-in user still gets
// the normal full app shell (Sidebar, everything else a click away) — this only swaps in a
// minimal standalone header for a genuinely anonymous visitor, since most of the sidebar's links
// need a real account and would just be dead ends for them.
export function GameRoute() {
  const user = useAuth()

  if (user) {
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

  return (
    <div className="min-h-screen bg-canvas">
      <header className="flex items-center justify-between border-b border-ink-100 bg-surface px-6 py-4">
        <Link to="/game" className="flex items-center gap-2">
          <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary-600 font-serif text-lg font-bold text-white">B</span>
          <div className="leading-tight">
            <p className="text-sm font-bold text-ink-900">BATE BANKING</p>
            <p className="text-[11px] text-ink-400">Game Mode — playing as a guest</p>
          </div>
        </Link>
        <Link to="/login" className="text-sm font-semibold text-primary-600 hover:text-primary-700">
          Log in for real banking →
        </Link>
      </header>
      <main className="px-6 py-8">
        <div className="mx-auto max-w-5xl">
          <Outlet />
        </div>
      </main>
    </div>
  )
}
