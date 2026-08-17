import { useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import toast from 'react-hot-toast'
import { apiErrorMessage } from '../api/client'
import { useAuth, useSignup } from '../hooks/useAuth'
import { btnPrimary, input, label } from '../lib/styles'

// Mirrors SignupRequest's @Pattern on the backend (auth/api/dto/SignupRequest.java) so a client
// finds out immediately rather than round-tripping to the server first — the backend still
// enforces the real rule regardless of what this does.
const PASSWORD_PATTERN = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^a-zA-Z0-9]).{12,}$/

export function SignupPage() {
  const user = useAuth()
  const navigate = useNavigate()
  const signup = useSignup()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')

  if (user) {
    return <Navigate to="/" replace />
  }

  const passwordValid = PASSWORD_PATTERN.test(password)

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!passwordValid) {
      toast.error('Password must be 12+ characters with an uppercase letter, lowercase letter, digit, and symbol.')
      return
    }
    signup.mutate(
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
          <h2 className="max-w-sm text-3xl font-semibold leading-tight">Open an account in seconds.</h2>
          <p className="mt-3 max-w-sm text-sm text-ink-400">
            A £0 GBP current account is opened for you automatically — deposit, transfer, borrow, and trade from there.
          </p>
        </div>
        <p className="text-xs text-ink-400">© {new Date().getFullYear()} Bate Banking</p>
      </div>

      <div className="flex w-full flex-col items-center justify-center bg-canvas px-6 py-12 lg:w-1/2">
        <div className="w-full max-w-sm">
          <div className="mb-8 flex items-center gap-2 lg:hidden">
            <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-primary-600 font-serif text-xl font-bold text-white">B</span>
            <div className="leading-tight">
              <p className="text-sm font-bold text-ink-900">BATE BANKING</p>
              <p className="text-[11px] text-ink-400">Banking by Bate</p>
            </div>
          </div>
          <h1 className="mb-1 text-2xl font-semibold text-ink-900">Create your account</h1>
          <p className="mb-8 text-sm text-ink-400">Opens a £0 GBP current account automatically.</p>
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
                autoComplete="new-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className={input}
                required
              />
              <p className={`text-xs ${password.length === 0 || passwordValid ? 'text-ink-400' : 'text-error-600'}`}>
                12+ characters, with an uppercase letter, lowercase letter, digit, and symbol.
              </p>
            </div>
            <button type="submit" disabled={signup.isPending} className={`${btnPrimary} mt-2 w-full py-3`}>
              {signup.isPending ? 'Creating account…' : 'Sign up'}
            </button>
          </form>
          <p className="mt-6 text-center text-sm text-ink-400">
            Already have an account?{' '}
            <Link to="/login" className="font-semibold text-primary-600 hover:text-primary-700">
              Log in
            </Link>
          </p>
        </div>
      </div>
    </div>
  )
}
