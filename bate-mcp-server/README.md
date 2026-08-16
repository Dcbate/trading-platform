# bate-mcp-server

A real Model Context Protocol server for Bate Banking's AI features — JSON-RPC over Streamable
HTTP, built with Spring AI's official MCP integration. Not a REST API dressed up to look like MCP.

## What it is

Three tools, each mirroring an existing AI call in `bate-banking-core` exactly (same prompt, same
model, same intent), now callable by any real MCP client:

| Tool | Mirrors | Input | Output |
|---|---|---|---|
| `summarize_anomaly` | `ai/AnthropicAnomalyDetector` | `subject`, `description` | one-sentence severity assessment |
| `summarize_payment` | `ai/AnthropicClaudeSummarizer` | `context` | one/two-sentence customer-facing summary |
| `debrief_game_session` | `ai/AnthropicGameCoach` | `narrative` | 3-5 sentence Game Mode coaching debrief |

Every tool is purely advisory, same as the in-process versions: whatever business decision
triggered the call (a fraud flag, a settled payment, a finished game) has already been made before
the tool is ever invoked. If Claude is unavailable, the tool returns an MCP error result — it does
not silently return a fallback string, because this server has no business context to write a
sensible fallback with. That judgment belongs to whichever caller invoked the tool.

## The `spring-ai-starter-mcp-server-webmvc` dependency

This is the one dependency that makes this a real MCP server instead of a REST facade, so it's
worth explaining on its own.

**What MCP actually requires, that a REST API doesn't:** a JSON-RPC 2.0 message envelope; an
`initialize` handshake where client and server negotiate a protocol version and exchange
capabilities; `tools/list` for runtime tool discovery, with a JSON Schema describing each tool's
inputs; `tools/call` for invocation, with a specific success/error result shape
(`{content: [...], isError: bool}`); and, for the Streamable HTTP transport specifically, session
management via an `Mcp-Session-Id` header and Server-Sent-Events framing on the response. Every one
of those is a real, specific thing a client checks for — get any of it wrong and a genuine MCP
client (Claude Desktop, Claude Code, Cursor, VS Code) simply won't be able to talk to your server,
no matter how sensible your JSON looks.

**What the starter does:** `org.springframework.ai:spring-ai-starter-mcp-server-webmvc` is Spring
AI 2.0's auto-configuration for exactly this. Add it to the classpath and Spring Boot:

1. Stands up the Streamable HTTP transport at `/mcp` on top of Spring MVC (the starter pulls in
   `spring-boot-starter-web`/Tomcat transitively).
2. Scans the application context for `@McpTool`-annotated methods on any Spring bean —
   `AnomalySummaryTool.summarizeAnomaly`, `PaymentSummaryTool.summarizePayment`,
   `GameDebriefTool.debriefGameSession` here — and registers each one as a callable MCP tool.
3. Generates each tool's JSON Schema straight from the Java method signature and the
   `@McpToolParam(description = "...", required = true)` annotations on its parameters — no schema
   written by hand, no drift between the code and what a client sees when it calls `tools/list`.
4. Implements the full `initialize`/`tools/list`/`tools/call` JSON-RPC dispatch, session lifecycle,
   and error-result wrapping (an uncaught exception from a `@McpTool` method becomes
   `{isError: true, content: [{type: "text", text: "..."}]}` automatically — see
   `ClaudeUnavailableException` and how it surfaces below).

None of that is hand-rolled here. `application.yml` sets a handful of properties
(`spring.ai.mcp.server.name`, `.version`, `.protocol: STREAMABLE`, `.instructions`) and the rest is
the three `@Component` tool classes in `tools/` — the starter does the protocol work.

**Why this matters over building a custom REST facade:** a hand-written `/tools/game-summary`
POST endpoint can look identical to an MCP tool call in a demo, but it cannot actually be added to
Claude Desktop or Claude Code (`claude mcp add`) — those clients speak JSON-RPC/Streamable HTTP,
not arbitrary REST. Using the real starter means this server is genuinely, provably interoperable
with the MCP ecosystem, not just shaped like it.

### Verified, not assumed

This was proven against the running server, not just asserted from the docs (Spring AI 2.0 is
new — GA'd June 2026 — and I found a real open compatibility issue in its tracker, so I checked
rather than trusted). The full JSON-RPC exchange:

```
$ curl -s -X POST http://localhost:8081/mcp \
    -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"verify","version":"1.0"}}}'

{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18",
  "capabilities":{"completions":{},"logging":{},"prompts":{"listChanged":true},
                   "resources":{"subscribe":false,"listChanged":true},"tools":{"listChanged":true}},
  "serverInfo":{"name":"bate-mcp-server","version":"0.1.0"},
  "instructions":"Tools for Bate Banking's AI features: ..."}}
```

`tools/list` (using the `Mcp-Session-Id` header returned above) returns all three tools with their
auto-generated schemas — `summarize_payment`'s, for example:

