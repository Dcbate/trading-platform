# Design Decisions: What's Real, What's Simulated

I verified every claim below directly against the current code (file paths given) — none of this is
a guess about what the system "should" do. The simulated pieces are intentional test seams I built
on purpose, not gaps I silently skipped; each section says why, and what real integration would
take.

## Email notifications — simulated (logged)

**Files:** `notification/service/EmailSender.java` (interface), `EmailSenderImpl.java` (the only
implementation).

```java
public void send(String clientId, String subject, String body) {
    log.info("[EMAIL stand-in] to={}, subject=\"{}\", body=\"{}\"", clientId, subject, body);
}
```

No SendGrid, SES, or SMTP integration exists. `NotificationServiceImpl.deliver()` calls this
unconditionally-succeeding stand-in and persists the notification as `SENT` either way.

**Why:** email delivery is a side effect at the edge of the system, not part of the domain logic I
was trying to demonstrate (fraud review, saga settlement, retry/DLQ). The retry/DLQ machinery
around delivery (`NotificationEventConsumer`'s `@RetryableTopic`, 6 attempts with exponential
backoff) is real and fully exercised regardless of what the "send" step actually does.

**To productionize:** I'd implement `EmailSender` against a real provider (SendGrid's Java SDK is a
single POST call) and register it as the primary `@Component`, keeping `EmailSenderImpl` as a
`@Profile("dev")` fallback. My rough estimate is under an hour, since the retry/failure/DLQ path
around it doesn't change at all.

## Slack alerts — simulated (logged)

**Files:** `notification/service/SlackSender.java` / `SlackSenderImpl.java`. Same pattern and
same reasoning as email above — logs a `[SLACK stand-in]` line instead of posting to a webhook.
**To productionize:** a Slack incoming webhook is one `WebClient.post()` call; same effort class as
email.

## Bank clearing gateway — simulated (deterministic, not random)

**Files:** `payment/service/BankClearingClient.java` (interface),
`BankClearingClientImpl.java` (only implementation), `config/SettlementProperties.java`.

```java
public boolean clear(Payment payment) {
    return payment.getAmount().compareTo(settlementProperties.simulatedBankFailureThreshold()) <= 0;
}
```

The threshold (`payment.settlement.simulated-bank-failure-threshold: 500000` in `application.yml`)
is a deliberate, deterministic test seam I built, not a risk limit — any payment ≤ $500,000 always
clears, anything above always fails. That's why `PaymentFlowIntegrationTest` (before I removed it
for an unrelated Testcontainers-environment reason — see `HANDOFF.md`) could reliably test both the
settle and compensate paths without mocking framework machinery.

**Why:** there's no real correspondent-bank relationship for me to integrate against in a demo
project. What's real is everything *around* this call: the saga (reserve → ledger → clear →
compensate on failure), the compensating reversal entries, and the fact the ledger nets to zero in
both outcomes.

**To productionize:** swap in a real clearing house / correspondent bank API client behind the same
`BankClearingClient` interface — the saga orchestration in `SettlementServiceImpl` doesn't change;
this is a multi-week integration in reality (bank onboarding, message formats, reconciliation SLAs),
not a code-complexity problem.

## FX price feed — simulated (bounded random walk)

**File:** `trading/service/PriceFeedServiceImpl.java`.

Prices are seeded from a hardcoded map (EUR/USD ≈ 1.08, GBP/USD ≈ 1.27, etc.) and each 2-second
tick applies a random percentage move off the previous cached price — normally ±2%, with a 5%
chance of a ±15% "spike" move, which I added specifically so the anomaly-detection threshold and
Claude enrichment path are actually reachable in a demo/test run without waiting for a real market
event. No external market data API is called.

