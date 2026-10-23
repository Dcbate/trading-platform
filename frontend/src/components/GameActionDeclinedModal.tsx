import type { ComponentType } from 'react'
import { Ban } from 'lucide-react'
import { btnPrimary, card } from '../lib/styles'

// Shared shell for "stop and read this" moments — a declined action or an explanation of a
// mechanic the player just triggered for the first time — neither of which belongs in a toast
// that's gone in a few seconds. Two thin exports below give each tone its own icon/color without
// duplicating the modal itself.
export function GameInfoModal({
  icon: Icon,
  tone,
  title,
  message,
  onDismiss,
}: {
  icon: ComponentType<{ size?: number; strokeWidth?: number }>
  tone: 'error' | 'info'
  title: string
  message: string
  onDismiss: () => void
}) {
  const badgeClass = tone === 'error' ? 'bg-error-50 text-error-600 dark:bg-error-500/15' : 'bg-primary-50 text-primary-600 dark:bg-primary-500/15'
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink-950/60 px-4" role="dialog" aria-modal="true">
      <div className={`${card} w-full max-w-sm`}>
        <div className="flex items-start gap-3">
          <div className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-full ${badgeClass}`}>
            <Icon size={18} strokeWidth={2} />
          </div>
          <div>
            <p className="text-sm font-semibold text-ink-900">{title}</p>
            <p className="mt-1 text-sm leading-relaxed text-ink-600">{message}</p>
          </div>
        </div>
        <button type="button" onClick={onDismiss} className={`mt-4 w-full ${btnPrimary}`}>
          Got it
        </button>
      </div>
    </div>
  )
}

// A credit/action decision deserves an explanation the player can actually read. The backend's
// exception messages here are already written for a player (real money amounts, no session UUID
// — see e.g. GameLoanDeclinedException's javadoc), so this modal's only job is to give the message
// a moment's worth of real screen space instead of a fleeting toast.
export function GameActionDeclinedModal({ title, message, onDismiss }: { title: string; message: string; onDismiss: () => void }) {
  return <GameInfoModal icon={Ban} tone="error" title={title} message={message} onDismiss={onDismiss} />
}