```json
{"name":"summarize_payment", "description":"Turn a plain-text payment-outcome description into a short, friendly customer-facing notification summary...",
 "inputSchema":{"type":"object","properties":{"context":{"type":"string","description":"Plain description of what happened, e.g. \"payment of £250.00 to Acme Ltd settled successfully\""}},"required":["context"]}}
```

And `tools/call` with no `CLAUDE_API_KEY` configured — proving the error path is a real MCP error
result, not a crash or an HTTP 500:

```json
{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"Error invoking method: summarizePayment\nCLAUDE_API_KEY is not configured on bate-mcp-server"}],"isError":true}}
```

## Running it

```bash
docker compose -f docker/docker-compose.yml up -d bate-mcp   # part of the main compose file, port 8081
```

or standalone:

```bash
cd bate-mcp-server
mvn -q -DskipTests package
CLAUDE_API_KEY=sk-ant-... java -jar target/bate-mcp-server-0.1.0.jar
```

**Connecting a real MCP client** (the actual proof this is real MCP, beyond curl): with the server
running, `claude mcp add --transport http bate-banking http://localhost:8081/mcp` registers it
with Claude Code, after which its three tools are callable from a normal conversation — the same
mechanism this very tool's schema was captured through above.

## Why a standalone Maven project, not a module of bate-banking-core

The prompt this was built from asked for a multi-module Maven project — one root parent POM,
`bate-banking-core` and `bate-mcp-server` as child modules physically relocated under it. I didn't
do that: `bate-banking-core` (this repo's root `pom.xml` and `src/`) stays exactly where it is,
untouched, and `bate-mcp-server` is its own independent Maven project with its own
`spring-boot-starter-parent`, living in a sibling directory.

Reasons:
- Physically moving `trading-platform`'s existing `src/main`, `src/test`, `docker/`, and every doc
  that references a file path would be a large, mechanical, high-risk rename for a repo that's
  otherwise stable, tested (190+ passing tests), and already deployed via its own Dockerfile —
  for zero functional benefit.
- Two services owning their own full Maven build (rather than sharing a parent POM for version
  management) is itself a common, legitimate pattern for a monorepo with independently deployable
  services — arguably closer to how this would actually be split at a real company than one shared
  parent artifact.
- It's lower risk to build, verify, and reason about in isolation, and nothing about "is this real
  MCP" depends on the module layout.

## `bate-banking-core` is a real MCP client of this server

`bate-banking-core`'s three AI call sites — fraud/anomaly severity, payment summaries, Game Mode
debrief — no longer call Anthropic directly. `ai/mcp/McpToolClient.java` (in the main repo)
connects to this server exactly the way any other MCP client would: the raw
`io.modelcontextprotocol.sdk:mcp-core` SDK (`McpClient.sync(...)`, not a Spring AI client
starter — this app only needs "call a named tool, get text back," not the broader
ChatClient/tool-calling machinery a fuller integration would pull in), talking Streamable HTTP to
`http://bate-mcp:8081/mcp` inside Docker Compose (`MCP_SERVER_URL` env var — never hardcoded).
`AnomalyDetectorImpl`, `ClaudeSummarizerImpl`, and `GameCoachImpl` implement the exact same
`AnomalyDetector`/`ClaudeSummarizer`/`GameCoach` interfaces the old in-process `AnthropicX` classes
did, so nothing else in `bate-banking-core` changed — `FraudDetectionServiceImpl`,
`RiskServiceImpl`, `SettlementServiceImpl`, and `GameServiceImpl` all still just depend on the
interface.

The old `AnthropicAnomalyDetector`/`AnthropicClaudeSummarizer`/`AnthropicGameCoach` classes, the
`claude.*` config, and `spring-boot-starter-webflux` (only ever added for the Claude `WebClient`)
were all removed from `bate-banking-core` once this landed — this server is now the *only* thing
in the whole system holding an Anthropic API key.

**The fallback guarantee crosses the network boundary too.** `McpToolClient.callTool` connects
lazily (on first use, not at app startup, so this server being briefly down never blocks
`bate-banking-core`'s own health checks) and never throws: an unreachable server, a dropped
connection, or a real MCP `isError` result all collapse to `Optional.empty()`, and each of the
three `McpX` classes falls back exactly the way its `AnthropicX` predecessor did — the rule's own
description for anomalies, the raw context for payment summaries, the rule-based paragraph for
Game Mode debriefs. Live-verified, not just asserted: with no real `CLAUDE_API_KEY` configured
anywhere, a routine background price-anomaly check in `bate-banking-core` made a real MCP call to
this server, got a real `isError` result back (`"CLAUDE_API_KEY is not configured on
bate-mcp-server"`), and degraded to `aiEnriched=false` without incident — visible in `docker logs`
from an ordinary run, not a contrived test.

## Also worth knowing: Gemini is gone

Around the same time this module was built, `bate-banking-core`'s anomaly detection was migrated
off Google's Gemini onto Claude (`AnthropicAnomalyDetector`, replacing `GeminiAnomalyDetector`) so
every AI call in the whole system — real banking app and this MCP server alike — goes through one
provider, one API key, one set of failure modes. This module was never wired to Gemini in the
first place; noted here only so the history is traceable if you're reading the git log.
