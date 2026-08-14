import { useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import toast from 'react-hot-toast'
import { apiErrorMessage } from '../api/client'
import { useAuth, useSignup } from '../hooks/useAuth'

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
    <div className="flex min-h-screen items-center justify-center bg-slate-50 px-4">
      <div className="w-full max-w-sm rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <div className="mb-6 flex items-center gap-2">
          <span className="flex h-9 w-9 items-center justify-center rounded-md bg-primary-600 font-serif text-xl font-bold text-white">
            B
          </span>
          <div className="leading-tight">
            <p className="text-sm font-bold text-slate-900">BATE BANKING</p>
            <p className="text-[11px] text-slate-500">Banking by Bate</p>
          </div>
        </div>
        <h1 className="mb-1 text-xl font-semibold text-slate-900">Create an account</h1>
        <p className="mb-6 text-sm text-slate-500">Opens a $0 USD checking account automatically.</p>
        <form onSubmit={handleSubmit} className="flex flex-col gap-4">
          <div className="flex flex-col gap-1">
            <label className="text-sm font-medium text-slate-700" htmlFor="email">
              Email
            </label>
            <input
              id="email"
              type="email"
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="rounded-md border border-slate-300 px-3 py-2 text-sm"
              required
            />
          </div>
          <div className="flex flex-col gap-1">
            <label className="text-sm font-medium text-slate-700" htmlFor="password">
              Password
            </label>
            <input
              id="password"
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className="rounded-md border border-slate-300 px-3 py-2 text-sm"
              required
            />
            <p className={`text-xs ${password.length === 0 || passwordValid ? 'text-slate-400' : 'text-red-600'}`}>
              12+ characters, with an uppercase letter, lowercase letter, digit, and symbol.
            </p>
          </div>
          <button
            type="submit"
            disabled={signup.isPending}
            className="rounded-md bg-primary-600 px-4 py-2 text-sm font-medium text-white hover:bg-primary-700 disabled:opacity-50"
          >
            {signup.isPending ? 'Creating account…' : 'Sign up'}
          </button>
        </form>
        <p className="mt-4 text-center text-sm text-slate-500">
          Already have an account?{' '}
          <Link to="/login" className="font-medium text-primary-700 underline">
            Log in
          </Link>
        </p>
      </div>
    </div>
  )
}
