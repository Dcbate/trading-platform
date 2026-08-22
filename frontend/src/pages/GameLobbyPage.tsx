import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import toast from 'react-hot-toast'
import { Gamepad2, Trophy, Target, X, Medal } from 'lucide-react'
import { apiErrorMessage } from '../api/client'
import { useGameClientId } from '../hooks/useGameClientId'
import { useGameDifficulties, useGameLeaderboard, useGameStats, useStartGame } from '../hooks/useGame'
import { formatCountdown, formatMoney } from '../lib/format'
import { pageTitle, listSection, input } from '../lib/styles'
import type { GameDifficultyCode, GameDifficultyResponse, GameLeaderboardSortBy } from '../types/api'

const HINT_SEEN_KEY = 'bate-banking-game-hint-seen'

const DIFFICULTY_STYLE: Record<GameDifficultyCode, string> = {
  APPRENTICE: 'from-success-600 to-secondary-600',
  TRADER: 'from-primary-600 to-secondary-600',
  MAVERICK: 'from-warning-600 to-error-600',
  ROGUE: 'from-error-600 to-ink-900',
}

function DifficultyCard({ difficulty, onPlay, isPending }: { difficulty: GameDifficultyResponse; onPlay: () => void; isPending: boolean }) {
  return (
    <button
      type="button"
      onClick={onPlay}
      disabled={isPending}
      className={`group flex flex-col gap-3 rounded-2xl bg-gradient-to-br p-5 text-left text-white shadow-lg shadow-ink-900/10 transition hover:-translate-y-0.5 disabled:cursor-not-allowed disabled:opacity-60 ${DIFFICULTY_STYLE[difficulty.code]}`}
    >
      <div className="flex items-center justify-between">
        <p className="text-lg font-bold">{difficulty.displayName}</p>
        {difficulty.chaosMode && (
          <span className="rounded-full bg-white/20 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wide">Chaos</span>
        )}
      </div>
      <p className="font-mono text-2xl font-bold">{formatMoney(difficulty.goalAmount, 'GBP')}</p>
      <p className="text-xs text-white/70">Goal, from {formatMoney(difficulty.startingCash, 'GBP')} in {difficulty.durationMinutes} min</p>
      <div className="mt-1 flex flex-wrap gap-x-4 gap-y-1 text-xs text-white/80">
        <span>Loans {difficulty.loanRateAnnualPercent}% APR</span>
        <span>Fees {difficulty.feePercent}%</span>
        <span>FX ±{difficulty.fxVolatilityPercent.toFixed(1)}%</span>
        <span>Stocks ±{difficulty.stockVolatilityPercent.toFixed(1)}%</span>
      </div>
      <span className="mt-2 inline-flex w-fit items-center rounded-lg bg-white/20 px-3 py-1.5 text-sm font-semibold transition group-hover:bg-white/30">
        {isPending ? 'Starting…' : 'Play'}
      </span>
    </button>
  )
}

const STATUS_LABEL: Record<string, string> = {
  WON: 'Won',
  LOST_TIME: 'Time out',
  LOST_BANKRUPT: 'Bankrupt',
}

const SORT_BY_LABEL: Record<GameLeaderboardSortBy, string> = {
  NET_WORTH: 'Best net worth',
  FASTEST_WIN: 'Fastest win',
}

function Leaderboard({ difficulties, clientId }: { difficulties: GameDifficultyResponse[] | undefined; clientId: string }) {
  const [difficulty, setDifficulty] = useState<GameDifficultyCode>('APPRENTICE')
  const [sortBy, setSortBy] = useState<GameLeaderboardSortBy>('NET_WORTH')
  const { data: leaderboard } = useGameLeaderboard(difficulty, sortBy, clientId)

  return (
    <div>
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <Medal size={16} strokeWidth={2} className="text-warning-600" />
          <h2 className="text-base font-semibold text-ink-900">Leaderboard</h2>
        </div>
        <div className="flex items-center gap-2">
          <div className="flex overflow-hidden rounded-lg border border-ink-100">
            {(Object.keys(SORT_BY_LABEL) as GameLeaderboardSortBy[]).map((option) => (
              <button
                key={option}
                type="button"
                onClick={() => setSortBy(option)}
                className={`px-3 py-2 text-xs font-semibold transition ${sortBy === option ? 'bg-primary-600 text-white' : 'bg-surface text-ink-600 hover:bg-canvas'}`}
              >
                {SORT_BY_LABEL[option]}
              </button>
            ))}
          </div>
          <select value={difficulty} onChange={(e) => setDifficulty(e.target.value as GameDifficultyCode)} className={`w-40 ${input}`}>
            {difficulties?.map((d) => (
              <option key={d.code} value={d.code}>
                {d.displayName}
              </option>
            ))}
          </select>
        </div>
      </div>
      {sortBy === 'FASTEST_WIN' && (
        <p className="mb-2 text-xs text-ink-400">Wins only — a fast loss isn't something to rank.</p>
      )}
      <div className={listSection}>
        {!leaderboard || leaderboard.entries.length === 0 ? (
          <p className="py-4 text-sm text-ink-400">
            {sortBy === 'FASTEST_WIN' ? 'No wins yet at this difficulty — be the first.' : 'No finished games yet at this difficulty — be the first.'}
          </p>
        ) : (
          leaderboard.entries.map((entry) => (
            <div
              key={entry.rank}
              className={`flex items-center justify-between py-2.5 text-sm ${entry.mine ? 'rounded-lg bg-primary-50 px-2 dark:bg-primary-500/10' : ''}`}
            >
              <span className="flex items-center gap-2">
                <span className="w-5 shrink-0 font-mono font-semibold text-ink-400">#{entry.rank}</span>
                <span className={`font-medium ${entry.mine ? 'text-primary-700 dark:text-primary-300' : 'text-ink-700'}`}>
                  {entry.mine ? 'You' : 'Player'}
                </span>
              </span>
              <span className="flex items-center gap-3">
                <span className="text-xs text-ink-400">{STATUS_LABEL[entry.status] ?? entry.status}</span>
                <span className="font-mono text-xs text-ink-400">{formatCountdown(entry.durationSeconds)}</span>
                <span className="font-mono font-semibold text-ink-900">{formatMoney(entry.netWorth, 'GBP')}</span>
              </span>
            </div>
          ))
        )}
      </div>
    </div>
  )
}

