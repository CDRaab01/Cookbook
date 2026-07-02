"""Plate → Cookbook recipe migration (Phase 6), with Plate faked via httpx.MockTransport."""

import uuid

import httpx
import pytest
from jose import jwt

from app.config import settings
from app.database import AsyncSessionLocal
from app.models.user import User
from app.services.plate_migration_service import migrate_from_plate

SECRET = "shared-cross-app-secret"

PLATE_EXPORT = [
    {
        "id": str(uuid.uuid4()),
        "name": "Banana Bowl",
        "description": "From Plate",
        "items": [
            {"food_name": "Banana", "quantity": 118, "unit": "g"},
            {"food_name": "Greek Yogurt", "quantity": 200, "unit": "g"},
        ],
    },
    {
        "id": str(uuid.uuid4()),
        "name": "Empty One",
        "description": None,
        "items": [],
    },
]


@pytest.fixture
def plate_configured(monkeypatch):
    monkeypatch.setattr(settings, "plate_base_url", "https://plate.test")
    monkeypatch.setattr(settings, "cross_app_secret", SECRET)


def _plate_client(seen_tokens: list[dict]) -> httpx.AsyncClient:
    """A fake Plate that validates the cross-app token like the real one would."""

    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/recipes/export"
        token = request.headers["Authorization"].removeprefix("Bearer ")
        payload = jwt.decode(token, SECRET, algorithms=["HS256"])
        seen_tokens.append(payload)
        if payload.get("type") != "cross_app":
            return httpx.Response(401)
        return httpx.Response(200, json=PLATE_EXPORT)

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


async def _register_user(client) -> tuple[User, str]:
    uid = uuid.uuid4().hex[:8]
    email = f"mig_{uid}@cookbook.com"
    resp = await client.post(
        "/auth/register",
        json={"name": "Migrator", "email": email, "password": "Testpass123!"},
    )
    token = resp.json()["access_token"]
    async with AsyncSessionLocal() as session:
        from sqlalchemy import select

        user = (await session.execute(select(User).where(User.email == email))).scalar_one()
    return user, token


async def test_migrate_imports_recipes_and_is_idempotent(client, plate_configured):
    user, token = await _register_user(client)
    seen: list[dict] = []

    async with AsyncSessionLocal() as db:
        result = await migrate_from_plate(db, user, client=_plate_client(seen))
    assert result.imported == 2
    assert result.skipped == 0
    # The minted token carried the right identity + type.
    assert seen[0]["email"] == user.email
    assert seen[0]["type"] == "cross_app"

    # Re-running skips everything (idempotent).
    async with AsyncSessionLocal() as db:
        again = await migrate_from_plate(db, user, client=_plate_client(seen))
    assert again.imported == 0
    assert again.skipped == 2

    # The recipes are ordinary Cookbook recipes, flagged with their origin.
    resp = await client.get("/recipes", headers={"Authorization": f"Bearer {token}"})
    recipes = resp.json()
    banana = next(r for r in recipes if r["name"] == "Banana Bowl")
    assert banana["source"] == "plate"
    assert banana["ingredient_count"] == 2

    detail = (
        await client.get(f"/recipes/{banana['id']}", headers={"Authorization": f"Bearer {token}"})
    ).json()
    assert detail["ingredients"][0]["name"] == "Banana"
    assert detail["ingredients"][0]["quantity"] == 118
    assert detail["ingredients"][0]["unit"] == "g"


async def test_migrate_endpoint_503_when_unconfigured(auth_client, monkeypatch):
    monkeypatch.setattr(settings, "plate_base_url", None)
    monkeypatch.setattr(settings, "cross_app_secret", None)
    resp = await auth_client.post("/migrate/plate")
    assert resp.status_code == 503


async def test_plate_rejection_maps_to_502(client, plate_configured):
    user, _ = await _register_user(client)

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401)

    rejecting = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    async with AsyncSessionLocal() as db:
        with pytest.raises(Exception) as excinfo:
            await migrate_from_plate(db, user, client=rejecting)
    assert getattr(excinfo.value, "status_code", None) == 502
