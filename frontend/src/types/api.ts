// Mirrors the backend's record DTOs field-for-field (see
// src/main/java/com/dcbate/tradingplatform/**/api/dto/*.java) — kept hand-written rather than
// generated from the OpenAPI spec since the surface is small and stable.
//
// Money fields (BigDecimal on the backend) come over the wire as plain JSON numbers — Jackson's
// default, since nothing overrides it — so they're typed `number` here, not `string`. That's a
// real precision tradeoff for a banking UI (IEEE-754 doubles aren't exact for currency); it's fine
// for display purposes at demo scale, but a production frontend would want the backend emitting
// BigDecimal as strings instead of trying to fix this on the client.

import { accountTypeLabel } from '../lib/format'

export type AccountType = 'CHECKING' | 'SAVINGS' | 'FX_TRADING' | 'BROKERAGE' | 'CRYPTO'
export type AccountStatus = 'ACTIVE' | 'FROZEN' | 'CLOSED'
export type TransferStatus = 'COMPLETED' | 'FAILED'
export type LoanStatus = 'ACTIVE' | 'PAID_OFF'
export type LoanProductType = 'PERSONAL_SHORT' | 'PERSONAL_LONG' | 'AUTO' | 'STUDENT' | 'MORTGAGE'
export type OrderSide = 'BUY' | 'SELL'
export type OrderStatus = 'PENDING' | 'VALIDATED' | 'PARTIALLY_FILLED' | 'FILLED' | 'REJECTED'

export interface AuthResponse {
  clientId: string
  email: string
}

export interface AccountResponse {
  accountId: string
  clientId: string
  accountType: AccountType
  currency: string
  nickname: string | null
  balance: number
  status: AccountStatus
  createdAt: string
}

// Same type/currency accounts are otherwise indistinguishable in a UI. Previously fell back to a
// slice of the account's raw UUID to disambiguate — that's still an internal identifier, not
// something a client reads as meaningful, so unnamed duplicates are numbered by account age
// instead (oldest first), never by id.
export function accountLabel(account: AccountResponse, allAccounts: AccountResponse[] = []): string {
  const base = `${accountTypeLabel(account.accountType)} · ${account.currency}`
  if (account.nickname) return `${account.nickname} (${base})`
  const unnamedSiblings = allAccounts
    .filter((a) => !a.nickname && a.accountType === account.accountType && a.currency === account.currency)
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt))
  if (unnamedSiblings.length <= 1) return base
  const index = unnamedSiblings.findIndex((a) => a.accountId === account.accountId)
  return index >= 0 ? `${base} (${index + 1})` : base
}

export interface CurrencyBalance {
  currency: string
  totalBalance: number
  accountCount: number
}

export interface BalanceSummaryResponse {
  clientId: string
  activeAccountCount: number
  balances: CurrencyBalance[]
}

export interface TransferResponse {
  transferId: string
  fromAccountId: string
  toAccountId: string
  fromClientId: string
  toClientId: string
  amount: number
  status: TransferStatus
  createdAt: string
}

export interface LoanProductResponse {
  code: LoanProductType
  displayName: string
  interestRateAnnualPercent: number
  termMonths: number
}

export interface LoanResponse {
  loanId: string
  clientId: string
  accountId: string
  productType: LoanProductType
  principal: number
  outstandingPrincipal: number
  interestRateAnnualPercent: number
  termMonths: number
  accruedInterest: number
  status: LoanStatus
  createdAt: string
}

export interface PriceResponse {
  symbol: string
  price: number
}

export interface OrderResponse {
  orderId: string
  clientId: string
  accountId: string | null
  currencyPair: string
  side: OrderSide
  quantity: number
  price: number
  status: OrderStatus
  createdAt: string
  filledAt: string | null
}

export interface PositionResponse {
  positionId: string
  accountId: string
  clientId: string
  symbol: string
  quantity: number
  avgCost: number
  updatedAt: string
}

export type StatementEntryType =
  | 'FX_ORDER'
  | 'PAYMENT'
  | 'TRANSFER_OUT'
  | 'TRANSFER_IN'
  | 'DEPOSIT'
  | 'WITHDRAWAL'
  | 'CONVERSION'
  | 'ACCOUNT_CLOSURE'
  | 'LOAN_ORIGINATED'
  | 'LOAN_REPAYMENT'

