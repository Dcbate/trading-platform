# Game Mode

## 1. What this is

A practice mode: pick a difficulty, start with seed cash, and try to grow it to a goal amount
before the timer runs out, using loans, FX trading, and stock trading — the same three tools the
real bank offers, just in a sandbox with no real money and a much faster clock.

It's a completely separate economy from the rest of the app. `game_sessions`,
`game_positions`, `game_loans`, and `game_trades` don't reference `accounts`, `orders`, or `loans`
at all, and Game Mode's simulated market (`GameMarketServiceImpl`) is a different instance from
the real FX/stock desk's `PriceFeedService`. A game session can never touch a real account's
balance, and a game trade can never move a real price. This was a deliberate call, not an
oversight — reusing the real tables would mean either fabricating a way for game money to look
like real money, or building a parallel "is this real or a game row" flag through every real
banking screen. Keeping them apart means there's zero chance the two ever get confused.

## 2. Difficulty tiers

| Difficulty | Goal | Start | Time | Loan rate | Fee | FX volatility | Stock volatility | Chaos |
|---|---|---|---|---|---|---|---|---|
| Apprentice | $10,000 | $1,000 | 15 min | 5.00% | 0% | ±1% | ±2% | no |
| Trader | $50,000 | $5,000 | 20 min | 8.00% | 0.1% | ±2.5% | ±4% | no |
| Maverick | $100,000 | $2,000 | 25 min | 12.00% | 0.25% | ±4% | ±6.5% | no |
| Rogue | $500,000 | $500 | 30 min | 20.00% | 1% | ±7.5% | ±11.5% | yes |

These live as embedded fields on the `GameDifficulty` enum (`domain/GameDifficulty.java`), not in
`application.yml` — they're fixed game rules, not deployment config.

There's no separate "leverage" or "margin call" mechanic. A loan already lets a player trade with
more than their starting cash — that's what leverage *is* — so a second multiplier system on top
would model the same thing twice. "Bankrupt" is simply net worth going negative (see §4);
"Rogue mode's chaos" is the one thing that genuinely needed its own flag: an occasional
outsized price jump on top of its already-wide normal moves (`GameMarketServiceImpl.advance`).

## 3. The simulated market

`GameMarketServiceImpl` runs one independent price feed per difficulty tier — everyone playing
"Trader" sees the same live prices, same as the real FX desk's clients all see the same quote.
Ticks every 5 seconds (`GameMarketScheduler`), in memory only (no Redis, no Kafka — a game
session never outlives 30 minutes, so nothing here needs to survive a restart or be visible
outside this one JVM). 3 FX pairs (EUR/USD, GBP/USD, USD/JPY) and 10 stocks (AAPL, MSFT, NVDA,
GOOGL, AMZN, TSLA, META, NFLX, INTC, AMD), seeded from the same kind of round starting points the
real desk uses — again, not real quotes.

**Prices move in regimes, not a pure random walk.** The first version of this class gave every
symbol an equal, independent chance of moving up or down on every tick — a textbook symmetric
random walk. That has zero expected drift by construction: no strategy beats it, because there's
nothing to read. Actually playing the game with that model confirmed it — net worth just wandered
near the starting cash for the entire session regardless of what you did, on every difficulty,
including Apprentice. A random walk isn't a *bug* in the mathematical sense (it's what "no
information advantage" is supposed to look like), but it makes a bad *game*, since the whole point
is to practice reading a market and acting on it.

The fix: each symbol now runs through a **trend regime** lasting 10-40 ticks (50 seconds to a few
minutes at the current tick rate) with a persistent directional bias, layered with a smaller
amount of tick-to-tick jitter for texture. When a regime ends, a new one rolls with its own random
direction and magnitude. This is the thing a player can actually *read* off the ticker — a
symbol printing green for the last few ticks is showing you its current regime, and momentum over
seconds-to-minutes timeframes is a real, well-documented feature of actual markets, not an
invented mechanic. Nothing is rigged to always go up: direction is still a coin flip at the start
of each regime, and every symbol trends independently. What changed is that the coin flip now
*persists* long enough to be tradeable, instead of re-flipping every single tick.

Regime magnitude is drawn from the upper half of the existing `fxVolatility`/`stockVolatility`
band per difficulty (see the table above) — no new difficulty parameters were needed, the fields
just mean "how strong is a regime's trend" instead of "how big is one tick's random jump."

