# The Infrastructure, Explained

[PROJECT_EXPLAINED.md](PROJECT_EXPLAINED.md) covers what the app *does* — accounts, payments,
loans, trading, Game Mode. This doc covers the other half: the pieces of infrastructure running
underneath it — Kafka, Postgres, Redis, the AI integrations, and the observability stack
(Prometheus/Grafana/Jaeger) — what each one is for, and why it's there rather than something
simpler.

Same format as before: plain English first, developer detail second.

---

# Part 1 — In Plain English

Everything below is a genuine, working piece of infrastructure — not diagrams describing a plan,
but real software actually running in Docker containers on this machine, doing real work every
time you click a button in the app.

## Postgres — where everything permanently lives

Every account, payment, loan, and trade is a row in a real Postgres database. If the whole app
restarts, nothing is lost — that's the whole point of a database over just holding things in
memory. The database's shape (which tables exist, which columns they have) is built up through a
numbered sequence of migration files, so anyone can rebuild the exact same database from scratch
just by running them in order, and every change to the shape of the data is in version control
right alongside the code, not hand-applied and forgotten.

## Kafka — the postal service between every part of the bank

This is the single most important piece of infrastructure in the project, and the reason the
system is built the way it is. Instead of one part of the app directly calling another part (like
picking up the phone and calling someone directly), every part of the bank posts a message to a
named "topic" — think of it as a labelled mailbox — and whichever other parts care about that kind
of message pick it up and react, on their own schedule.

For example: when you submit a payment, the payment service doesn't personally walk it through
fraud-checking, then settlement, then sending you a notification. It just drops a "new payment"
message in a mailbox. A fraud-checking service is watching that mailbox, checks it, and drops its
own message in the next mailbox. A settlement service is watching *that* one, and so on. Each part
only knows about its own job and which mailbox to watch — nobody needs to know how the whole chain
fits together.

Why does this matter? Two big reasons. First, resilience — if the fraud-checking service is
temporarily down, payment messages just sit in the mailbox waiting, instead of the whole submission
failing outright. Second, it genuinely reflects how a real bank's systems are built — large banks
don't have one giant program doing everything, they have many independent systems reacting to
events, exactly like this.

There's also a safety net: if Kafka itself is briefly unavailable, messages are held in memory and
retried automatically rather than being silently lost.

## Redis — a fast scratchpad, not the permanent record

Redis holds things that need to be read very quickly and don't need to survive forever: the
current live exchange rate for each currency pair, and some short-term tracking used to spot
suspiciously rapid trading activity. It's a supporting cache, not where your money is recorded —
that's always Postgres.

## The AI integrations — a second opinion, never the decision-maker

Three places in the app call a real AI model over the internet, but in every case the AI is only
ever adding commentary on top of a decision that's already been made by ordinary code — it never
makes the decision itself.

- When something unusual happens (an oddly large price jump, unusually fast trading), a rule
  already decided that it's worth flagging. The app then asks Anthropic's Claude for one short
  sentence explaining how serious this looks and why — genuinely useful color, but if Claude is
  unreachable or fails, the flag still goes out with a plain, rule-written description instead. The
  system never waits on or depends on the AI to work correctly.
- When a payment settles, Anthropic's Claude is asked to write a short, friendly one- or
  two-sentence summary of what happened, for a customer-facing notification. Same rule: if it
  fails, the notification still goes out, just with a plainer message.
- When a Game Mode session ends, Claude is given the difficulty's rules and the full trade/loan
  history and asked for a few sentences explaining why the player won or lost and which moves
  helped or hurt most. Same rule again: if it fails or no key is configured, the player still gets
  a real debrief — a plain paragraph computed from the same data — just not an AI-written one.

## Watching the bank run — Prometheus, Grafana, and Jaeger

Three tools work together to answer "is the bank healthy, and if something's slow, why?"

- **Prometheus** continuously pulls numbers out of the running app — how many payments succeeded,
  how long fraud checks are taking, how backed-up the message queues are — over 150 different
  measurements in total.
- **Grafana** turns those numbers into live dashboards you can actually look at — graphs of
  transfer speed, payment success rate, fraud-check latency, updating in real time.
- **Jaeger** answers a more specific question: for one single request, exactly which step took the
  time? If a customer complains "my transfer took 8 seconds," this tool can show precisely which
  internal step — security check, business logic, database write — actually caused the delay,
  instead of someone having to guess.

None of these three are just switched on and left untested — each one was actually driven with
real traffic and its output checked against the real running system to prove it works, not just
configured and assumed to be fine.

## Chronicle Queue — a permanent, ultra-fast paper trail for every trade

Every trade executed on the FX/stock desk is also written to a separate, extremely fast local
journal (not the database) purely for record-keeping — the financial-industry equivalent of a
black box flight recorder. It's built to write records without pausing the program to do
routine memory cleanup, which matters for a system where every trade needs to be logged
without adding delay.

