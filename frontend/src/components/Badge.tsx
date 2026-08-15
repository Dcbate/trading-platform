// Explicit dark: overrides here (unlike most of the app) because pastel -50 badge backgrounds
// read as washed-out patches on a dark surface — badges need their own dark palette, not just
// the ink-token repaint that covers text/background/border everywhere else.
const variants = {
  success: 'bg-success-50 text-success-700 dark:bg-success-500/15 dark:text-success-500',
  warning: 'bg-warning-50 text-warning-700 dark:bg-warning-500/15 dark:text-warning-500',
  error: 'bg-error-50 text-error-700 dark:bg-error-500/15 dark:text-error-500',
  neutral: 'bg-ink-50 text-ink-400',
  primary: 'bg-primary-50 text-primary-700 dark:bg-primary-500/15 dark:text-primary-300',
} as const

export function Badge({ children, variant = 'neutral' }: { children: React.ReactNode; variant?: keyof typeof variants }) {
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${variants[variant]}`}>
      {children}
    </span>
  )
}
