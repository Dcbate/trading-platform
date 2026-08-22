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
| Apprentice | £10,000 | £1,000 | 15 min | 5.00% | 0% | ±1% | ±2% | no |
| Trader | £50,000 | £5,000 | 20 min | 8.00% | 0.1% | ±2.5% | ±4% | no |
| Maverick | £100,000 | £2,000 | 25 min | 12.00% | 0.25% | ±4% | ±6.5% | no |
| Rogue | £500,000 | £500 | 30 min | 20.00% | 1% | ±7.5% | ±11.5% | yes |

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

**Stocks occasionally shock — every difficulty, not just Rogue.** Independent of the chaos spike
above (which is Rogue-only and symmetric — an equally likely jump up or down), every stock symbol
at every difficulty tier has a small chance each tick (`STOCK_SHOCK_PROBABILITY`, 0.05%) of an
outsized single-tick move of 12-30%, layered on top of whatever regime is already in play. It's
FX-exempt — a genuine stock-market event, not a currency-pair move. Unlike the symmetric chaos
spike, a shock is weighted 65/35 toward crashes (`STOCK_SHOCK_DOWN_ODDS`): "stocks should crash
sometimes, losing a lot of money" was the original ask this mechanism was built for, so crashes
stay the dominant flavor, but a rally — the same mechanism, same magnitude, just upward — is a real
and genuinely tradeable possibility too, not something you can be on the right side of by holding
short (there's no short-selling here) but very much something you can catch by already holding the
symbol, or buying into, when it fires. At 0.05% per stock per tick, a full 15-30 minute session (10
stocks × 180-360 ticks) expects roughly 1-2 shocks somewhere across the board: rare enough to be a
real event when it lands, not a routine feature of every session.

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

### Loan interest — each elapsed minute is a compressed "day"

Early versions computed `GameLoan` interest the same way the real `Loan` does: a true annualized
rate, accrued against real wall-clock elapsed time. That's realistic but useless in a 15-30 minute
session — a £5,000 loan at 20% APR accrues about 6 pence over an entire game, so the difficulty
table's `loanRateAnnualPercent` was invisible in practice and taking a loan was effectively free
money with no downside.

A next attempt overcorrected: it removed the annualization divisor entirely and applied the
stated annual rate directly per elapsed minute. That made interest accrue absurdly fast instead —
the same £5,000 loan at 20% APR accrued £30,000 over a 30-minute session, more than 6x the
principal.

Game Mode now reuses the real `Loan`'s day-based formula (`principal * rate * days / (100 *
365)`), but feeds it elapsed **minutes**, scaled by `LOAN_INTEREST_DAYS_PER_MINUTE`, in place of
**days** (`GameServiceImpl.pendingInterest`) — so each minute the loan is outstanding accrues as
if that many compressed days had passed at the stated annual rate. That compression factor
started at 1 (about £82 on a £5,000/20%/30-minute loan) but players found it still too small and
too slow to feel like a real cost, so it's now 6 (about £493 for that same example) — enough that
holding a large loan for the whole session visibly drags on net worth, without recreating the
£30,000-in-30-minutes overcorrection above.

### Loans are a credit decision, not a blank cheque

A loan request also now runs a quick "credit check": `GameServiceImpl.takeLoan` computes the
session's current net worth and won't let *total* outstanding loan principal (existing loans plus
the one being requested) exceed 1.5x that net worth (`MAX_LOAN_TO_NET_WORTH_MULTIPLIER`). Asking
for more than that is declined with a 409 (`GameLoanDeclinedException`), not silently capped or
granted anyway — the same way a real lender sizes a credit line off what you're actually worth
rather than handing out an unlimited amount. This closes off the "take an arbitrarily large loan,
it barely costs anything" exploit that used to make reaching the net-worth goal trivial: since the
limit is relative to net worth, borrowing capacity grows only as the player actually grows the
account, not on demand.

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
draw a line through them. A "Buy Max"/"Sell Max" button next to the shares field fills in the most
you can afford (BUY) or everything you currently hold (SELL) — `Math.floor(cash / price)` and the
held position's quantity respectively — and submits the trade immediately, in one click, rather
than just populating the field. The same button exists on the real Stocks & Shares and Crypto
trading pages now too (whole-share rounding for stocks, 8-decimal-place rounding for crypto's
fractional quantities).

**Trading fast on a crash/rally/spike waives the fee.** If a symbol had a market event (§3/§6a)
within the last `REACTION_WINDOW_SECONDS` (10s), a trade on that exact symbol settles with `fee =
0` instead of the difficulty's usual rate (`GameServiceImpl.isReactionTrade`) — a real, mechanical
reward for reading the news ticker and acting on it, not just a cosmetic badge. It reuses the same
event log the ticker already reads, so there's no new state tracked just for this. The frontend
independently re-derives the same "was this a reaction" check from the market events it's already
polling (purely to pick which toast to show — `⚡ Fast reaction` vs. the normal one) — the actual
fee waiver is enforced server-side either way, so a client that gets the timing wrong just sees the
plain toast for what was still a free trade.

**A hot streak tracks consecutive profitable closes.** `GameSessionResponse.currentStreak` is a
live count of consecutive SELLs (most-recent-first) with a positive `realizedPnl`, computed fresh
from `GameTrade` rows on every read (`GameServiceImpl.computeCurrentStreak`) — not a new persisted
counter, and it resets to 0 the moment a closed trade loses money. The frontend shows a 🔥 badge
once it reaches 2, and reaching 5 in a single session earns the "On Fire" achievement (§9a) — a
different read of the same trade history (best run reached at any point, not just the current one).

## 5a. Savings — a slow, safe alternative to trading

`POST /v1/game/sessions/{id}/savings/deposit` and `.../savings/withdraw` move money between
`GameSession.cash` and a new `savingsBalance` field on the session itself — not a separate table,
since a session only ever has one savings "account." Savings earns a flat 3% APR
(`GameServiceImpl.SAVINGS_RATE_ANNUAL_PERCENT`), accrued with the exact same day-based formula (and
the same "each elapsed minute stands in for a compressed day") already used for loan interest and
the goal it solves is symmetric to that one: a loan makes waiting *cost* something if you're
leveraged, savings makes waiting *earn* something if you're sitting on cash you're not ready to
put into a trade yet — an answer to "there's nothing to do while a regime plays out" that doesn't
require watching a chart.

The rate is flat across every difficulty, deliberately not scaled up the way the loan rate is
(5% at Apprentice up to 20% at Rogue) — savings is meant to be the boring-but-safe option
*everywhere*, not something that gets better to abuse the harder the difficulty. It's also
deliberately modest: 3% APR compressed into a 15-30 minute session earns pennies, not pounds — far
below what a single decent trade nets, so parking everything in savings and never trading isn't a
winning strategy, just a way to avoid losing what you're not using yet. `savingsBalance` counts
toward net worth exactly like cash does (`GameServiceImpl.currentSavingsValue`, folded into
`computeNetWorth`), so moving money into savings is never a way to hide it from the goal
calculation.

## 5b. Dividends — a reward for patience

Holding a stock (not an FX pair — no currency pair pays a dividend, the same way none does in real
life) periodically pays out a small amount straight into cash, at a flat 2% APR
(`GameServiceImpl.DIVIDEND_YIELD_ANNUAL_PERCENT`) on the position's current market value, using the
same day-compression formula as savings and loan interest
(`DIVIDEND_DAYS_PER_MINUTE`). It's deliberately a modest bonus stacked on top of a position that's
already capable of a much bigger move on its own — not the primary reason to hold something, just
a reason "hold and wait" is never a strictly worse choice than constantly flipping in and out of a
position.

`GameServiceImpl.settleDividends` runs as the very first line inside `evaluate()` — before
`computeNetWorth`, so a dividend lands in cash before a same-request SELL could delete the position
it's paid against — and, unlike savings/loan interest, there's no separate "live value" shown on
top of cash: a dividend sweeps directly into `GameSession.cash`, so a plain read of cash already
includes it. Each `GamePosition` tracks its own `dividendLastAccrualAt`, set to the moment it was
opened (so a position never gets backdated for time before you actually bought it) and reset every
time a payout settles. A running `GameSession.totalDividendsPaid` is exposed on
`GameSessionResponse` purely for visibility — the frontend shows "Dividends earned: £X" once it's
above zero — since otherwise a small, silent, recurring credit to cash could look like a display
bug rather than the feature it is.

## 5c. Insurance

`POST /v1/game/sessions/{id}/insurance` (body: `{symbol}`) pays a one-time, non-refundable premium
— 3% of the position's current market value (`GameServiceImpl.INSURANCE_PREMIUM_PERCENT_OF_POSITION_VALUE`,
scaling with what's actually being insured, the way a real premium scales with sum insured) — to
floor a held position's downside at 85% of its average cost
(`INSURANCE_FLOOR_PERCENT_OF_AVG_COST`). The player still eats the first 15% of a down move
themselves, the same way a real policy's excess works; insurance protects against a catastrophic
drop beyond that, not every wobble. There's no expiry and no duration field — a policy lasts until
the position is fully sold or the session ends, and buying more of an already-insured symbol
doesn't recompute the floor (a deliberate simplification, not an oversight).

The floor is enforced by one helper, `GameServiceImpl.effectivePrice(position, marketPrice)` —
`marketPrice` unless the position is insured and the real price has fallen below the floor, in
which case the floor wins. Every place a position's price feeds valuation or a sale routes through
it: `computeNetWorth`'s positions sum, `toResponse`'s per-position `marketValue`/`unrealizedPnl`
(so the UI shows the protected value, not a scary real-time dip below what was paid), and
`executeSell`'s proceeds/`realizedPnl` — this last one is where the actual "payout" happens: if the
real price is below the floor when you sell, the sale settles at the floor instead, and the
insurer effectively covers the gap.

## 5d. Wealth manager

`POST /v1/game/sessions/{id}/advisor/hire` pays a one-time fee (2% of the difficulty's starting
cash, `GameServiceImpl.ADVISOR_HIRE_FEE_PERCENT_OF_STARTING_CASH` — scaled per tier so it's a
proportionate bet everywhere, not negligible at Rogue and crushing at Apprentice) to hire an
advisor for the rest of the session. Every 90 seconds
(`ADVISOR_TIP_INTERVAL_SECONDS`) they name a symbol and a side — "BUY AAPL," say — right roughly
62% of the time (`ADVISOR_TIP_ACCURACY`).

The tip isn't a hidden signal: it's built directly on the same trend-regime state a player could
read off the ticker themselves (`GameMarketService.currentTrendUp`), then deliberately flipped
wrong about 38% of the time so blindly following every tip is a losing strategy over a long enough
session, not a cheat code. `GameServiceImpl.settleAdvisorTip` runs from the same `evaluate()` choke
point every entry point already passes through, and — unlike the "live, recompute on every poll"
pattern savings/loan interest use — actually writes the tip down once it's decided, since a tip has
to be a stable, named call to act on rather than a value that reshuffles itself on every refresh.
`GameSessionResponse` carries `advisorHired`, `advisorTipSymbol`, `advisorTipSide`, and
`advisorNextTipAt` so the frontend can show the current tip and a countdown to the next one without
a separate endpoint.

## 5e. Capital gains tax

A flat 15% (`GameServiceImpl.TAX_RATE_PERCENT`, deliberately not scaled by difficulty tier the way
loan/fee rates are — this is a levy on banked profit, not a risk-tier-scaled cost of participating)
is deducted from cash on realized trading profit, checked at most once every 60 real seconds
(`TAX_SETTLEMENT_INTERVAL_SECONDS`). It's a discrete periodic deduction, not a live-computed value
like savings or loan interest — `GameServiceImpl.settleTax` sums every trade's realized P&L
(`GameTradeRepository.sumRealizedPnl`) and taxes only the amount above a running high-water mark
(`GameSession.totalRealizedPnlTaxed`, internal bookkeeping, not exposed on the DTO), so a later
losing trade never claws back tax already paid and the same banked profit is never taxed twice.
Deliberately taxes only trade-based capital gains, not dividend income — a stated simplification,
not an oversight.

`settleTax` runs from the same `evaluate()` choke point every entry point already passes through,
right after `settleDividends` — a session's `totalTaxPaid` is exposed on `GameSessionResponse` for
transparency, and the frontend fires a one-off toast the moment it increases, since a silent
recurring deduction from cash the player didn't cause could easily read as a bug rather than the
feature it is.

**A real bug found in live verification, not by reading the code**: the first version of
`settleTax`'s "not yet due" branch also rewrote `taxLastSettledAt` to `now` before returning —
correct for the *null* case (a session predating this field shouldn't owe backdated tax, so the
clock should start now) but wrong for the *not-yet-due* case, since `evaluate()` runs on every
request and the frontend polls the session every 5 seconds. That meant the 60-second window
restarted on almost every poll and tax could go the entire game without ever actually settling —
invisible in a quick manual test (which happened to leave a long gap between checks) and only
caught by re-verifying against realistic continuous polling. Fixed by only resetting the clock in
the null-initialization branch; a not-yet-due check now returns without touching it.

## 6. Personal stats, and an anonymous leaderboard

`GET /v1/game/stats?clientId=` returns only the calling client's own history: total games, win
rate, best net worth per difficulty, and best realized trade. This one *does* carry a
`@PreAuthorize`-equivalent ownership check (`CallerPrincipal.requireOwner`) — it's personal data,
not public.

`GET /v1/game/leaderboard?difficulty=&sortBy=&clientId=` is different: it's the top 10 sessions for
a difficulty, and — unlike every other read in this app — genuinely public, no ownership check at
all. `sortBy` picks how it's ranked: `NET_WORTH` (the default) includes every finished session, won
or lost, ranked by `finalNetWorth` descending; `FASTEST_WIN` only ever includes `WON` sessions,
ranked by `finishedAt − startedAt` ascending — "fastest loss" isn't a stat worth ranking by, so
losses simply don't appear on that board. Both boards return every entry's `durationSeconds`
regardless of which one is active, so a net-worth board still shows how long a run took and a
fastest-win board still shows what it was worth — nothing is hidden, just ranked differently. The
two reward different play: `FASTEST_WIN` favors decisive, leveraged plays that reach the goal
early and stop; `NET_WORTH` favors squeezing the most out of the entire clock, win or not.

This is only safe to expose publicly because `GameLeaderboardEntry` was designed to carry zero
identity: no clientId, no email, nothing that says *who* got a given score, real or fabricated.
`clientId` is an optional query param used purely to flag which single row (if any) is the caller's
own (`mine: true`) — computed server-side, never returned to the client as a value they could read
off someone else's row. This satisfies the same "never show data that looks real but isn't" rule an
earlier version of this doc used to justify not having a leaderboard at all: the scores here are
completely real (real finished sessions' `finalNetWorth`/duration), and nothing fabricated or
cross-client is ever exposed — the leaderboard just doesn't say whose numbers they are.

## 6a. Market news events

`GET /v1/game/market/events?difficulty=` returns the most recent 20 headline-worthy market
events for a difficulty's shared market — a stock crash or rally (§3) or, in Rogue mode, a chaos
spike — each a short generated headline plus which symbol and when
(`GameMarketServiceImpl.recordEvent`). Routine regime-driven drift is never logged here; only the
two outsized, rare moves are, which is what makes a "news" event actually read as news instead of
noise. Purely cosmetic flavor text either way — it never affects the price math itself, which
already happened by the time the event is recorded.

The frontend's `GamePlayPage` renders this as a persistent ticker pinned to the bottom of the
screen, styled like a real news channel's crawl: always visible for the whole session (not a
banner that pops in only when something just happened) and continuously auto-scrolling via a CSS
keyframe animation, rather than a static list you'd have to notice was updated. It shows the most
recent 8 events that happened since the current session started (older events from before the
player arrived are filtered out client-side by comparing `occurredAt` to the session's
`startedAt`); with nothing yet to report it shows a placeholder line instead of disappearing, so
the ticker itself is a constant fixture of the game screen.

Each `GameMarketEvent` also carries `priceUp`/`magnitudePercent` alongside the free-text headline
— machine-readable duplicates of what the prose already says, so a client can react to a specific
direction/size without parsing the headline string.

### Actionable event prompts

The ticker is deliberately passive — read it or don't. `GamePlayPage` layers an interactive prompt
on top of the same event stream: the moment a new crash/rally/chaos-spike event appears anywhere
in the shared market (not just on whatever symbol happens to be charted), a modal
(`GameEventPromptModal`) pops up with the headline, a live countdown matching the real 10-second
reaction window (`GameServiceImpl.REACTION_WINDOW_SECONDS`), and a compact buy/sell form wired
straight to the normal trade endpoint — the fee waiver is enforced server-side exactly as before,
this only changes how visible the opportunity is. Dismissing or letting the countdown hit zero
just closes it; a dismissed event is remembered (by symbol+timestamp) so it can't reopen itself on
a later poll. This is genuinely different from the flash-pulse on the chart (§ earlier): the flash
only fires for whatever's currently selected and is purely cosmetic, while the prompt watches the
whole market and asks for a decision — catching events you'd otherwise never notice, which was the
actual point of "a lot of waiting around."

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
- No *social* leaderboard with real identities on it — the public leaderboard that does exist
  (§6) is deliberately anonymous, same reasoning as everywhere else in this app about not exposing
  one client's data to another.
- No short-selling, limit/pending orders, or portfolio concentration limits — all discussed as
  possible follow-ons, not built.

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
  limit, the day-based loan interest rate — see §3's sibling note in `GameServiceImpl.pendingInterest`),
  the outcome, every trade in order, every loan taken, and the final per-symbol P&L — is built in
  `GameServiceImpl.buildNarrative`, so the model is grounded in Game Mode's actual rules rather than
  guessing what "won" or "loan interest" means here.
- If `CLAUDE_API_KEY` isn't configured (or the call fails), `debrief.aiGenerated` comes back
  `false` and `summary` is a plain rule-based paragraph computed from the same data
  (`GameServiceImpl.buildFallbackSummary` — best/worst position, final net worth vs. goal, whether
  loans dragged the score down) rather than an empty state. The frontend shows a small "AI-written"
  badge only when `aiGenerated` is true, so a fallback debrief never claims to be something it
  isn't.

## 9a. Achievement badges

The same `GET /v1/game/sessions/{sessionId}/debrief` response also carries `achievements`
(`GameAchievementResponse[]`) — a small set of deterministic, rule-based badges computed in
`GameServiceImpl.computeAchievements` from the same trade/loan/session data the debrief and P&L
breakdown already read. Deliberately *not* AI-decided: the summary text above is Claude's
(or the fallback's) interpretation of the session, but whether a badge was earned is a plain
boolean check with no room for the model to be generous or inconsistent between two similar games.

Current badges: **Debt Free** (won without ever taking a loan), **Leveraged Up** (won after
borrowing more than your entire starting cash), **Day Trader** (10+ trades in one session),
**Home Run** (a single closed trade worth over 25% of starting cash), **Speed Runner** (won with
more than half the session's clock still remaining), **Perfectionist** (every closed trade was
profitable, 3+ trades), **On Fire** (5+ profitable closed trades in a row at any point in the
session — the best run reached, via `GameServiceImpl.computeBestStreak`, not just wherever the
live streak happens to be when the session ends), and **Lesson Learned** (went bankrupt — the one
badge for losing, not winning). A session can earn any number of these at once, including zero —
they're a bonus read on top of win/lose, not a second scoring system. The frontend's `EndScreen`
renders each as a small badge card above the written debrief, shown only when the list is
non-empty.

## 9b. Fun while waiting: game feel

A fast few-symbol market with 5-second ticks means a lot of a session is spent watching a chart
for a regime to develop — these additions exist purely to make that waiting less passive, none of
them touching the real trading/loan/savings mechanics above. (An earlier version of this section
also had a free "will this tick up or down?" mini-game, `QuickGuess`; it was removed after players
found it added noise without adding fun — see the game-mode brainstorm history for the replacement
ideas that came out of that.)

- **Crash/rally flash** — when a market event (§3/§6a) fires for whatever symbol is currently
  charted, the chart card gets a brief amber pulse (`DetailChart`'s `flashing` prop, cleared after
  1.2s). Purely a CSS transition keyed off the same event log the news ticker reads; it changes
  nothing about the price or the trade you'd place in response.
- **Win confetti** — `EndScreen` renders a one-shot burst of falling coloured pieces
  (`GamePlayPage`'s `Confetti` component) when the session ends in `WON`. No animation library:
  randomized once per mount via `useState`'s lazy initializer, then just a CSS `@keyframes` fall.

## 9c. Speed controls

The other half of "a lot of waiting around" isn't missing something to react to (§6a's actionable
prompts cover that) — it's the pace itself. A "Speed up" button (`POST
/v1/game/sessions/{sessionId}/speed-boost`) fast-forwards the market: for 60 seconds, every 5s
scheduler tick (`GameMarketScheduler`) advances each symbol 3 times instead of once
(`GameMarketServiceImpl.currentSpeedMultiplier`/`SPEED_BOOST_TICK_MULTIPLIER`) — a genuine
increase in how much regime progression and shock-roll opportunity happens per real minute, not a
client-side polling trick dressed up to feel faster.

Because the market is one shared simulation per difficulty tier (§3), a boost is necessarily
tier-wide: triggering it speeds up the market for every player currently in that difficulty, not
just the session that requested it. That's stated here deliberately rather than left as a surprise
— it's the honest trade-off of reusing the existing shared-market architecture instead of forking
per-session state, which would have been a much bigger change for the same result. The boost
itself is free; the only real cost is a 90-second per-session cooldown
(`GameServiceImpl.SPEED_BOOST_COOLDOWN_SECONDS`), long enough that one player mashing the button
can't keep the tier permanently sped up for everyone else in it. `GameSessionResponse` carries
`speedBoostAvailableAt` (when this session can next activate one) and `marketSpeedMultiplier`
(the tier's current live speed, 1 normally) so the frontend can show a countdown or a "3x speed"
badge without a separate poll.