## Docker, Kubernetes, and Helm — running it consistently anywhere

Every part of the system — the app, the database, Kafka, Redis, the dashboards — runs inside
Docker containers, which is why the whole thing can be brought up with one command and behaves
identically on any machine. Beyond that, the project also has a full Kubernetes setup (the
industry-standard way to run this kind of system at scale across many machines) and was actually
tested running on a real local Kubernetes cluster, not just written and left unverified.

---

# Part 2 — For a Software Developer

## Postgres 18

Schema is managed entirely through Flyway (`src/main/resources/db/migration/`, `V1` through
`V11`), each migration representing a real, applied schema change — not squashed/rewritten history
except once, deliberately, early on before the schema had shipped anywhere (documented in
`HANDOFF.md`). Migrated from Postgres 16 → 18 mid-project, which required fixing a Postgres 18
volume-layout change in `docker-compose.yml`.

## Kafka 4.3.1

14 topics, all provisioned as `NewTopic` beans in `KafkaConfig.java` (broker
`auto.create.topics.enable=false`, so the app's bean definitions are the sole source of truth for
partition counts — see [KAFKA_SETUP.md](KAFKA_SETUP.md) for the incident where a race between
app-provisioning and broker auto-create silently produced a topic with the wrong partition count).

Full topic/producer/consumer table lives in [KAFKA_SETUP.md](KAFKA_SETUP.md); the shape worth
understanding is: `orders → orders-validated → trades` (the FX/stock desk) and
`payments → payments-validated` (settlement itself is an in-process saga, not
Kafka-choreographed — with `ledger-entries`, `fraud-alerts`, `notifications` fanning off along the
way) are two independent event chains, plus `account-activity`/`transfers`/`loans` as
broadcast/audit-only topics for the newer banking features that don't currently have a downstream
consumer. An earlier `settlements` topic that had neither a producer nor a consumer was removed
during a later cleanup pass rather than left as an unexplained provisioned-but-unused topic.

Two real production-grade bugs were found and fixed in this subsystem, both documented with full
root-cause writeups in [KAFKA_SETUP.md](KAFKA_SETUP.md):

1. Spring Boot 4 moved `KafkaAdmin` autoconfiguration into a new `spring-boot-kafka` module; without
   depending on it directly, topic auto-provisioning silently did nothing.
2. `KafkaConfig.producerFactory()`'s hardcoded properties bypassed `application.yml`, meaning
   `KafkaProducer.send()` could block a request thread for up to 60s (`max.block.ms`) if topic
   metadata wasn't cached yet — caught by noticing a suspiciously long Jaeger span, fixed by
   capping `max.block.ms` at 3s and routing metadata-wait timeouts into the existing fallback-retry
   queue.

`KafkaEventPublisher` also has a single-instance, in-memory fallback queue
(`fallback-queue.capacity`/`drain-interval-ms` in `application.yml`) that absorbs publishes during
a broker outage and drains them once Kafka recovers — this is what "resilience" means concretely
in this codebase, not just a claim.

## Redis 8

Two consumers: `PriceFeedServiceImpl` caches the current simulated rate per currency pair (with a
TTL), which `AccountServiceImpl.convert` reads directly to price a conversion — the FX desk and
account conversion share one source of truth for "what's the rate right now." `OrderVelocityTracker`
uses Redis to rate-limit rapid order submission per client; it's explicitly noted as
single-instance-correct only — a real multi-instance deployment would need this backed by
something shared and consistent, which Redis already is, but the current implementation wasn't
built with that in mind. JWT refresh tokens are **not** in Redis — they're a real Postgres table
(`refresh_tokens`) with a revoked flag, since token rotation needs the durability and query
guarantees of the relational store, not a cache.

## AI integrations — now via a real MCP server, not in-process

`ai/mcp/AnomalyDetectorImpl` (implements `AnomalyDetector`), `ai/mcp/ClaudeSummarizerImpl`
(implements `ClaudeSummarizer`), and `ai/mcp/GameCoachImpl` (implements `GameCoach`) don't call
Anthropic directly anymore. Each delegates to `ai/mcp/McpToolClient`, which is a real Model Context
Protocol client — `io.modelcontextprotocol.sdk:mcp-core`'s `McpClient.sync(...)` over Streamable
HTTP, not a hand-rolled REST call — talking to `bate-mcp-server`, a genuinely separate,
independently-deployable Spring Boot service (`bate-mcp-server/`, its own Maven project, its own
Dockerfile, its own container in `docker-compose.yml`) that's the *only* place in the whole system
holding a Claude API key now. `bate-banking-core` never sees `CLAUDE_API_KEY` at all; it only knows
`MCP_SERVER_URL`.

