# Bate Banking — Frontend

The real client for the app described in the [root README](../README.md) — not a demo shell.
Signup, login, accounts, transfers, loans, FX/stock trading, and Game Mode all run against the real
backend API, with cookie-based auth (HTTP-only, `SameSite=Strict`) rather than a token in
`localStorage`.

Stack: React 19 + TypeScript, Vite, Tailwind, Zustand (session state), TanStack Query (server
state), React Router. See [docs/PROJECT_EXPLAINED.md](../docs/PROJECT_EXPLAINED.md) for how this
fits into the rest of the system, and [docs/INFRASTRUCTURE_EXPLAINED.md](../docs/INFRASTRUCTURE_EXPLAINED.md)
for why the frontend and backend are served same-origin through nginx (`nginx.conf`) rather than
across two origins with CORS — it's what makes the `SameSite=Strict` cookies actually work.

## Running it

From the repo root, `./scripts/local-setup.sh` builds and serves this alongside the rest of the
stack. To run just the frontend against an already-running backend:

```bash
npm install
npm run dev       # Vite dev server, proxies /v1 (incl. /v1/auth) and /actuator to localhost:8080
```

`npm run build` produces the production bundle `Dockerfile` copies into the nginx image used by
`docker-compose.yml`.

## Layout

```
src/
├── pages/        Login, Signup, Dashboard, Accounts, Transfer, Trading (FX/stock desk),
│                 Loans, Settings, GameLobbyPage, GamePlayPage
├── hooks/        one TanStack Query hook module per domain (useAccounts.ts, useGame.ts, ...)
├── store/        Zustand — authStore (who's logged in), appStore (small cross-page UI state)
├── api/          axios client: cookie-based auth, automatic refresh-and-retry on a 401
├── components/   shared UI pieces
└── lib/          formatting helpers, style constants, guestId.ts (Game Mode's anonymous-play id)
```
