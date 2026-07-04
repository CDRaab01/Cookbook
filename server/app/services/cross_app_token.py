"""Acquiring a bearer token to authenticate an outbound cross-app call to Plate (ROADMAP T2 #5).

Prefers a dragonfly-id RS256 service token (the identity server's POST /cross-app/token, using
Cookbook's confidential client credentials) so Plate validates it against the JWKS it already
trusts — no shared symmetric secret. Falls back to the legacy HS256 token signed with
``cross_app_secret`` while the suite migrates. Configuring ``cross_app_client_id`` +
``cross_app_client_secret`` (with ``suite_issuer``) flips Cookbook to the RS256 path.
"""

from datetime import datetime, timedelta, timezone

import httpx
from jose import jwt

from app.config import settings

_TIMEOUT_SECONDS = 8.0


def mint_legacy_cross_app_token(email: str) -> str:
    """Legacy HS256 cross-app token signed with the shared ``cross_app_secret``.

    Typed ``cross_app`` so it can't be confused with a normal Cookbook access token. Raises if no
    secret is configured.
    """
    if not settings.cross_app_secret:
        raise RuntimeError("cross_app_secret is not configured")
    expire = datetime.now(timezone.utc) + timedelta(seconds=settings.cross_app_token_ttl_seconds)
    return jwt.encode(
        {"email": email, "type": "cross_app", "exp": expire},
        settings.cross_app_secret,
        algorithm=settings.algorithm,
    )


def _service_token_url() -> str | None:
    """The dragonfly-id cross-app token endpoint, if this app is a configured confidential client."""
    if settings.cross_app_client_id and settings.cross_app_client_secret and settings.suite_issuer:
        return settings.suite_issuer.rstrip("/") + "/cross-app/token"
    return None


def cross_app_configured() -> bool:
    """Whether any cross-app auth path (RS256 service client or legacy secret) is available."""
    return _service_token_url() is not None or bool(settings.cross_app_secret)


async def fetch_cross_app_token(email: str, *, client: httpx.AsyncClient | None = None) -> str:
    """A bearer token authorizing a cross-app call on behalf of ``email``.

    RS256 from dragonfly-id when confidential-client creds are set; otherwise the legacy HS256
    token. Raises if neither is configured. ``client`` is injectable for tests.
    """
    url = _service_token_url()
    if url is None:
        return mint_legacy_cross_app_token(email)

    data = {
        "client_id": settings.cross_app_client_id,
        "client_secret": settings.cross_app_client_secret,
        "subject_email": email,
    }

    async def _do(c: httpx.AsyncClient) -> str:
        resp = await c.post(url, data=data)
        resp.raise_for_status()
        return resp.json()["access_token"]

    if client is not None:
        return await _do(client)
    async with httpx.AsyncClient(timeout=_TIMEOUT_SECONDS) as owned:
        return await _do(owned)
