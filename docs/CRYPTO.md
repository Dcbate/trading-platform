# Crypto Trading

*Built overnight, autonomously, per your instruction to keep going without stopping for
questions. This doc exists specifically so you can review the reasoning when you're back, not
just the diff.*

## What you asked for

> lets also add a crypto part but lets base it on both the stock and shares and the fx a crypto
> buy goes to a queue and then it get picked up by a different code to then take it from placed to
> fullfiled similair to the shares

Three things fell out of that sentence, and they turned out to already be exactly how this system
works:

1. **"Similar to the shares" / "goes to a queue... picked up by a different code... placed to
   filled"** — that's not a new mechanism to build, it's a precise description of the pipeline
   that already exists for every order, stock or FX: `OrderController` → `orders` Kafka topic →
   `RiskService` validates → `orders-validated` topic → `MatchingEngineConsumerRunner` picks it up
   → `MatchingEngineService` matches → `trades` topic → `ExecutionServiceImpl` picks *that* up and
   settles the fill. Nothing about that pipeline cares what the `currencyPair` string actually is
   — it's already generic. So crypto didn't need a second queue or a second consumer; it needed to
   be a third *kind of symbol* flowing through the one pipeline that already does exactly what you
   described. Building a parallel queue/consumer pair would have duplicated real, working
   machinery for zero behavioral difference — I chose not to do that, and I'm flagging the choice
   explicitly rather than silently deciding it for you.
2. **"Based on... the stock and shares"** — settlement. A crypto buy is "cash out, asset in," the
   same shape as a stock buy (see `ExecutionServiceImpl.settleFill()`), not a two-currency trade
   like a real FX pair. So a crypto order behaves like a stock order: it needs a funded
   `accountId` to actually settle, and a fill debits/credits real `Account.balance` and maintains
   a `Position` row — genuine money movement, not a status label.
3. **"Based on... the fx"** — naming and quoting convention. Crypto pairs are named `BTC/USD`,
   `ETH/USD`, etc. — the same `"BASE/QUOTE"` string shape as `EUR/USD` — because that's the
   standard way every real exchange quotes crypto, and it slots into the existing
   `OrderBook`/`MatchingEngineServiceImpl` machinery with zero changes (it already treats every
   `currencyPair` as an opaque string key).

## What actually changed

**Backend:**
- `AccountType` gained a `CRYPTO` value (alongside the pre-existing `CHECKING`/`SAVINGS`/
  `FX_TRADING`/`BROKERAGE`) — see `docs/ACCOUNTS.md` §2.
- `TradingProperties` gained a third symbol list, `cryptoSymbols`, alongside the existing
  `currencyPairs` and `stockSymbols` — seeded in `application.yml` with `BTC/USD`, `ETH/USD`,
  `SOL/USD`, `XRP/USD`.
- `PriceFeedServiceImpl` seeds a starting price for each (same synthetic random-walk model as
  everything else — not a real feed, same as FX/stocks).
- `PriceFeedScheduler` ticks crypto symbols on the same cron as FX pairs and stock symbols.
- New `GET /v1/crypto/prices` endpoint, mirroring `/v1/fx/prices` and `/v1/stocks/prices` exactly.
- **`OrderServiceImpl` needed no changes at all.** The whole-unit-quantity check
  (`InvalidOrderQuantityException`) only applies to symbols in `stockSymbols` — crypto correctly
  stays out of that list, so fractional quantities (`0.001 BTC`) are accepted, the same as a
  fractional FX amount would be. The funding-account check (`checkFundingAccount`) already runs
  for *any* order carrying an `accountId`, regardless of symbol — crypto gets it for free.

**Frontend:**
- New `/crypto` page and sidebar nav entry ("Crypto", under Trading), built by close-copying
  `TradingPage.tsx` (Stocks & Shares): same order form shape, same positions/orders tables, with
  the whole-share-quantity input swapped for a fractional one and the account filter swapped from
  `BROKERAGE` to `CRYPTO`.
- **One real bug I found and fixed while doing this, not something I introduced:**
  `TradingPage.tsx`'s orders/positions tables were rendering the client's *entire* order/position
  list, unfiltered by symbol — meaning once crypto orders existed, they'd have shown up on the
  Stocks & Shares page too. I scoped both pages' tables to their own symbol set
  (`stockSymbols`/`cryptoSymbols` derived from each page's own live price list) so they only ever
  show their own activity.

## What I deliberately did not build

- **No new `AccountType`-level enforcement.** Nothing stops a client from attaching a `CRYPTO`
  account's `accountId` to a genuine FX pair order, or vice versa — the account-type/symbol
  pairing is a UI convention (which page's form you used), not a backend rule. This is the same
  gap already documented in `DESIGN_DECISIONS.md` for stocks vs. FX; crypto doesn't add a new
  category of gap, it just means one more symbol family the same gap applies to.
- **No dedicated crypto wallet/custody model.** A `CRYPTO` account is a `Position` row plus a cash
  balance, exactly like a `BROKERAGE` account — there's no distinct on-chain-address concept, no
  network/chain field, nothing that would make this resemble real custody. That's consistent with
  the rest of the platform (this is a matching-engine/ledger demo, not a wallet), but worth being
  explicit that "crypto" here means "a tradeable instrument," not "a cryptocurrency wallet."
- **No stablecoin/fiat-pair distinction, no network fees, no on-chain settlement time.** Fills are
  instant, same as a stock or FX fill — there's no simulated blockchain confirmation delay.

## Verification

`mvn test` — full suite green, including a new `OrderServiceImplTest` case
(`submitCryptoOrderWithFractionalQuantitySucceeds`) proving a `0.001 BTC` order is accepted where
`0.001` shares of `AAPL` would be rejected, and a new `PriceControllerTest` case for
`GET /v1/crypto/prices`. Frontend `tsc --noEmit` clean.

**Live-verified end to end**, not just unit-tested: opened a real `CRYPTO` account, placed a
`0.05 BTC/USD` buy through the actual UI, matched it with a dealer sell (no funding account, same
pattern as [HOW_A_TRADE_FILLS.md](HOW_A_TRADE_FILLS.md)), and confirmed via the real API
afterward — not just the UI showing a green toast — that: the order reached `FILLED`; the crypto
account's balance dropped by exactly `0.05 × 77598.9612 = 3879.95` (to the cent, matching the fill
price precisely); a genuine `Position` row exists with `77598.9612` as its avg cost; and the fill
shows up correctly on both the full bank statement and the new account-scoped one.

**One real bug this live verification caught, now fixed**: the quantity `<input>` in
`CryptoPage.tsx` originally had `min="0.00000001"` / `step="0.0001"` — a perfectly normal value
like `0.05` doesn't land on that step grid, so the browser's native HTML5 constraint validation
silently blocked form submission with *zero* visible error (no toast, no console error, the click
just did nothing). Fixed to `min="0"` / `step="any"`. This is exactly the kind of bug that only
surfaces by actually clicking the button in a real browser — a unit test of the submit handler in
isolation would never have caught it, since the browser's native validation runs before React's
`onSubmit` ever fires.