## 4. Win, lose, and how a session gets evaluated

There's no background job deciding when a session ends. Every read (`GET /v1/game/sessions/{id}`)
and every action (loan, trade) calls `GameServiceImpl.evaluate` first, which recomputes net worth
and checks all three end conditions before anything else happens:

- **Win**: `netWorth >= difficulty.goalAmount`
- **Bankrupt**: `netWorth < 0`
- **Time's up**: `now >= session.endsAt`

`netWorth = cash + Σ(position.quantity × currentPrice) − Σ(loan.outstandingPrincipal + interestOwed)`.

This means a client can never "sneak in" a trade after time's actually up by holding a stale
IN_PROGRESS session open — the server re-derives status from wall-clock time and the live market
on every call, not from whatever the client's own countdown timer says.

### Loan interest — per minute, not annualized

Early versions computed `GameLoan` interest the same way the real `Loan` does: a true annualized
rate, accrued continuously from `originatedAt`. That's realistic but useless in a 15-30 minute
session — a £5,000 loan at 20% APR accrues about 6 pence over an entire game, so the difficulty
table's `loanRateAnnualPercent` was invisible in practice and taking a loan was effectively free
money with no downside.

Game Mode now treats the stated rate as **"this percentage of the outstanding principal, per
minute held"**, not per year — the annualization divisor was removed entirely
(`GameServiceImpl.pendingInterest`). A £2,000 loan at 5% now accrues £100 for every minute it's
left unpaid, which is a real, felt tradeoff inside a short session: borrow to trade bigger, but
the clock is now working against you too.

Because interest actually matters now, it also needs to be repayable rather than just netted out
at the end — see below. That in turn means a `GameLoan`'s interest can't be computed fresh on
every read anymore (an outstanding balance can shrink between reads via a partial repayment, so
"recompute from `originatedAt`" would be wrong). `GameLoan` now tracks `outstandingPrincipal`,
`accruedInterest`, and `lastAccrualAt`: `interestOwed` = `accruedInterest` (already settled) plus
`pendingInterest` (computed live from `lastAccrualAt` to now). A repayment first calls
`settleAccrual` to fold pending interest into `accruedInterest`, then applies the payment —
interest first, principal second, same order the real `Loan.repay` uses. There's still no
scheduled job here: settlement only happens lazily, at repayment or when a session ends, not on
every 5-second poll, since a DB write on every poll for a value nobody's reading yet would be
pure overhead.

### Repaying a loan

