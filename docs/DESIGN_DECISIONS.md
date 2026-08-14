# Design Decisions: What's Real, What's Simulated

I verified every claim below directly against the current code (file paths given) — none of this is
a guess about what the system "should" do. The simulated pieces are intentional test seams I built
on purpose, not gaps I silently skipped; each section says why, and what real integration would
take.

## Email notifications — simulated (logged)

**Files:** `notification/service/EmailSender.java` (interface), `LoggingEmailSender.java` (the only
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
single POST call) and register it as the primary `@Component`, keeping `LoggingEmailSender` as a
`@Profile("dev")` fallback. My rough estimate is under an hour, since the retry/failure/DLQ path
around it doesn't change at all.

## Slack alerts — simulated (logged)

**Files:** `notification/service/SlackSender.java` / `LoggingSlackSender.java`. Same pattern and
same reasoning as email above — logs a `[SLACK stand-in]` line instead of posting to a webhook.
**To productionize:** a Slack incoming webhook is one `WebClient.post()` call; same effort class as
email.

## Bank clearing gateway — simulated (deterministic, not random)

**Files:** `payment/service/BankClearingClient.java` (interface),
`SimulatedBankClearingClient.java` (only implementation), `config/SettlementProperties.java`.

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
Gemini enrichment path are actually reachable in a demo/test run without waiting for a real market
event. No external market data API is called.

**Why:** a real market data subscription (Bloomberg, Refinitiv, a bank's own rate engine) isn't
something I can integrate into a demo project. What's real is everything downstream of a price
tick: the Redis cache with TTL, the anomaly-detection rule, the optional Gemini severity enrichment,
and the `/v1/accounts/{id}/convert` endpoint using this exact cached rate to move real money between
two of a client's own accounts.

**To productionize:** replace `PriceFeedServiceImpl.randomWalk()`'s source with a real feed
subscription (WebSocket or FIX depending on provider); the Redis caching, Kafka publish, and
anomaly-detection consumers are unaffected.

## Login / JWT issuance — no real flow (JWTs are hand-issued)

**Files checked:** `config/SecurityConfig.java`, and a full repo search for any auth/login/signup
endpoint or token-issuing code.

There's no `AuthController`, no `/login` or `/register` endpoint, and no `JwtEncoder` or
token-generation code anywhere in `src/main/java`. `SecurityConfig` only configures a **resource
server** — it validates JWTs signed with a shared secret (`jwt.secret`), it never mints one. The
only place I actually construct a JWT in this codebase is test code
(`AccountSecurityIntegrationTest`, hand-signing via `nimbus-jose-jwt`), and the `dev` Spring profile
bypasses authentication entirely (grants every role to an anonymous principal) so I can exercise the
API with plain `curl`/`playground.html` without a token at all.

**Why:** building a real identity provider (registration, password/MFA, session management) is an
entirely separate, large body of work orthogonal to what I set out to demonstrate here —
event-driven banking domain logic, ownership-based authorization, saga patterns. What's real is the
authorization model I built *on top of* a valid JWT: `CallerPrincipal`/`requireOwner` enforcing that
a client's token can only see/act on their own resources, proven end-to-end by
`AccountSecurityIntegrationTest` against the real (non-`dev`) filter chain.

**To productionize:** front the resource server with a real OAuth2/OIDC identity provider (Auth0,
Cognito, Keycloak, or a bank's own IdP) — `SecurityConfig`'s `jwtSecurityFilterChain` barely changes
(swap the shared-secret `NimbusJwtDecoder` for a JWKS-based one pointed at the IdP's issuer); zero
changes needed in `CallerPrincipal` or any controller, since they only ever look at claims already
on a validated token.

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
event pipeline end to end (including the fallback queue for broker outages), the risk/fraud rule
engines, loan interest accrual math, the matching engine, distributed tracing and metrics (see
`OBSERVABILITY_PROOF.md`), and both AI integrations (`GeminiAnomalyDetector`,
`AnthropicClaudeSummarizer`) — these make genuine outbound HTTP calls to the real Gemini and
Anthropic APIs when I've configured a real key, falling back to plain rule-based text (never
blocking the underlying decision) only when no key is set.
