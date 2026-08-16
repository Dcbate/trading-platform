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

export type AccountType = 'CHECKING' | 'SAVINGS' | 'FX_TRADING' | 'BROKERAGE'
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

// Same type/currency accounts are otherwise indistinguishable in a UI — falls back to a short
// slice of the id when the client didn't set a nickname.
export function accountLabel(account: AccountResponse): string {
  const base = `${accountTypeLabel(account.accountType)} · ${account.currency}`
  return account.nickname ? `${account.nickname} (${base})` : `${base} · ${account.accountId.slice(0, 8)}`
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
  netWorth: number
  goalAmount: number
  timeRemainingSeconds: number
  startedAt: string
  endsAt: string
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
