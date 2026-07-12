"""Magpie-awareness: this month's grocery spend badge on the shopping list (federated awareness
Link D). Best-effort and read-only — a faked Magpie (httpx.MockTransport) answers /cross-app/summary,
and any failure / unset integration degrades to None (the tile hides), never an error."""

import datetime

import httpx
import pytest
from jose import jwt

from app.config import settings
from app.services.grocery_spend_service import fetch_month_grocery_spend

SECRET = "shared-cross-app-secret"
TODAY = datetime.date.today()


@pytest.fixture
def magpie_configured(monkeypatch):
    monkeypatch.setattr(settings, "magpie_base_url", "https://magpie.test")
    monkeypatch.setattr(settings, "cross_app_secret", SECRET)
    # Ensure the RS256 path is off so the token is minted locally (no token POST to fake).
    monkeypatch.setattr(settings, "cross_app_client_id", None)
    monkeypatch.setattr(settings, "cross_app_client_secret", None)


def _fake_magpie(grocery_spend: int | None, *, status: int = 200) -> httpx.AsyncClient:
    """Answers GET /cross-app/summary; ``grocery_spend=None`` with status!=200 simulates an error."""

    def handler(request: httpx.Request) -> httpx.Response:
        token = request.headers["Authorization"].removeprefix("Bearer ")
        payload = jwt.decode(token, SECRET, algorithms=["HS256"])
        assert payload["type"] == "cross_app"
        if request.url.path == "/cross-app/summary":
            if status != 200:
                return httpx.Response(status)
            # Whole-dollar aggregate; consumer only reads grocery_spend.
            return httpx.Response(
                200,
                json={
                    "start": request.url.params["start"],
                    "end": request.url.params["end"],
                    "income": 5000,
                    "spend": 1200,
                    "net": 3800,
                    "grocery_spend": grocery_spend,
                    "savings_goal": None,
                },
            )
        return httpx.Response(404)

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


async def test_returns_month_grocery_spend(magpie_configured):
    result = await fetch_month_grocery_spend("owner@cookbook.com", TODAY, client=_fake_magpie(287))
    assert result is not None
    assert result.spent_dollars == 287
    assert result.month == TODAY.replace(day=1).isoformat()


async def test_none_when_integration_off():
    # No magpie_configured fixture ⇒ magpie_base_url unset ⇒ absent, no HTTP attempted.
    result = await fetch_month_grocery_spend("owner@cookbook.com", TODAY)
    assert result is None


async def test_none_when_magpie_errors(magpie_configured):
    result = await fetch_month_grocery_spend(
        "owner@cookbook.com", TODAY, client=_fake_magpie(None, status=500)
    )
    assert result is None  # rule 7: degrade to absence, tile hides


async def test_window_is_first_of_month_through_today(magpie_configured):
    seen: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["start"] = request.url.params["start"]
        seen["end"] = request.url.params["end"]
        return httpx.Response(
            200,
            json={
                "start": seen["start"],
                "end": seen["end"],
                "income": 0,
                "spend": 0,
                "net": 0,
                "grocery_spend": 42,
                "savings_goal": None,
            },
        )

    fake = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    await fetch_month_grocery_spend("owner@cookbook.com", TODAY, client=fake)
    assert seen["start"] == TODAY.replace(day=1).isoformat()
    assert seen["end"] == TODAY.isoformat()
