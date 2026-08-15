import { NavLink } from 'react-router-dom'
import {
  LayoutDashboard,
  Wallet,
  ArrowLeftRight,
  Percent,
  TrendingUp,
  LineChart,
  Settings,
  LogOut,
} from 'lucide-react'
import { useAuth, useLogout } from '../hooks/useAuth'

const links = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/accounts', label: 'Accounts', icon: Wallet },
  { to: '/transfer', label: 'Transfer', icon: ArrowLeftRight },
  { to: '/loans', label: 'Loans', icon: Percent },
  { to: '/fx', label: 'FX Markets', icon: TrendingUp },
  { to: '/trading', label: 'Trading', icon: LineChart },
]

export function Sidebar() {
  const user = useAuth()
  const logout = useLogout()

  return (
    <aside className="flex h-screen w-60 shrink-0 flex-col bg-ink-950 text-white">
      <div className="flex items-center gap-2 px-5 py-6">
        <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary-500 font-serif text-lg font-bold text-white">
          B
        </span>
        <div className="leading-tight">
          <p className="text-sm font-bold">BATE BANKING</p>
          <p className="text-[11px] text-ink-400">Banking by Bate</p>
        </div>
      </div>

      <nav className="flex flex-1 flex-col gap-0.5 px-3">
        {links.map((link) => {
          const Icon = link.icon
          return (
            <NavLink
              key={link.to}
              to={link.to}
              end={link.to === '/'}
              className={({ isActive }) =>
                `flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition ${
                  isActive ? 'bg-primary-500/15 text-primary-300' : 'text-ink-400 hover:bg-white/5 hover:text-white'
                }`
              }
            >
              <Icon size={18} strokeWidth={2} />
              {link.label}
            </NavLink>
          )
        })}
      </nav>

      <div className="flex flex-col gap-2 border-t border-white/5 px-3 py-4">
        <NavLink
          to="/settings"
          className={({ isActive }) =>
            `flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition ${
              isActive ? 'bg-primary-500/15 text-primary-300' : 'text-ink-400 hover:bg-white/5 hover:text-white'
            }`
          }
        >
          <Settings size={18} strokeWidth={2} />
          Settings
        </NavLink>
        <div className="flex items-center justify-between rounded-xl px-3 py-2">
          <span className="truncate text-xs text-ink-400">{user?.email}</span>
          <button
            type="button"
            onClick={() => logout.mutate()}
            className="shrink-0 rounded-lg p-1.5 text-ink-400 transition hover:bg-white/5 hover:text-white"
            title="Log out"
          >
            <LogOut size={16} strokeWidth={2} />
          </button>
        </div>
      </div>
    </aside>
  )
}
