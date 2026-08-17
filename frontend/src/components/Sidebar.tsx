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
  Sun,
  Moon,
  Gamepad2,
  FileText,
  Bitcoin,
} from 'lucide-react'
import { useAuth, useLogout } from '../hooks/useAuth'
import { useThemeStore } from '../store/themeStore'

// Grouped like a traditional bank's sidebar (Chase/Bank of America style) — section labels over
// related pages, rather than one flat list. Each group only lists pages that actually exist;
// no placeholder sub-items for features that aren't built.
const groups = [
  { title: null, links: [{ to: '/', label: 'Dashboard', icon: LayoutDashboard }] },
  {
    title: 'Banking',
    links: [
      { to: '/accounts', label: 'Accounts', icon: Wallet },
      { to: '/statement', label: 'Bank Statement', icon: FileText },
      { to: '/transfer', label: 'Transfers', icon: ArrowLeftRight },
      { to: '/loans', label: 'Loans', icon: Percent },
    ],
  },
  {
    title: 'Trading',
    links: [
      { to: '/fx', label: 'FX Markets', icon: TrendingUp },
      { to: '/trading', label: 'Stocks & Shares', icon: LineChart },
      { to: '/crypto', label: 'Crypto', icon: Bitcoin },
    ],
  },
]

const navLinkClass = ({ isActive }: { isActive: boolean }) =>
  `flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition ${
    isActive ? 'bg-primary-500/15 text-primary-300' : 'text-ink-400 hover:bg-white/5 hover:text-white'
  }`

// Deliberately styled apart from the rest of the nav (gradient chip, not a plain icon) — Game
// Mode's money is fake, and the sidebar should make that obvious at a glance, not just in copy.
const gameLinkClass = ({ isActive }: { isActive: boolean }) =>
  `flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition ${
    isActive ? 'bg-white/10 text-white' : 'text-ink-400 hover:bg-white/5 hover:text-white'
  }`

export function Sidebar() {
  const user = useAuth()
  const logout = useLogout()
  const theme = useThemeStore((s) => s.theme)
  const toggleTheme = useThemeStore((s) => s.toggleTheme)

  return (
    <aside className="sticky top-0 flex h-screen w-60 shrink-0 flex-col overflow-y-auto bg-ink-950 text-white">
      <div className="flex items-center gap-2 px-5 py-6">
        <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary-500 font-serif text-lg font-bold text-white">
          B
        </span>
        <div className="leading-tight">
          <p className="text-sm font-bold">BATE BANKING</p>
          <p className="text-[11px] text-ink-400">Banking by Bate</p>
        </div>
      </div>

      <nav className="flex flex-1 flex-col gap-4 px-3">
        {groups.map((group, i) => (
          <div key={group.title ?? i} className="flex flex-col gap-0.5">
            {group.title && (
              <p className="px-3 pb-1 text-[11px] font-semibold uppercase tracking-wide text-ink-600">{group.title}</p>
            )}
            {group.links.map((link) => {
              const Icon = link.icon
              return (
                <NavLink key={link.to} to={link.to} end={link.to === '/'} className={navLinkClass}>
                  <Icon size={18} strokeWidth={2} />
                  {link.label}
                </NavLink>
              )
            })}
          </div>
        ))}

        <div className="flex flex-col gap-0.5">
          <p className="px-3 pb-1 text-[11px] font-semibold uppercase tracking-wide text-ink-600">Play</p>
          <NavLink to="/game" className={gameLinkClass}>
            <span className="flex h-[18px] w-[18px] items-center justify-center rounded-md bg-gradient-to-br from-primary-500 to-secondary-500">
              <Gamepad2 size={12} strokeWidth={2.5} className="text-white" />
            </span>
            Game Mode
          </NavLink>
        </div>
      </nav>

      <div className="flex flex-col gap-2 border-t border-white/5 px-3 py-4">
        <button
          type="button"
          onClick={toggleTheme}
          className="flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium text-ink-400 transition hover:bg-white/5 hover:text-white"
        >
          {theme === 'dark' ? <Sun size={18} strokeWidth={2} /> : <Moon size={18} strokeWidth={2} />}
          {theme === 'dark' ? 'Light mode' : 'Dark mode'}
        </button>
        <NavLink to="/settings" className={navLinkClass}>
          <Settings size={18} strokeWidth={2} />
          Settings
        </NavLink>
        <div className="flex items-center justify-between rounded-lg px-3 py-2">
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
