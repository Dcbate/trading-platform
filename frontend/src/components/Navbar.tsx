import { NavLink } from 'react-router-dom'
import { useAuth, useLogout } from '../hooks/useAuth'

const links = [
  { to: '/', label: 'Dashboard' },
  { to: '/accounts', label: 'Accounts' },
  { to: '/transfer', label: 'Transfer' },
  { to: '/loans', label: 'Loans' },
  { to: '/fx', label: 'FX Markets' },
  { to: '/settings', label: 'Settings' },
]

export function Navbar() {
  const user = useAuth()
  const logout = useLogout()

  return (
    <nav className="sticky top-0 z-10 border-b border-slate-200 bg-white">
      <div className="mx-auto flex max-w-5xl items-center justify-between px-4 py-3">
        <div className="flex items-center gap-6">
          <div className="flex items-center gap-2">
            <span className="flex h-8 w-8 items-center justify-center rounded-md bg-primary-600 font-serif text-lg font-bold text-white">
              B
            </span>
            <div className="leading-tight">
              <p className="text-sm font-bold text-slate-900">BATE BANKING</p>
              <p className="text-[11px] text-slate-500">Banking by Bate</p>
            </div>
          </div>
          <div className="flex gap-4">
            {links.map((link) => (
              <NavLink
                key={link.to}
                to={link.to}
                end={link.to === '/'}
                className={({ isActive }) =>
                  `text-sm font-medium ${isActive ? 'border-b-2 border-primary-600 text-primary-700' : 'text-slate-500 hover:text-primary-600'}`
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
