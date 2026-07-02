"""Phase 7: recipe nutrition + log-to-diary via a faked Plate (httpx.MockTransport)."""

import uuid

import httpx
import pytest
from jose import jwt

from app.config import settings
from app.database import AsyncSessionLocal
from app.models.user import User
from app.services.plate_nutrition_service import (
    LogToPlateRequest,
    get_recipe_nutrition,
    log_recipe_to_plate,
)

SECRET = "shared-cross-app-secret"

CHILI = {
    "name": "Chili",
    "servings": 4,
    "ingredients": [
        {"name": "Ground beef", "quantity": 1, "unit": "lb"},
        {"name": "Beans", "quantity": 2, "unit": "can"},
        {"name": "Secret spice", "quantity": 1, "unit": "dash"},
    ],
}


@pytest.fixture
def plate_configured(monkeypatch):
    monkeypatch.setattr(settings, "plate_base_url", "https://plate.test")
    monkeypatch.setattr(settings, "cross_app_secret", SECRET)


async def _user_with_recipe(client) -> tuple[User, str, str]:
    uid = uuid.uuid4().hex[:8]
    email = f"nut_{uid}@cookbook.com"
    resp = await client.post(
        "/auth/register",
        json={"name": "Nut", "email": email, "password": "Testpass123!"},
    )
    token = resp.json()["access_token"]
    recipe = await client.post("/recipes", json=CHILI, headers={"Authorization": f"Bearer {token}"})
    async with AsyncSessionLocal() as session:
        from sqlalchemy import select

        user = (await session.execute(select(User).where(User.email == email))).scalar_one()
    return user, token, recipe.json()["id"]


def _fake_plate(requests_seen: list[dict]) -> httpx.AsyncClient:
    beef_id = str(uuid.uuid4())

    def handler(request: httpx.Request) -> httpx.Response:
        token = request.headers["Authorization"].removeprefix("Bearer ")
        payload = jwt.decode(token, SECRET, algorithms=["HS256"])
        assert payload["type"] == "cross_app"
        import json as _json

        body = _json.loads(request.content)
        requests_seen.append({"path": request.url.path, "body": body})

        if request.url.path == "/cross-app/resolve-foods":
            items = []
            for item in body["items"]:
                if item["name"] == "Ground beef":
                    items.append(
                        {
                            "name": item["name"],
                            "matched": True,
                            "food_id": beef_id,
                            "food_name": "Ground Beef 85/15",
                            "kcal": 980.0,
                            "protein_g": 84.0,
                            "carbs_g": 0.0,
                            "fat_g": 68.0,
                        }
                    )
                elif item["name"] == "Beans":
                    items.append(
                        {
                            "name": item["name"],
                            "matched": True,
                            "food_id": str(uuid.uuid4()),
                            "food_name": "Black Beans",
                            "kcal": 420.0,
                            "protein_g": 28.0,
                            "carbs_g": 76.0,
                            "fat_g": 2.0,
                        }
                    )
                else:
                    items.append({"name": item["name"], "matched": False})
            return httpx.Response(200, json={"items": items})

        if request.url.path == "/cross-app/log-recipe":
            usable = [i for i in body["items"] if i["name"] != "Secret spice"]
            return httpx.Response(
                200, json={"logged": len(usable), "skipped": len(body["items"]) - len(usable)}
            )

        return httpx.Response(404)

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


async def test_nutrition_breakdown_totals_and_persisted_matches(client, plate_configured):
    user, token, recipe_id = await _user_with_recipe(client)
    seen: list[dict] = []

    async with AsyncSessionLocal() as db:
        result = await get_recipe_nutrition(
            db, user, uuid.UUID(recipe_id), client=_fake_plate(seen)
        )

    assert result.total_count == 3
    assert result.matched_count == 2
    assert result.totals.kcal == pytest.approx(1400.0)
    assert result.per_serving.kcal == pytest.approx(350.0)  # servings=4
    unmatched = next(i for i in result.items if i.name == "Secret spice")
    assert unmatched.matched is False

    # Matched Plate food ids were persisted on the ingredients.
    detail = (
        await client.get(f"/recipes/{recipe_id}", headers={"Authorization": f"Bearer {token}"})
    ).json()
    beef = next(i for i in detail["ingredients"] if i["name"] == "Ground beef")
    assert beef["plate_food_id"] is not None


async def test_log_to_plate_scales_by_servings_eaten(client, plate_configured):
    user, _, recipe_id = await _user_with_recipe(client)
    seen: list[dict] = []

    async with AsyncSessionLocal() as db:
        result = await log_recipe_to_plate(
            db,
            user,
            uuid.UUID(recipe_id),
            LogToPlateRequest(date="2026-07-01", meal="dinner", servings_eaten=2.0),
            client=_fake_plate(seen),
        )

    assert result.logged == 2
    assert result.skipped == 1
    logged = next(r for r in seen if r["path"] == "/cross-app/log-recipe")
    assert logged["body"]["meal"] == "dinner"
    assert logged["body"]["recipe_name"] == "Chili"
    beef = next(i for i in logged["body"]["items"] if i["name"] == "Ground beef")
    # 1 lb for 4 servings, 2 servings eaten ⇒ 0.5 lb.
    assert beef["quantity"] == pytest.approx(0.5)


async def test_endpoints_503_when_unconfigured(auth_client, monkeypatch):
    monkeypatch.setattr(settings, "plate_base_url", None)
    monkeypatch.setattr(settings, "cross_app_secret", None)
    recipe = (await auth_client.post("/recipes", json=CHILI)).json()

    resp = await auth_client.get(f"/recipes/{recipe['id']}/nutrition")
    assert resp.status_code == 503
    resp = await auth_client.post(
        f"/recipes/{recipe['id']}/log-to-plate",
        json={"date": "2026-07-01", "meal": "dinner"},
    )
    assert resp.status_code == 503