export interface BankStatementEntry {
  occurredAt: string
  type: StatementEntryType
  description: string
  amount: number | null
  currency: string | null
  reference: string
}

export interface BankStatementResponse {
  clientId: string
  entries: BankStatementEntry[]
}

export interface ApiError {
  timestamp: string
  status: number
  error: string
  messages: string[]
  path: string
}

// --- Game Mode — a separate simulated economy, see docs/GAME_MODE.md. ---

export type GameDifficultyCode = 'APPRENTICE' | 'TRADER' | 'MAVERICK' | 'ROGUE'
export type GameStatus = 'IN_PROGRESS' | 'WON' | 'LOST_TIME' | 'LOST_BANKRUPT'

export interface GameDifficultyResponse {
  code: GameDifficultyCode
  displayName: string
  goalAmount: number
  startingCash: number
  durationMinutes: number
  loanRateAnnualPercent: number
  feePercent: number
  fxVolatilityPercent: number
  stockVolatilityPercent: number
  chaosMode: boolean
}

export interface GamePositionResponse {
  symbol: string
  quantity: number
  avgCost: number
  currentPrice: number
  marketValue: number
  unrealizedPnl: number
  insured: boolean
  insuranceFloorPrice: number | null
}

export interface GameLoanResponse {
  gameLoanId: string
  principal: number
  outstandingPrincipal: number
  interestOwed: number
  rateAnnualPercent: number
  originatedAt: string
}

export interface GameSessionResponse {
  sessionId: string
  clientId: string
  difficulty: GameDifficultyCode
  status: GameStatus
  cash: number
  savingsBalance: number
  netWorth: number
  goalAmount: number
  timeRemainingSeconds: number
  currentStreak: number
  startedAt: string
  endsAt: string
  speedBoostAvailableAt: string
  marketSpeedMultiplier: number
  advisorHired: boolean
  advisorTipSymbol: string | null
  advisorTipSide: OrderSide | null
  advisorNextTipAt: string | null
  totalDividendsPaid: number
  totalTaxPaid: number
  positions: GamePositionResponse[]
  loans: GameLoanResponse[]
}

export interface GameTradeResponse {
  tradeId: string
  symbol: string
  side: OrderSide
  quantity: number
  price: number
  fee: number
  realizedPnl: number | null
  createdAt: string
}

export interface GamePriceResponse {
  symbol: string
  price: number
}

export interface GameSymbolPerformanceResponse {
  symbol: string
  realizedPnl: number
  unrealizedPnl: number
  totalPnl: number
  quantityHeld: number
}

export interface GameAchievementResponse {
  title: string
  description: string
}

export interface GameDebriefResponse {
  summary: string
  aiGenerated: boolean
  symbolPerformance: GameSymbolPerformanceResponse[]
  achievements: GameAchievementResponse[]
}

export interface GameDifficultyStat {
  difficulty: GameDifficultyCode
  gamesPlayed: number
  wins: number
  bestNetWorth: number | null
}

export interface GameStatsResponse {
  clientId: string
  totalGames: number
  wins: number
  winRatePercent: number
  bestTradePnl: number | null
  perDifficulty: GameDifficultyStat[]
}

export type GameLeaderboardSortBy = 'NET_WORTH' | 'FASTEST_WIN'

// No clientId here on purpose — a public leaderboard shows scores, never who's behind them (see
// GameLeaderboardEntry's own javadoc on the backend). `mine` is the only tie back to the caller.
export interface GameLeaderboardEntry {
  rank: number
  netWorth: number
  durationSeconds: number
  status: GameStatus
  mine: boolean
}

export interface GameLeaderboardResponse {
  difficulty: GameDifficultyCode
  sortBy: GameLeaderboardSortBy
  entries: GameLeaderboardEntry[]
}

export interface GameMarketEventResponse {
  symbol: string
  headline: string
  priceUp: boolean
  magnitudePercent: number
  occurredAt: string
}
