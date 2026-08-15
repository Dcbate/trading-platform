import { useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import toast from 'react-hot-toast'
import { Gamepad2 } from 'lucide-react'
import { apiErrorMessage } from '../api/client'
import { useAuth, useLogin } from '../hooks/useAuth'
import { btnPrimary, input, label } from '../lib/styles'

export function LoginPage() {
  const user = useAuth()
  const navigate = useNavigate()
  const login = useLogin()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')

  if (user) {
    return <Navigate to="/" replace />
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    login.mutate(
      { email, password },
      {
        onSuccess: () => navigate('/'),
        onError: (error) => toast.error(apiErrorMessage(error)),
      },
    )
  }

  return (
    <div className="flex min-h-screen">
      <div className="hidden w-1/2 flex-col justify-between bg-ink-950 p-12 text-white lg:flex">
        <div className="flex items-center gap-2">
          <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary-500 font-serif text-xl font-bold">B</span>
          <div className="leading-tight">
            <p className="text-sm font-bold">BATE BANKING</p>
            <p className="text-[11px] text-ink-400">Banking by Bate</p>
          </div>
        </div>
        <div>
          <h2 className="max-w-sm text-3xl font-semibold leading-tight">Accounts, transfers, loans, and trading — one login.</h2>
          <p className="mt-3 max-w-sm text-sm text-ink-400">
            Built end to end, event-driven, with real settlement. Not a mockup.
          </p>
          <Link
            to="/game"
            className="mt-6 flex max-w-sm items-center gap-3 rounded-xl bg-white/5 p-3 transition hover:bg-white/10"
          >
            <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-primary-500 to-secondary-500">
              <Gamepad2 size={16} strokeWidth={2} className="text-white" />
            </span>
            <p className="text-xs text-ink-400">
              No account needed — try <span className="font-semibold text-white">Game Mode</span> now. Practice loans,
              FX, and stock trading against a countdown, with fake money.
            </p>
          </Link>
        </div>
        <p className="text-xs text-ink-400">© {new Date().getFullYear()} Bate Banking</p>
      </div>

      <div className="flex w-full flex-col items-center justify-center bg-canvas px-6 lg:w-1/2">
        <div className="w-full max-w-sm">
          <div className="mb-8 flex items-center gap-2 lg:hidden">
            <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary-600 font-serif text-xl font-bold text-white">B</span>
            <div className="leading-tight">
              <p className="text-sm font-bold text-ink-900">BATE BANKING</p>
              <p className="text-[11px] text-ink-400">Banking by Bate</p>
            </div>
          </div>
          <h1 className="mb-1 text-2xl font-semibold text-ink-900">Welcome back</h1>
          <p className="mb-8 text-sm text-ink-400">Log in to your Bate Banking account.</p>
          <form onSubmit={handleSubmit} className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5">
              <label className={label} htmlFor="email">
                Email
              </label>
              <input
                id="email"
                type="email"
                autoComplete="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                className={input}
                required
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label className={label} htmlFor="password">
                Password
              </label>
              <input
                id="password"
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className={input}
                required
              />
            </div>
            <button type="submit" disabled={login.isPending} className={`${btnPrimary} mt-2 w-full py-3`}>
              {login.isPending ? 'Logging in…' : 'Log in'}
            </button>
          </form>
          <p className="mt-6 text-center text-sm text-ink-400">
            No account?{' '}
            <Link to="/signup" className="font-semibold text-primary-600 hover:text-primary-700">
              Sign up
            </Link>
          </p>
        </div>
      </div>
    </div>
  )
}
