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

`netWorth = cash + Σ(position.quantity × currentPrice) − Σ(loan.principal + interestOwed)`.

This means a client can never "sneak in" a trade after time's actually up by holding a stale
IN_PROGRESS session open — the server re-derives status from wall-clock time and the live market
on every call, not from whatever the client's own countdown timer says.

Loan interest is computed the same way: `interestOwed` is simple (non-compounding) interest based
on elapsed wall-clock seconds since `originatedAt`, computed fresh on every valuation
(`GameServiceImpl.interestOwed`) rather than accrued and persisted incrementally like the real
`Loan`'s daily scheduled job (`LoanInterestScheduler`) does. A 15-30 minute session has nothing
for a scheduled job to catch up on between reads, so it would be pure overhead here.

There's also deliberately no loan repayment endpoint in Game Mode — a session is too short for
"pay it down before the timer runs out" to be a meaningful choice, so a loan's principal plus
whatever interest has accrued just gets netted out of the final score.

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
