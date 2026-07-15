"""Cooked-range provider surface (federated awareness Link A): RS256-only auth (no HS256, no
SSO-audience replay), range math, cap, and the consumer contract fixture."""

import datetime
import json
import pathlib
import time
import uuid

import pytest
import pytest_asyncio
from jose import jwt as jose_jwt

from app.config import settings
from app.database import AsyncSessionLocal
from app.models.cook_event import CookEvent
from app.models.recipe import Recipe
from app.models.user import User
from app.security import hash_password
from tests.test_suite_auth import _PRIVATE_PEM, _jwks, ISSUER, KID

TODAY = datetime.date.today()


@pytest.fixture
def suite_enabled(monkeypatch):
    """Mocked dragonfly-id: JWKS + issuer configured (mirrors test_suite_auth's fixture —
    fixtures don't import across modules, helpers do)."""
    monkeypatch.setattr(settings, "suite_jwks_url", "http://id.test/jwks")
    monkeypatch.setattr(settings, "suite_issuer", ISSUER)

    async def _fake_fetch(*, force: bool = False):
        return _jwks()

    monkeypatch.setattr("app.services.suite_auth._fetch_jwks", _fake_fetch)


def _cross_app_token(email: str, *, aud: str = "cross-app", iss: str = ISSUER) -> str:
    now = int(time.time())
    claims = {
        "iss": iss, "aud": aud, "azp": "magpie", "email": email,
        "type": "cross_app", "iat": now, "exp": now + 120,
    }
    return jose_jwt.encode(claims, _PRIVATE_PEM, algorithm="RS256", headers={"kid": KID})


@pytest_asyncio.fixture
async def cook_user(suite_enabled):
    """A user with recipes + cook events, under the mocked JWKS (suite_enabled fixture)."""
    async with AsyncSessionLocal() as session:
        user = User(
            name="Cook",
            email=f"cook_{uuid.uuid4().hex[:8]}@cookbook.test",
            hashed_password=hash_password("secret123"),
        )
        session.add(user)
        await session.flush()
        tikka = Recipe(user_id=user.id, name="Chicken Tikka")
        stirfry = Recipe(user_id=user.id, name="Veggie Stir Fry")
        session.add_all([tikka, stirfry])
        await session.flush()
        for days_ago, recipe in ((9, tikka), (5, tikka), (2, stirfry)):
            session.add(
                CookEvent(
                    user_id=user.id,
                    recipe_id=recipe.id,
                    cooked_at=datetime.datetime.now(datetime.timezone.utc)
                    - datetime.timedelta(days=days_ago),
                )
            )
        await session.commit()
        await session.refresh(user)
        return user


def _range_params(days: int = 13) -> dict:
    return {"start": (TODAY - datetime.timedelta(days=days)).isoformat(), "end": TODAY.isoformat()}


async def test_cooked_requires_token(client, suite_enabled):
    resp = await client.get("/cross-app/cooked", params=_range_params())
    assert resp.status_code == 401


async def test_cooked_rejects_suite_audience(client, cook_user):
    """An SSO token (aud='suite') must never work on a cross-app surface."""
    token = _cross_app_token(cook_user.email, aud="suite")
    resp = await client.get(
        "/cross-app/cooked", params=_range_params(),
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 401


async def test_cooked_rejects_hs256_legacy_token(client, cook_user):
    """RS256-only surface: a legacy shared-secret HS256 token is refused outright."""
    legacy = jose_jwt.encode(
        {"email": cook_user.email, "type": "cross_app",
         "exp": int(time.time()) + 120},
        "any-shared-secret", algorithm="HS256",
    )
    resp = await client.get(
        "/cross-app/cooked", params=_range_params(),
        headers={"Authorization": f"Bearer {legacy}"},
    )
    assert resp.status_code == 401


async def test_cooked_unknown_email_401s(client, suite_enabled):
    token = _cross_app_token("nobody@cookbook.test")
    resp = await client.get(
        "/cross-app/cooked", params=_range_params(),
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 401


async def test_cooked_counts_events_and_distinct_recipes(client, cook_user):
    token = _cross_app_token(cook_user.email)
    resp = await client.get(
        "/cross-app/cooked", params=_range_params(),
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["count"] == 3
    assert body["distinct_recipes"] == 2
    assert body["events"][0]["recipe_name"] == "Chicken Tikka"  # oldest first
    assert all(set(e) == {"date", "recipe_name"} for e in body["events"])  # names+dates ONLY


async def test_cooked_range_cap(client, cook_user):
    token = _cross_app_token(cook_user.email)
    resp = await client.get(
        "/cross-app/cooked", params=_range_params(days=32),
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 422


async def test_cooked_matches_contract_fixture(client, cook_user):
    fixture = json.loads(
        (pathlib.Path(__file__).parent / "contracts" / "cooked_range.json").read_text()
    )
    token = _cross_app_token(cook_user.email)
    resp = await client.get(
        "/cross-app/cooked", params=_range_params(),
        headers={"Authorization": f"Bearer {token}"},
    )
    body = resp.json()
    assert set(body.keys()) == set(fixture.keys()) - {"_comment"}
    assert set(body["events"][0].keys()) == set(fixture["events"][0].keys())


# --- planned-meals provider (Link E) ------------------------------------------------------


async def test_plan_returns_slot_and_name_only(client, cook_user):
    from app.models.meal_plan import MealPlanEntry
    from app.models.recipe import Recipe
    from app.models.shopping_list import ShoppingList

    async with AsyncSessionLocal() as session:
        recipe = Recipe(user_id=cook_user.id, name="Chicken Tikka")
        # The plan is scoped to a list; cross-app reports the user's default (oldest) list.
        default_list = ShoppingList(user_id=cook_user.id, name="Groceries")
        session.add_all([recipe, default_list])
        await session.flush()
        session.add_all([
            MealPlanEntry(
                user_id=cook_user.id, list_id=default_list.id, date=TODAY,
                slot="dinner", recipe_id=recipe.id,
            ),
            MealPlanEntry(
                user_id=cook_user.id, list_id=default_list.id, date=TODAY,
                slot="lunch", note="Leftovers",
            ),
            # Another day — must not appear.
            MealPlanEntry(
                user_id=cook_user.id, list_id=default_list.id,
                date=TODAY + datetime.timedelta(days=1),
                slot="dinner", recipe_id=recipe.id,
            ),
        ])
        await session.commit()

    token = _cross_app_token(cook_user.email)
    resp = await client.get(
        "/cross-app/plan", params={"date": TODAY.isoformat()},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["date"] == TODAY.isoformat()
    slots = {e["slot"]: e["recipe_name"] for e in body["entries"]}
    assert slots == {"dinner": "Chicken Tikka", "lunch": "Leftovers"}  # note falls back to name
    # slot + name + the eaten flag (so Plate's coach knows planned vs actually-happened); nothing more.
    assert all(set(e.keys()) == {"slot", "recipe_name", "eaten"} for e in body["entries"])
    assert all(e["eaten"] is False for e in body["entries"])  # nothing marked eaten yet


async def test_plan_requires_cross_app_token(client, suite_enabled):
    resp = await client.get("/cross-app/plan", params={"date": TODAY.isoformat()})
    assert resp.status_code == 401


async def test_plan_empty_day(client, cook_user):
    token = _cross_app_token(cook_user.email)
    resp = await client.get(
        "/cross-app/plan", params={"date": TODAY.isoformat()},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert resp.json()["entries"] == []
