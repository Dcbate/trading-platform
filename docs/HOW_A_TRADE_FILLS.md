# How a Trade Actually Fills — and How to Demo It Yourself

This doc exists because of a real conversation, not a hypothetical one: I placed a TSLA buy order,
it sat at `VALIDATED` and never moved, and working out *why* — live, by actually testing it rather
than guessing — turned up a genuine bug along the way. Everything below, including the order IDs
and timestamps, is from that real session. Nothing here is a made-up example.

For the technical spec (the matching algorithm, the risk checks, the full order-status table), see
[TRADING_SYSTEM.md](TRADING_SYSTEM.md). This doc is the plain-English "why did my order not fill,
and how do I make one fill on purpose" walkthrough that one doesn't fully answer.

---

# Part 1 — In Plain English

## What actually happens when you click "Buy"

Clicking buy doesn't buy anything by itself. It creates an **order** — a standing request that says
"I'll pay up to this price for this many shares" — and that request then has to wait for someone
else who's actually willing to sell at that price. Nothing is automatic about finding that someone.

Your order moves through a few stages:

- **`PENDING`** — accepted, but nothing's been checked yet. This lasts a fraction of a second.
- **`VALIDATED`** — passed the basic checks (you're not over your limits) and is now sitting in a
  waiting line, grouped by price, waiting for a matching order to show up.
- **`FILLED`** — a real match happened. Money actually moved.

The important one to understand is `VALIDATED`. An order can sit there for a fraction of a second,
or it can sit there forever. There's no guarantee it ever fills, and — this is the part that's easy
to miss — **there's nothing you can do to cancel it once it's in.** No cancel button exists. If it
never matches, it just stays there.

## "But the price ticker says TSLA is $270 — shouldn't that fill me?"

No, and this is the single most common misunderstanding about how this works. The price you see
ticking on the screen is a **simulated quote** — it's there to show you roughly what TSLA is "worth"
right now, for display and for the anomaly-detection feature. It is **not** a standing offer from
anyone to actually sell you shares at that price. Nobody is obligated to trade with you just because
the ticker says a number. Your buy order only fills when a *real* sell order — submitted by an
actual client, through the actual API — happens to cross it.

## "OK, but if nobody starts out owning any shares, how does anyone ever sell the first one?"

This is a genuinely good question, and the honest answer is: this demo doesn't have a stock
exchange, an IPO process, or a market maker behind it, so it solves this the simplest way it could.
Every sell order normally has to prove you actually hold the shares you're trying to sell (checked
against your account's `Position` — you can't sell what you don't have). But an order submitted
*without* a funding account attached to it skips that check entirely — no shares required, no money
required, nothing. That's a leftover from an earlier "dealer desk" concept, and in this demo it
doubles as the only way new shares ever enter circulation: someone effectively originates supply out
of nothing, the same conceptual role a company issuing new shares plays in a real market. It's not a
polished feature — it's a real gap being used on purpose, and I want to be upfront about that rather
than pretend there's a proper issuance mechanism.

## The gap I actually found live: a "ghost" order (since fixed)

*Update: the bug described in this section is fixed — see [Fixed: recovering the book on
startup](#fixed-recovering-the-book-on-startup) below for the live proof. Left the original
narrative in place because it's the honest account of how the bug was found and why it mattered;
the fix section explains what changed and re-proves it against a real restart.*

While trying to explain all this, I discovered something worse than "nobody's placed a matching
order yet." My very first TSLA buy — placed at 09:13:28, sitting at `VALIDATED` — turned out to be
**permanently unfillable at the time**, for a reason that has nothing to do with price.

The list of orders currently waiting to match lives in the app's short-term memory (RAM), not
permanently on disk. Postgres remembers that your order exists and what status it's in, but the
actual "who's waiting to trade right now" list only exists in memory, and it gets wiped clean every
time the app restarts. Normally that's not a big problem — but this session had restarted the app
container many times earlier the same day, testing something unrelated (making the system more
resilient to a different kind of outage). Every one of those restarts silently erased whatever
orders were waiting at the time, including that TSLA buy. Postgres still says `VALIDATED` — your
screen still shows it waiting — but nothing will ever be able to match it, because it doesn't exist
anywhere a match could happen. It's a ghost: visible, but unreachable.

I proved this rather than just asserting it: I submitted a sell order at the *exact* same price and
watched it also just sit there, unmatched — direct evidence nothing was listening on the other side.
Checking the app's own live "how many orders are currently waiting" count confirmed it: **one**
order waiting, and it was the sell I'd just placed, not the original buy. The original was already
gone.

## So how did the second attempt actually fill?

The difference was simple: the second buy was placed *after* the app's last restart, so it was
still genuinely present in memory when I submitted a matching sell moments later. It filled in
about two seconds, and the buyer's account balance actually dropped by the exact cost of the
share — proof this wasn't just a status label changing, real money moved.

One more thing worth knowing if you try this yourself: **the price I submit the matching sell at
doesn't need to match your buy exactly.** Whichever order was there *first* — yours, in this case —
sets the price the trade actually executes at. The order arriving second just has to be willing to
accept that price or better. That's why I could sell at a deliberately silly-low price ($0.01) and
still have the trade settle at your real price, not mine — I only need to guarantee my price crosses
yours, not guess it exactly.

---

# Part 2 — For a Developer: the Mechanics and How to Reproduce This

## The pipeline (see [TRADING_SYSTEM.md](TRADING_SYSTEM.md) for full detail)

```
POST /v1/orders → PENDING → RiskService checks → VALIDATED → published to Kafka (orders-validated)
    → MatchingEngineConsumerRunner picks it up → checked against the in-memory OrderBook for that symbol
    → crosses something? → TradeEvent → ExecutionService persists FILLED/PARTIALLY_FILLED + settles the account
    → doesn't cross anything? → just rests in the book, still VALIDATED, until something else does
```

The two facts that explain everything above:

1. **`MatchingEngineServiceImpl.match()` only ever runs once per *incoming* order.** There's no
   background job that re-scans already-resting orders. A resting order is entirely passive — it
   only ever gets matched as a side effect of some *other*, later order being processed.
2. **The `checkFundingAccount()` share/funds check only runs `if (request.accountId() != null)`.**
   Omit `accountId` from the request and both the funds check (for a buy) and the position check
   (for a sell) are skipped entirely — verified directly in
   [`OrderServiceImpl.java`](../src/main/java/com/dcbate/tradingplatform/trading/service/OrderServiceImpl.java).
   That's the "dealer sell with no account" trick from Part 1.

## The ghost-order bug, precisely

`MatchingEngineServiceImpl` holds the live book as a plain field:

```java
private final Map<String, OrderBook> books = new ConcurrentHashMap<>();
```

Nothing rebuilds this from Postgres on startup — it only ever gets populated by
`match()` being called, which only happens when `MatchingEngineConsumerRunner` consumes an
`OrderValidatedEvent` off Kafka. That consumer runs with `group.id=matching-engine`,
`enable.auto.commit=false`, and calls `kafkaConsumer.commitSync()` right after processing each
batch. So on restart, it resumes from the **last committed offset** — not from the beginning of the
topic — meaning any order that was already processed (matched or left resting) before the restart
is never replayed. A resting order that was never actually filled simply vanishes from the book,
with Postgres never told anything changed.

This is a real, previously undocumented gap, distinct from the Kafka *fallback-queue* durability
work in [KAFKA_SETUP.md](KAFKA_SETUP.md) — that work was about the **publisher** surviving an
outage; this is about the **matching engine's own state** not surviving a restart at all, outage or
not. Combined with there being no cancel/expiry endpoint (see
[TRADING_SYSTEM.md §1](TRADING_SYSTEM.md#1-order-lifecycle)), a resting order that outlives an app
restart is stuck forever, silently, with no error and no indication anywhere that it happened.

**What fixing it properly would take:** on startup, query Postgres for every order still in
`VALIDATED`/`PARTIALLY_FILLED` status and replay each one through `MatchingEngineServiceImpl.match()`
before the app starts accepting new order traffic — effectively rebuilding the book from the
database instead of trusting Kafka's committed offset to still reflect "what's actually resting."
That's exactly what got built — see below.

## Fixed: recovering the book on startup

`MatchingEngineServiceImpl` now has a `@PostConstruct recoverRestingOrders()` method that does
exactly what the section above described: query `OrderRepository` for every order still
`VALIDATED` or `PARTIALLY_FILLED`, compute how much of each is actually still outstanding (a
`PARTIALLY_FILLED` order's remaining quantity is derived by subtracting `SUM(Trade.quantity)` from
`Order.quantity` — `Order` itself doesn't track a running filled quantity), and replay each one
through the same `match()` method live orders use, oldest first.

Two things make this safe rather than just plausible:

- **Ordering.** Spring constructs beans by resolving constructor dependencies first —
  `MatchingEngineConsumerRunner` takes `MatchingEngineService` as a constructor argument, so Spring
  cannot construct the consumer until `MatchingEngineServiceImpl`'s own construction (including its
  `@PostConstruct`) has finished. No new-order Kafka message can reach `match()` before recovery has
  already run. No `@DependsOn` needed — it falls out of the existing dependency graph.
- **Reusing `match()`, not a separate insert path.** Orders still resting at shutdown can't have
  crossed each other — if they had, the matching engine would already have resolved that while it
  was running, and neither would still be resting. So replaying them through the ordinary matching
  path in original arrival order is safe, and it means recovery can never drift out of sync with
  what live trading does, because it *is* live trading, just fed from Postgres instead of Kafka.

**Live proof, a real restart:**

1. Submitted a fresh EUR/USD buy with no crossing counter-order — it rested at `VALIDATED`, and
   `/actuator/health` → `components.matchingEngine.details.restingOrders` read `3` (2 pre-existing
   ghost orders from before this fix, plus the new one).
2. `docker compose restart app` — a real container restart, not a reload.
3. After the app reported healthy again: the order was still `VALIDATED`, and
   `restingOrders` was still `3` — nothing was lost.
4. Submitted a matching sell. The order that had survived the restart flipped to `FILLED` within
   two seconds, and `restingOrders` dropped back to `2` — proof it wasn't just a status label that
   survived, it was still genuinely sitting in the live order book, reachable by a real match.

This closes the gap for good — a resting order now survives any number of restarts, the same way
it always looked like it should.

## Reproducing this yourself

Real commands, run against a live `docker compose up` stack. Substitute in your own IDs as you go —
every ID below is one this walkthrough actually produced.

```bash
# 1. Sign up a buyer and open a funded trading account
curl -s -X POST http://localhost:8080/v1/auth/signup -H 'Content-Type: application/json' \
  -d '{"email":"buyer@example.com","password":"Password123!"}'
# → {"clientId": "<BUYER_ID>", ...}

curl -s -X POST http://localhost:8080/v1/accounts -H 'Content-Type: application/json' \
  -d '{"clientId":"<BUYER_ID>","accountType":"FX_TRADING","currency":"USD","openingBalance":10000}'
# → {"accountId": "<BUYER_ACCOUNT_ID>", "balance": 10000, ...}

# 2. Submit a real buy — carries accountId, so it's genuinely funded and will really settle
curl -s -X POST http://localhost:8080/v1/orders -H 'Content-Type: application/json' \
  -d '{"clientId":"<BUYER_ID>","accountId":"<BUYER_ACCOUNT_ID>","currencyPair":"TSLA","side":"BUY","quantity":1,"price":270.51}'
# → {"orderId": "<BUY_ORDER_ID>", "status": "PENDING", ...}

# 3. Sign up a "dealer" — no account needed at all, since we're skipping the funding check
curl -s -X POST http://localhost:8080/v1/auth/signup -H 'Content-Type: application/json' \
  -d '{"email":"dealer@example.com","password":"Password123!"}'
# → {"clientId": "<DEALER_ID>", ...}

# 4. Submit a matching sell — no accountId, priced low enough to guarantee it crosses,
#    since the ACTUAL execution price will be the resting buy's price, not this one
curl -s -X POST http://localhost:8080/v1/orders -H 'Content-Type: application/json' \
  -d '{"clientId":"<DEALER_ID>","currencyPair":"TSLA","side":"SELL","quantity":1,"price":0.01}'

# 5. Check the buy order — should flip to FILLED within a second or two
curl -s http://localhost:8080/v1/orders/<BUY_ORDER_ID>

# 6. Prove real money moved, not just a status label
curl -s "http://localhost:8080/v1/accounts?clientId=<BUYER_ID>"
```

If you want to watch it happen without polling, the real frontend doesn't poll at all — it listens
on the live `ws://.../v1/orders/stream` WebSocket and gets pushed the status change the instant
`ExecutionServiceImpl` persists it.

**A real captured run, from the session this doc is based on:**

| Order | ID | Price | Result |
|---|---|---|---|
| Buy (funded) | `2fae3150-28c4-4b60-97ab-69fb12b9e688` | $270.51 | `FILLED` at `19:58:29`, 2s after submission |
| Sell (dealer, no account) | `f22f4b5f-51d6-45be-a54a-968f97578073` | $270.51 | `FILLED` at the same instant |
| Buyer's account balance | `d5010d40-5a8e-4c61-b3f1-945c46248a4b` | — | $10,000.00 → **$9,729.49** (debited exactly $270.51) |

A second run, matching a real order placed through the actual frontend rather than curl on both
sides, filled in **217ms** and the live order-book depth (`/actuator/health` →
`components.matchingEngine.details.restingOrders`) dropped from `1` to `0` the instant it matched —
independent, second-source proof the fill was genuine and not just a database write.
