# FX Trading Desk

This is the deep dive into the order lifecycle and matching algorithm behind the bank's FX trading
desk — a real bank function, funded by a client's `FX_TRADING` account
([docs/ACCOUNTS.md](ACCOUNTS.md#2-account-types)). I built this as the one specialized desk on top
of the retail bank, not the platform's identity — a bank's FX desk takes buy/sell orders on
currency pairs and matches them in an order book, and that's exactly what the mechanics below do.
For the broader system design, see [ARCHITECTURE.md](../ARCHITECTURE.md).

## 1. Order lifecycle

```
PENDING → VALIDATED → PARTIALLY_FILLED → FILLED
   │           │
   └── REJECTED ┘  (risk check failure, either notional or velocity)
```

- `PENDING`: written by `OrderService` the instant the order is accepted, before any risk or
  matching has happened. `GET /v1/orders/{id}` can return this immediately after `201`.
- `VALIDATED`: written by `RiskService` once both limit checks pass. An order can sit here briefly
  if its currency pair's book has no crossing counter-order yet — it's now resting in the Matching
  Engine's in-memory book, invisible to Postgres until it fills.
- `PARTIALLY_FILLED` / `FILLED`: written by `ExecutionService` only, driven by the
  `buyOrderStatus`/`sellOrderStatus` fields on the `TradeEvent` the Matching Engine computed.
- `REJECTED`: written by `RiskService`. Terminal — a rejected order never reaches the book.

I didn't build a cancellation or expiry endpoint — an order that rests in the book (partially or
fully unfilled) just stays `VALIDATED` or `PARTIALLY_FILLED` indefinitely. It's a real gap for
anything resembling production use, and I'd rather flag it than let it look like an oversight.

## 2. Matching algorithm

I gave each currency pair its own
[`OrderBook`](../src/main/java/com/dcbate/tradingplatform/trading/service/matching/OrderBook.java):
two `TreeMap`s (buys ordered highest-price-first, sells lowest-price-first), each price level a
FIFO deque — the standard price/time-priority structure, and I didn't see a reason to reach for
anything fancier at this scale.

```java
while (!incoming.isFullyFilled()) {
    var bestLevel = opposite.firstEntry();
    if (bestLevel == null || !crosses(incoming, bestLevel.getKey())) break;

    var resting = bestLevel.getValue().peekFirst();
    var fillQty = min(incoming.remaining, resting.remaining);
    // both sides decrement; a match executes at the RESTING order's price
    // (the order that was already in the book, not the one that just arrived)
}
// leftover quantity, if any, rests in the book at the incoming order's own price
```

Price/time priority in practice:
- **Price priority**: the best-priced resting order matches first, regardless of arrival order.
- **Time priority**: at the same price, the order that arrived first (head of the deque) fills
  first.
- **Execution price**: always the resting order's price — the arriving order "takes" liquidity at
  the price it was offered, never worse than what it asked for. I picked this convention because
  it's how real exchanges price a cross, and it made the fill logic unambiguous to test.

## 3. Example walkthrough

1. Seller posts `SELL 10 EUR/USD @ 1.0800` → book has no buys → it rests: `sells[1.0800] = [seller]`.
2. Buyer posts `BUY 10 EUR/USD @ 1.0800` → crosses (`1.0800 >= 1.0800`) → matches the full 10
   against the resting sell at `1.0800` → both orders fully filled, book empty again.
3. `TradeEvent{quantity=10, price=1.0800, buyOrderStatus=FILLED, sellOrderStatus=FILLED}` →
   `ExecutionService` persists the trade and flips both `Order.status` to `FILLED`.

A partial-fill variant: if the seller had only posted 4 units, the trade fills 4, the seller's
order is `FILLED`, and the buyer's remaining 6 units rest in the book as `PARTIALLY_FILLED`,
waiting for the next matching sell. I tested this variant specifically because it's the case most
likely to hide an off-by-one in the quantity bookkeeping.

## 4. Risk scenarios

I run two independent checks in
[`RiskServiceImpl`](../src/main/java/com/dcbate/tradingplatform/trading/service/RiskServiceImpl.java);
either one is enough to reject:

- **Notional limit**: `sum(open orders' quantity × price) + this order's notional >
  trading.risk.max-notional-per-client` (default 1,000,000). "Open" means `PENDING`, `VALIDATED`,
  or `PARTIALLY_FILLED` — anything not yet terminal.
- **Velocity limit**: more than `trading.risk.max-orders-per-window` (default 5) orders from the
  same client within `trading.risk.window-seconds` (default 60), tracked by
  [`OrderVelocityTracker`](../src/main/java/com/dcbate/tradingplatform/trading/service/OrderVelocityTracker.java)'s
  in-memory sliding window. I'll be upfront that this tracker is single-instance only — it'd need
  to move to Redis before this could run behind more than one app instance.

On rejection, I call `AnomalyDetector.explain()` with the rule's plain-text reason — if
`GEMINI_API_KEY` is configured, the persisted `RiskAlert.reason` gets an AI-written severity
assessment; otherwise it falls back to the rule's own description verbatim. Either way, the order
is already rejected by that point — I never let Gemini decide the outcome, only narrate it, for
the same reason I kept AI out of the payment fraud decision (see
[PAYMENT_SYSTEM.md §5](PAYMENT_SYSTEM.md#5-fraud-scenarios)): I don't want a third-party API
outage to be able to change whether an order executes.

**Explicitly out of scope, same as I note in ACCOUNTS.md**: an FX pair fill still doesn't move
`Account.balance` — a currency pair involves two currencies, and I never solved which side of the
account holds which one. That's a genuinely separate problem from the one below, not something I
papered over by adding stock settlement.

## 5. Stock orders: the same engine, real settlement

I reused this exact pipeline — `OrderController` → `RiskService` → `MatchingEngine` →
`ExecutionService` — for buying and selling shares in well-known companies (`AAPL`, `MSFT`,
`GOOGL`, `AMZN`, `NVDA`, `TSLA`, `META`), rather than building a second matching engine. The
matching logic doesn't care whether `currencyPair` holds `"EUR/USD"` or `"AAPL"` — it was already
symbol-agnostic. `Order.accountId` (nullable) is what turns settlement on: an FX order submitted
without one behaves exactly as before; a stock order carries the `BROKERAGE` account funding it,
and `ExecutionServiceImpl` settles the fill for real — a buy debits cash and increases a
`Position`, a sell credits cash and decreases one, with `Position.avgCost` tracked as a standard
weighted-average cost basis. Unlike the FX case above, this was tractable specifically because a
stock trade is single-currency: no "which side holds which currency" problem to solve.

`POST /v1/orders` also now accepts a `CLIENT` caller (previously `TRADER`/`ADMIN` dealer-desk
roles only), with the same `CallerPrincipal.requireOwner` check every other client-facing endpoint
uses — a client can only submit or view their own orders; staff still act across clients.

**A gap I found and deliberately didn't wire up**: `OrderStreamHandler` (the `/v1/orders/stream`
WebSocket) broadcasts every order update to *every* connected session with no per-client
filtering — fine for the FX dealer desk it was originally built for (all `TRADER` staff seeing all
activity is normal), but not safe to hand to retail clients without adding server-side scoping
first, since client A would see client B's fills. The frontend's Trading page polls
`GET /v1/orders?clientId=` instead. Filtering the WebSocket by client is a real follow-on, not
done here.
