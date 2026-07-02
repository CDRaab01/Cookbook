from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    database_url: str
    secret_key: str
    algorithm: str = "HS256"
    access_token_expire_minutes: int = 30
    refresh_token_expire_days: int = 7

    # Test-suite escape hatch: SQLAlchemy's default pool binds pooled asyncpg connections to
    # the event loop that created them, which fights pytest-asyncio's per-test loops (Plate's
    # suite hit exactly this on the dev machine). NullPool opens a fresh connection per
    # session — slower, loop-safe. The conftest enables it; deployments leave it off.
    db_nullpool: bool = False

    # Security hardening for public (e.g. Cloudflare Tunnel) multi-user deployments.
    # When set, /auth/register requires a matching invite_code. Leave unset for an open
    # (local/dev or trusted-network) deployment.
    registration_invite_code: str | None = None
    # Trust X-Forwarded-For / CF-Connecting-IP for the rate-limit client key. Only enable
    # behind a trusted reverse proxy (Cloudflare Tunnel, nginx) — otherwise clients can spoof it.
    trust_proxy: bool = False
    # Emit Strict-Transport-Security. Enable only when served over HTTPS (TLS at the proxy/edge).
    hsts_enabled: bool = False
    # Expose the interactive API docs (/docs, /redoc, /openapi.json). Disable on public deploys.
    docs_enabled: bool = True

    # Build/deploy stamp surfaced by GET /version so the app can show what's running
    # (and confirm a redeploy landed). Injected at deploy time; "unknown" for a manual/dev run.
    git_sha: str = "unknown"
    built_at: str = "unknown"

    # External recipe discovery (Spoonacular, Phase 5 — CLAUDE.md §5). Server-side key only.
    # Unset ⇒ the discover/import endpoints are disabled (503).
    spoonacular_api_key: str | None = None
    spoonacular_base_url: str = "https://api.spoonacular.com"
    # Use Spoonacular via RapidAPI instead of direct: set this to the RapidAPI host and put the
    # RapidAPI key in spoonacular_api_key (auth then uses X-RapidAPI-Key/Host headers).
    spoonacular_rapidapi_host: str | None = None
    # Max recipes returned per discovery search.
    recipe_discover_limit: int = 10
    # Timeout (seconds) for outbound Spoonacular calls.
    external_timeout_seconds: float = 8.0

    # Plate integration (Phases 6–7 — CLAUDE.md §6). Cookbook reads recipe exports and food
    # matches from Plate, and writes diary entries, over Plate's cross-app surface. Auth is a
    # short-lived JWT signed with `cross_app_secret` carrying the user's email; Plate validates
    # it with the SAME secret and resolves its own user by email. Both unset ⇒ the integration
    # is disabled, which is the case in CI and any deploy without Plate.
    plate_base_url: str | None = None
    cross_app_secret: str | None = None
    # TTL of the minted cross-app token — only needs to outlive a single request round-trip.
    cross_app_token_ttl_seconds: int = 60
    # Timeout (seconds) for outbound calls to Plate.
    plate_timeout_seconds: float = 8.0

    # Optional SMTP — if unset, reset codes are printed to stdout instead
    smtp_host: str | None = None
    smtp_port: int = 587
    smtp_user: str | None = None
    smtp_password: str | None = None
    smtp_from: str = "noreply@cookbook.local"
    # True = SSL on port 465 (Outlook, Yahoo). False (default) = STARTTLS on port 587 (Gmail).
    smtp_use_ssl: bool = False


settings = Settings()