The fallback contract survived the move to a second process: `McpToolClient.callTool` connects
lazily (first use, not app startup — so `bate-mcp-server` being briefly down never blocks
`bate-banking-core`'s own health checks) and never throws. An unreachable server, a dropped
connection mid-call, or a real MCP `isError` result (`bate-mcp-server` itself has no
`CLAUDE_API_KEY` configured, or the Anthropic call failed) all collapse to `Optional.empty()`, and
each of the three `McpX` classes degrades exactly the way its old in-process `AnthropicX`
predecessor did — the rule's own description for anomalies, the raw context for payment summaries,
the rule-based paragraph for Game Mode debriefs. This is live-verified, not just unit-tested: with
no real Claude key configured anywhere, an ordinary background price-anomaly check made a genuine
MCP call, got a genuine `isError` result back, and degraded correctly — visible in `docker logs`
from a normal run (see `bate-mcp-server/README.md` for the captured log line).

`AnomalyDetectorImpl.explain` is called from `FraudDetectionServiceImpl`/`RiskServiceImpl` to
enrich an already-triggered rule (`fraud_detection_latency_seconds` — real p99 of 324ms measured
live pre-migration, see [OBSERVABILITY_PROOF.md](OBSERVABILITY_PROOF.md)) with a one-sentence
severity assessment. `ClaudeSummarizerImpl.summarize` is called from the settlement/notification
path to turn a payment event into a short customer-facing sentence. `GameCoachImpl.debrief` is
called from `GameServiceImpl.getDebrief` once a Game Mode session ends, given the difficulty's
rules plus the full trade/loan history, to write a few sentences on why the player won or lost —
see [GAME_MODE.md §9](GAME_MODE.md#9-ai-written-debrief) for the full shape of this one, including
the per-symbol P&L chart it's paired with on the frontend, and `bate-mcp-server/README.md` for why
this is real MCP (JSON-RPC, tool discovery, auto-generated schemas) rather than a REST facade that
merely looks like it.

## Observability: Prometheus, Grafana, Jaeger

All three were live-verified against real API traffic on 2026-08-14, with real trace IDs, real
metric values, and real dashboard screenshots captured in
[OBSERVABILITY_PROOF.md](OBSERVABILITY_PROOF.md) — summarized:

- **Jaeger**: required fixing a genuine Spring Boot 4.1.0 gap — neither of Boot 4's two
  tracing-autoconfiguration modules actually constructs a `Tracer` bean, so
  `NoopTracerAutoConfiguration` silently won and every span was generated and discarded, with no
  error anywhere. Fixed in `config/TracingConfig.java` (defines the missing `SdkTracerProvider` +
  `OtelTracer` bridge). 17 distinct traced operations confirmed, spanning HTTP, Kafka listeners,
  and `@Scheduled` tasks — not just the web layer.
- **Prometheus**: scrapes `/actuator/prometheus`, 151 distinct metric names, including custom
  business metrics wired directly into service code (`transfer_latency_seconds`,
  `fraud_detection_latency_seconds`, `payment_settlement_outcome_total`, `fx_trades_total`,
  `kafka_consumer_lag`, `kafka_fallback_queue_size`) — not just JVM/HTTP defaults.
- **Grafana**: `docker/grafana/dashboards/banking-platform.json`, auto-provisioned, 5 panels, all
  confirmed rendering real (non-empty) data once traffic was generated — including a
  previously-unmounted alert-rules file that was found and fixed during verification.

## Chronicle Queue

`chronicle/` — an off-heap, memory-mapped, zero-GC append-only journal that every FX/stock fill is
written to, separately from the Postgres `Trade` row. This is a deliberate low-latency technique
(avoiding GC pauses on the hot execution path) rather than a durability requirement Postgres
couldn't already satisfy — it demonstrates a real technique used in latency-sensitive trading
systems, with its own unit-tested reader/writer.

## Docker / Kubernetes / Helm

`docker/docker-compose.yml` runs the full stack locally (app, Postgres, Kafka, Redis, frontend,
Jaeger, Prometheus, Grafana) behind one Docker network. `k8s/` holds raw "production-shaped"
manifests assuming externally managed Postgres/Redis/Kafka; `helm/trading-platform/` is the
actually-deployable chart, with a `devDependencies.enabled` toggle for running everything
(including Postgres/Redis/Kafka as in-cluster pods) inside a local `kind` cluster — which is how it
was verified: all pods reaching `Running`, liveness/readiness probes reporting `UP` via
port-forward. This has not been verified against a real cloud Kubernetes service (GKE/EKS) —
stated directly in `HANDOFF.md` rather than implied.

## Where to go deeper

[KAFKA_SETUP.md](KAFKA_SETUP.md), [OBSERVABILITY_PROOF.md](OBSERVABILITY_PROOF.md),
[PERFORMANCE_BASELINE.md](PERFORMANCE_BASELINE.md), [DESIGN_DECISIONS.md](DESIGN_DECISIONS.md),
[OPERATIONS.md](OPERATIONS.md) each go one level deeper than this doc on their respective piece.