**Why:** a real market data subscription (Bloomberg, Refinitiv, a bank's own rate engine) isn't
something I can integrate into a demo project. What's real is everything downstream of a price
tick: the Redis cache with TTL, the anomaly-detection rule, the optional Claude severity enrichment,
and the `/v1/accounts/{id}/convert` endpoint using this exact cached rate to move real money between
two of a client's own accounts.

**To productionize:** replace `PriceFeedServiceImpl.randomWalk()`'s source with a real feed
subscription (WebSocket or FIX depending on provider); the Redis caching, Kafka publish, and
anomaly-detection consumers are unaffected.

## Login / JWT issuance — real, but hand-rolled rather than a managed IdP

**Files:** `auth/api/AuthController.java`, `auth/service/AuthServiceImpl.java`,
`security/JwtIssuer.java`, `config/SecurityConfig.java`.

This used to be a gap — no signup/login endpoint existed, and the only place a JWT was ever
constructed was test code hand-signing one. It isn't anymore: `POST /v1/auth/signup` creates a real
`User` row (bcrypt-hashed password via `PasswordEncoder`), auto-opens a `CHECKING` account through
the same `AccountService.openAccount` every other caller goes through, and `AuthServiceImpl` mints a
real access token (15 min) and refresh token (7 days) via `JwtIssuer` — the same HS256 signing
`SecurityConfig`'s `NimbusJwtDecoder` validates, so a token minted at signup passes the existing
resource-server filter chain unchanged. `POST /v1/auth/login` does the same after verifying the
password hash. `POST /v1/auth/refresh` rotates the refresh token: the redeemed row is marked revoked in
the `refresh_tokens` table before a new pair is issued, so replaying an old refresh token fails
outright rather than silently succeeding — that's the "rotation" part, not just "long-lived token."
Tokens are set as HTTP-only, `SameSite=Strict` cookies (`AuthController`), never returned in a JSON
body a script could read into `localStorage`.

**What's still a stand-in:** it's a hand-rolled issuer, not a managed identity provider. No MFA, no
password reset flow, no email verification, no OAuth2/social login. `dev` profile still bypasses
authentication entirely for `curl`/`playground.html` convenience — real login isn't required to
exercise the API locally, only to get a token the non-`dev` filter chain will accept.

**Why I built it this way:** a full identity provider (MFA, session management, password reset,
compliance-grade audit) is a separate, large body of work from what this project set out to
demonstrate — event-driven banking domain logic, ownership-based authorization, saga patterns. What
I did build for real is the part that actually exercises that domain logic: a genuine user, a real
password check, a real token a real client can use, and the same `CallerPrincipal`/`requireOwner`
ownership model enforced on top of it, proven end-to-end by `AccountSecurityIntegrationTest` against
the real (non-`dev`) filter chain.

**To productionize:** front the resource server with a real OAuth2/OIDC identity provider (Auth0,
Cognito, Keycloak, or a bank's own IdP) — `SecurityConfig`'s `jwtSecurityFilterChain` barely changes
(swap the shared-secret `NimbusJwtDecoder` for a JWKS-based one pointed at the IdP's issuer); zero
changes needed in `CallerPrincipal` or any controller, since they only ever look at claims already
on a validated token. `AuthController`/`AuthServiceImpl` would mostly go away, replaced by the IdP's
own signup/login/consent flows.

## FX trade execution does not move account balances — a gap I'm stating, not hiding

**File:** `trading/service/ExecutionServiceImpl.java`.

When an order fills, `recordTrade()` records metrics, saves the `Trade` row, updates both orders'
status (and broadcasts over the WebSocket order stream), and journals the fill to Chronicle Queue.
It has **no reference to `AccountService` at all** — I confirmed this with a repo-wide search:
nothing under `trading/` or `kafka/` touches account balances. `AccountType.FX_TRADING` exists as an
enum value, but nothing in the fill path connects to it. Account balances only ever change via
`LedgerServiceImpl` (the payment settlement saga) or the `deposit`/`withdraw`/`convert` endpoints on
`AccountController`.

**Why I'm flagging this as a real gap, not dressing it up as a design choice:** unlike the items
above, there's no strong argument that this *should* stay simulated — a real FX desk's fills do
move money. I scoped it out because wiring a fill to a debit/credit pair correctly (which side holds
which currency, partial fills, netting) is a meaningfully sized feature in its own right, not a
same-day addition like the notification/email items above. I ran out of runway before getting to
it.

**To productionize:** on a fill, debit the seller's base-currency account and credit their
quote-currency account (and the inverse for the buyer) inside the same transaction that updates the
`Order`/`Trade` rows — the pattern to follow already exists in `LedgerServiceImpl`'s double-entry
logic for payments.

## What's genuinely real

Everything not listed above: the double-entry ledger, the payment saga (reserve → ledger → clear →
compensate) and its compensating-reversal correctness, ownership-based authorization, the Kafka
event pipeline end to end — including a fallback queue for broker outages that's genuinely durable
now (Chronicle Queue-backed, survives an app restart mid-outage, not just an in-memory buffer — see
`docs/KAFKA_SETUP.md` for the three resilience bugs I found and fixed testing that properly) — the
risk/fraud rule engines, loan interest accrual math, the matching engine, distributed tracing and
metrics (see `OBSERVABILITY_PROOF.md`), and all three AI integrations (anomaly severity, payment summaries,
Game Mode debrief) — every AI call in the app goes through the same Anthropic Claude API now,
rather than splitting usage across two providers. As of the MCP migration, none of the three call
Anthropic directly anymore: `ai/mcp/AnomalyDetectorImpl`, `ClaudeSummarizerImpl`, and `GameCoachImpl`
implement the same interfaces the old in-process `AnthropicX` classes did, but reach Claude by
calling `bate-mcp-server` — a real, separate MCP server (`bate-mcp-server/`) — over genuine
JSON-RPC/Streamable HTTP via `ai/mcp/McpToolClient`. `bate-mcp-server` is now the only place in the
whole system holding an Anthropic API key; this app connects to it lazily (not at startup) and
never lets a failure there block anything — an unreachable server, a dropped connection, or a real
MCP `isError` result all collapse to the exact same plain rule-based text the old direct-call
version fell back to. See `bate-mcp-server/README.md` for the protocol details and a live-captured
proof of this fallback actually firing.
