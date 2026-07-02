# Cookbook — deployment

Self-hosted via Docker Compose, same operational model as Spotter and Plate (this host runs
all three side by side).

## Ports on this host

| App      | API (localhost)       | Postgres (localhost) |
|----------|-----------------------|----------------------|
| Spotter  | 127.0.0.1:8000        | 127.0.0.1:5432       |
| Plate    | 127.0.0.1:8001        | 127.0.0.1:5433       |
| Cookbook | **127.0.0.1:8003**    | **127.0.0.1:5434**   |

Inside the compose network the server always listens on 8000 (the tunnel targets
`http://server:8000`); only host-side access is remapped.

## First-time setup

1. `cp server/.env.example server/.env`, set `SECRET_KEY` (and later
   `SPOONACULAR_API_KEY`, `PLATE_BASE_URL`, `CROSS_APP_SECRET` — the cross-app secret must
   MATCH Plate's, which already matches Spotter's; it's one ecosystem-wide secret).
2. `docker compose up -d --build` — migrations run on container boot.
3. Verify: `curl http://127.0.0.1:8003/health` → `{"status":"ok"}`.

## Public hostname (Cloudflare Tunnel)

Same pattern as Spotter/Plate: create a remotely-managed tunnel in the Cloudflare dashboard,
point a public hostname (e.g. `cookbook.dragonflymedia.org`) at `http://server:8000`, put the
token in the root `.env` as `TUNNEL_TOKEN`, and set `COMPOSE_PROFILES=tunnel` in the root
`.env` so a plain `docker compose up` keeps the tunnel in the managed set across deploys.
In `server/.env` set `TRUST_PROXY=true`, `HSTS_ENABLED=true`, `DOCS_ENABLED=false`, and a
`REGISTRATION_INVITE_CODE`.

## Remote redeploy on push to main

Mirrors Spotter/Plate exactly:

1. Register a self-hosted GitHub Actions runner on this host with the label `cookbook`
   (Settings → Actions → Runners → New self-hosted runner; run `run.cmd` interactively or
   install as a service).
2. Add a repository **Actions variable** `COOKBOOK_DIR` pointing at the canonical clone
   (e.g. `C:\Code\Cookbook`).
3. Pushing to `main` runs CI; when CI is green, the Deploy workflow calls
   `deploy/redeploy.ps1` on the host: fetch → `git reset --hard <sha>` →
   `docker compose up -d --build` → health-gate on `/health` → prune. `GIT_SHA`/`BUILT_AT`
   are stamped so `GET /version` (and the app's Settings → About) reports the running commit.
4. Rollback: Actions → Deploy → Run workflow → pass a previous SHA as `ref`.

`server/.env` (gitignored) and the `pgdata` volume survive `git reset --hard`.

## Verify a deploy

App **Settings → About** shows app + server version/commit, or
`curl http://127.0.0.1:8003/version` locally / the public hostname through the tunnel.
`530` = tunnel down; `503` on discovery endpoints = Spoonacular key not configured; `503` on
Plate-integration endpoints = `PLATE_BASE_URL`/`CROSS_APP_SECRET` not configured.
