# FX Trading Desk

Deep dive into the order lifecycle and matching algorithm behind the bank's FX trading desk — a
real bank function, funded by a client's `FX_TRADING` account
([docs/ACCOUNTS.md](ACCOUNTS.md#2-account-types)). A bank's FX desk takes buy/sell orders on
currency pairs and matches them in an order book, which is exactly what the mechanics below do.
For the broader system design, see [ARCHITECTURE.md](../ARCHITECTURE.md).

## 1. Order lifecycle

```
PENDING → VALIDATED → PARTIALLY_FILLED → FILLED
   │           │
   └── REJECTED ┘  (risk check failure, either notional or velocity)
```

- `PENDING`: written by `OrderService` the instant the order is accepted, before any risk or
  matching has happened. `GET /v1/orders/{id}` can return this immediately after `201`.
- `VALIDATED`: written by `RiskService` once both limit checks pass. An order can sit here
  briefly if its currency pair's book has no crossing counter-order yet — it's now resting in the
  Matching Engine's in-memory book, invisible to Postgres until it fills.
- `PARTIALLY_FILLED` / `FILLED`: written by `ExecutionService` only, driven by the
  `buyOrderStatus`/`sellOrderStatus` fields on the `TradeEvent` the Matching Engine computed.
- `REJECTED`: written by `RiskService`. Terminal — a rejected order never reaches the book.

An order that rests in the book (partially or fully unfilled) stays `VALIDATED` or
`PARTIALLY_FILLED` indefinitely; there's no cancellation or expiry endpoint in Phase 1.

## 2. Matching algorithm

Each currency pair gets its own [`OrderBook`](../src/main/java/com/dcbate/tradingplatform/trading/service/matching/OrderBook.java):
two `TreeMap`s (buys ordered highest-price-first, sells lowest-price-first), each price level a
FIFO deque.

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
- **Execution price**: always the resting order's price — the arriving order "takes" liquidity
  at the price it was offered, never worse than what it asked for.

## 3. Example walkthrough

1. Seller posts `SELL 10 EUR/USD @ 1.0800` → book has no buys → it rests: `sells[1.0800] = [seller]`.
2. Buyer posts `BUY 10 EUR/USD @ 1.0800` → crosses (`1.0800 >= 1.0800`) → matches the full 10 against
   the resting sell at `1.0800` → both orders fully filled, book empty again.
3. `TradeEvent{quantity=10, price=1.0800, buyOrderStatus=FILLED, sellOrderStatus=FILLED}` →
   `ExecutionService` persists the trade and flips both `Order.status` to `FILLED`.

A partial-fill variant: if the seller had only posted 4 units, the trade fills 4, the seller's
order is `FILLED`, and the buyer's remaining 6 units rest in the book as `PARTIALLY_FILLED`,
waiting for the next matching sell.

## 4. Risk scenarios

Two independent checks run in [`RiskServiceImpl`](../src/main/java/com/dcbate/tradingplatform/trading/service/RiskServiceImpl.java),
either one is enough to reject:

- **Notional limit**: `sum(open orders' quantity × price) + this order's notional >
  trading.risk.max-notional-per-client` (default 1,000,000). "Open" means `PENDING`,
  `VALIDATED`, or `PARTIALLY_FILLED` — anything not yet terminal.
- **Velocity limit**: more than `trading.risk.max-orders-per-window` (default 5) orders from the
  same client within `trading.risk.window-seconds` (default 60), tracked by
  [`OrderVelocityTracker`](../src/main/java/com/dcbate/tradingplatform/trading/service/OrderVelocityTracker.java)'s
  in-memory sliding window.

On rejection: `AnomalyDetector.explain()` is called with the rule's plain-text reason — if
`GEMINI_API_KEY` is configured, the persisted `RiskAlert.reason` gets an AI-written severity
assessment; otherwise it falls back to the rule's own description verbatim. Either way, the
order is rejected — Gemini never decides the outcome, only narrates it.