export function GameLobbyPage() {
  const { clientId, isGuest } = useGameClientId()
  const navigate = useNavigate()
  const { data: difficulties } = useGameDifficulties()
  const { data: stats } = useGameStats(clientId)
  const startGame = useStartGame()
  const [showHint, setShowHint] = useState(() => typeof window !== 'undefined' && !localStorage.getItem(HINT_SEEN_KEY))

  function dismissHint() {
    localStorage.setItem(HINT_SEEN_KEY, '1')
    setShowHint(false)
  }

  function play(difficulty: GameDifficultyCode) {
    startGame.mutate(
      { clientId, difficulty },
      {
        onSuccess: (session) => navigate(`/game/play/${session.sessionId}`),
        onError: (error) => toast.error(apiErrorMessage(error)),
      },
    )
  }

  return (
    <div className="flex flex-col gap-8">
      <div className="flex items-center gap-3">
        <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-primary-600 to-secondary-600 text-white">
          <Gamepad2 size={22} strokeWidth={2} />
        </span>
        <div>
          <h1 className={pageTitle}>Game Mode</h1>
          <p className="text-sm text-ink-400">Practice with fake money and a real countdown — nothing here touches your real accounts.</p>
        </div>
      </div>

      {isGuest && (
        <p className="text-xs text-ink-400">
          Playing as a guest — your stats are saved on this device. <Link to="/signup" className="font-semibold text-primary-600 hover:text-primary-700">Sign up</Link> to keep them for good and unlock real banking too.
        </p>
      )}

      {showHint && (
        <div className="flex items-start gap-3 rounded-xl border border-primary-100 bg-primary-50 p-4 text-sm text-ink-700 dark:border-primary-500/20 dark:bg-primary-500/10">
          <Target size={18} strokeWidth={2} className="mt-0.5 shrink-0 text-primary-600 dark:text-primary-300" />
          <div className="flex-1">
            <p className="font-semibold text-ink-900">How to play</p>
            <p className="mt-1 text-ink-600">
              Pick a difficulty below. Take a loan to trade with more than your starting cash, buy FX or shares while
              the price is low, sell when it moves your way, and repeat until your net worth hits the goal — before
              the timer runs out or you go bankrupt.
            </p>
          </div>
          <button type="button" onClick={dismissHint} className="shrink-0 text-ink-400 hover:text-ink-900">
            <X size={16} strokeWidth={2} />
          </button>
        </div>
      )}

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {difficulties?.map((d) => (
          <DifficultyCard key={d.code} difficulty={d} onPlay={() => play(d.code)} isPending={startGame.isPending} />
        ))}
      </div>

      {stats && stats.totalGames > 0 && (
        <div>
          <div className="mb-2 flex items-center gap-2">
            <Trophy size={16} strokeWidth={2} className="text-warning-600" />
            <h2 className="text-base font-semibold text-ink-900">Your stats</h2>
          </div>
          <div className={listSection}>
            <div className="flex flex-wrap gap-x-8 gap-y-2 py-4 text-sm">
              <div>
                <p className="text-xs text-ink-400">Games played</p>
                <p className="font-mono text-lg font-semibold text-ink-900">{stats.totalGames}</p>
              </div>
              <div>
                <p className="text-xs text-ink-400">Win rate</p>
                <p className="font-mono text-lg font-semibold text-ink-900">{stats.winRatePercent.toFixed(0)}%</p>
              </div>
              {stats.bestTradePnl !== null && (
                <div>
                  <p className="text-xs text-ink-400">Best trade</p>
                  <p className="font-mono text-lg font-semibold text-success-600">{formatMoney(stats.bestTradePnl, 'GBP')}</p>
                </div>
              )}
            </div>
            {stats.perDifficulty.map((d) => (
              <div key={d.difficulty} className="flex items-center justify-between py-3 text-sm">
                <span className="font-medium text-ink-700">{difficulties?.find((diff) => diff.code === d.difficulty)?.displayName ?? d.difficulty}</span>
                <span className="text-ink-400">
                  {d.wins}/{d.gamesPlayed} won
                  {d.bestNetWorth !== null && <> · best {formatMoney(d.bestNetWorth, 'GBP')}</>}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      <Leaderboard difficulties={difficulties} clientId={clientId} />
    </div>
  )
}
