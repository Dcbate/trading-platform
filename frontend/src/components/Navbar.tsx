import { NavLink } from 'react-router-dom'
import { useAuth, useLogout } from '../hooks/useAuth'

const links = [
  { to: '/', label: 'Dashboard' },
  { to: '/accounts', label: 'Accounts' },
  { to: '/transfer', label: 'Transfer' },
  { to: '/loans', label: 'Loans' },
  { to: '/settings', label: 'Settings' },
]

export function Navbar() {
  const user = useAuth()
  const logout = useLogout()

  return (
    <nav className="border-b border-slate-200 bg-white">
      <div className="mx-auto flex max-w-5xl items-center justify-between px-4 py-3">
        <div className="flex items-center gap-6">
          <span className="text-lg font-semibold text-slate-900">trading-platform</span>
          <div className="flex gap-4">
            {links.map((link) => (
              <NavLink
                key={link.to}
                to={link.to}
                end={link.to === '/'}
                className={({ isActive }) =>
                  `text-sm font-medium ${isActive ? 'text-slate-900' : 'text-slate-500 hover:text-slate-800'}`
                }
              >
                {link.label}
              </NavLink>
            ))}
          </div>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-sm text-slate-500">{user?.email}</span>
          <button
            type="button"
            onClick={() => logout.mutate()}
            className="rounded-md border border-slate-300 px-3 py-1 text-sm text-slate-700 hover:bg-slate-50"
          >
            Log out
          </button>
        </div>
      </div>
    </nav>
  )
}