`POST /v1/game/sessions/{sessionId}/loans/{loanId}/repay` (`GameServiceImpl.repayLoan`) accepts
any positive amount; if it's more than what's actually owed (`outstandingPrincipal +
interestOwed`), only what's owed is taken — same rule the real loan repayment uses
(`GameLoanRepayRequest`'s Javadoc). A loan that's fully paid off (`outstandingPrincipal` and
`accruedInterest` both zero) is filtered out of the session's `loans` list on the next read, so it
just disappears from the UI rather than lingering as a zero-balance row.

## 5. Trades

`POST /v1/game/sessions/{id}/trades` fills instantly at the current simulated price — there's no
order book and no matching engine, unlike the real FX/stock desk (see
[TRADING_SYSTEM.md](TRADING_SYSTEM.md)). That's intentional: a single-player game has no
counterparty to match against, so simulating a full order book would just be extra machinery with
nothing on the other side of it.

A BUY debits `notional + fee` from cash and folds into the position's weighted-average cost, same
shape as the real desk's `ExecutionServiceImpl.settleFill`. A SELL credits `notional − fee`,
reduces (or closes) the position, and records `realizedPnl = (fillPrice − avgCost) × quantity −
fee` on the trade row — that's the number "best trade" in personal stats reads from.

Clicking a symbol on the game screen charts it — the frontend accumulates each poll's price into
a rolling history per symbol (the same pattern `useLivePrices` already uses for the real FX/stock
pages, just kept local to `GamePlayPage` instead of a shared hook, since Game Mode's price shape
and polling cadence are its own thing). Nothing is persisted server-side for this; it's exactly
the same live prices the trade form already reads, just kept around in the browser long enough to
draw a line through them. A "Max" button next to the shares field fills in the most you can
afford (BUY) or everything you currently hold (SELL) — `Math.floor(cash / price)` and the held
position's quantity respectively, both already-available numbers, not a new calculation.

## 6. Personal stats, not a leaderboard

`GET /v1/game/stats?clientId=` returns only the calling client's own history: total games, win
rate, best net worth per difficulty, and best realized trade. There's no global leaderboard with
other players' names on it.

That's a direct consequence of a rule this app has followed everywhere else: never show data that
looks real but isn't. A leaderboard needs either real other players' scores (which would mean
exposing every client's game results to every other client — this app doesn't expose any other
client's data anywhere, see `CallerPrincipal.requireOwner`) or invented names and scores
(fabricated data, the same thing [DESIGN_DECISIONS.md](DESIGN_DECISIONS.md) already rules out for
FX bid/ask spreads and stock fundamentals). "Your best runs" is the honest version of a
leaderboard for a single-player game with no other real players in it yet.

## 7. Playable without an account

Game Mode doesn't require logging in — `/v1/game/**` is on `SecurityConfig`'s permitAll list (see
`CallerPrincipal`'s javadoc for exactly what that means for `Authentication` resolution), and none
of `GameController`'s methods carry a `@PreAuthorize`, unlike every other client-facing endpoint in
the app. A first-time visitor gets a `guest-<uuid>` id generated once and persisted in
`localStorage` (`frontend/src/lib/guestId.ts`), used as their `clientId` exactly the way a real
client's would be — so "your stats" (§6) still means something across reloads, just scoped to that
browser rather than a real account. `useGameClientId` prefers a real logged-in `clientId` when one
exists, so nothing changes for a logged-in client playing normally.

This was a deliberate reversal of an earlier, more restrictive scoping decision. The tradeoff is
real: a guest's stats live only in that browser's `localStorage` (clearing site data loses them,
and they don't follow you to another device) — the lobby says as much and links to sign-up for
anyone who wants that history to actually persist.

## 8. What's out of scope, stated not silently dropped

- No multi-step in-app tutorial modal — a short first-time hint banner on the game screen instead.
- No "Share" button — nothing real to share to.
- No literal leverage multiplier or margin-call liquidation (see §2).
- No global/social leaderboard (see §6).

## 9. AI-written debrief

`GET /v1/game/sessions/{sessionId}/debrief` (`GameServiceImpl.getDebrief`) is only available once a
session has ended — a 409 (`GameSessionStillInProgressException`) otherwise, since "why did you
win/lose" isn't a meaningful question to ask mid-session. It returns two things: a short written
debrief and a per-symbol P&L breakdown (`GameSymbolPerformanceResponse`, aggregated from
`GameTrade.realizedPnl` for closed positions and a live unrealized calculation for whatever's still
held) — the frontend's `EndScreen` charts the latter as horizontal red/green bars ("biggest gains
and losses"), and shows the former as plain text.

The write-up itself follows the exact same interface pattern already used for anomaly enrichment
(`ai/mcp/AnomalyDetectorImpl`) and payment-outcome summaries (`ai/mcp/ClaudeSummarizerImpl`) — a
third, independent AI integration, not a special case:

- `ai/GameCoach` (interface) → `ai/mcp/GameCoachImpl` (real implementation), sharing the same
  `McpToolClient` as the other two — none of the three call Anthropic directly; all three call
  `bate-mcp-server`'s `debrief_game_session`/`summarize_anomaly`/`summarize_payment` tools over
  real MCP (see `bate-mcp-server/README.md`). `bate-mcp-server` itself uses a different prompt and
  a longer response for the debrief tool (400 tokens vs. 200 — a debrief is a few sentences of
  analysis, not a one-line notification).
- The full session narrative handed to Claude — the difficulty's rules (goal, starting cash, time
  limit, the per-minute loan interest rate), the outcome, every trade in order, every loan taken,
  and the final per-symbol P&L — is built in `GameServiceImpl.buildNarrative`, so the model is
  grounded in Game Mode's actual rules rather than guessing what "won" or "loan interest" means
  here.
- If `CLAUDE_API_KEY` isn't configured (or the call fails), `debrief.aiGenerated` comes back
  `false` and `summary` is a plain rule-based paragraph computed from the same data
  (`GameServiceImpl.buildFallbackSummary` — best/worst position, final net worth vs. goal, whether
  loans dragged the score down) rather than an empty state. The frontend shows a small "AI-written"
  badge only when `aiGenerated` is true, so a fallback debrief never claims to be something it
  isn't.
