"""Cross-app token acquisition (ROADMAP T2 #5): an RS256 token from dragonfly-id when Cookbook is a
configured confidential client, otherwise the legacy HS256 shared-secret token."""

import httpx
import pytest
from jose import jwt

from app.config import settings
from app.services.cross_app_token import cross_app_configured, fetch_cross_app_token

SECRET = "shared-cross-app-secret"


@pytest.fixture
def rs256_client_creds(monkeypatch):
    monkeypatch.setattr(settings, "cross_app_client_id", "cookbook")
    monkeypatch.setattr(settings, "cross_app_client_secret", "s3cret")
    monkeypatch.setattr(settings, "suite_issuer", "https://id.test")


@pytest.fixture
def legacy_secret(monkeypatch):
    monkeypatch.setattr(settings, "cross_app_client_id", None)
    monkeypatch.setattr(settings, "cross_app_client_secret", None)
    monkeypatch.setattr(settings, "cross_app_secret", SECRET)


async def test_fetch_uses_dragonfly_id_when_client_configured(rs256_client_creds):
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["url"] = str(request.url)
        captured["body"] = request.read().decode()
        return httpx.Response(200, json={"access_token": "rs256-xyz", "expires_in": 120})

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        token = await fetch_cross_app_token("cook@cookbook.com", client=client)

    assert token == "rs256-xyz"
    assert captured["url"].endswith("/cross-app/token")
    assert "client_id=cookbook" in captured["body"]
    assert "subject_email=cook" in captured["body"]


async def test_fetch_falls_back_to_hs256_without_client_creds(legacy_secret):
    token = await fetch_cross_app_token("cook@cookbook.com")
    payload = jwt.decode(token, SECRET, algorithms=["HS256"])
    assert payload["email"] == "cook@cookbook.com" and payload["type"] == "cross_app"


def test_cross_app_configured_true_with_secret(legacy_secret):
    assert cross_app_configured() is True
