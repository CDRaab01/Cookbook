"""Phase 7: recipe nutrition + log-to-diary via a faked Plate (httpx.MockTransport)."""

import uuid
from datetime import date

import httpx
import pytest
from jose import jwt
from sqlalchemy import select

from app.config import settings
from app.database import AsyncSessionLocal
from app.models.recipe import Recipe
from app.models.user import User
from app.services.plate_nutrition_service import (
    LogToPlateRequest,
    get_recipe_nutrition,
    log_recipe_for_confirmation,
    log_recipe_to_plate,
    retract_confirmation_log,
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


async def _household_member_and_shared_recipe(client) -> tuple[User, str]:
    """Owner creates a CHILI recipe and shares it (family recipe); the wife joins the household.
    Returns (wife_user, recipe_id) — the wife is a co-member, NOT the recipe's creator."""
    uid = uuid.uuid4().hex[:8]
    owner_email = f"fo_{uid}@cookbook.com"
    wife_email = f"fw_{uid}@cookbook.com"
    owner = (await client.post(
        "/auth/register", json={"name": "Owner", "email": owner_email, "password": "Testpass123!"}
    )).json()["access_token"]
    wife = (await client.post(
        "/auth/register", json={"name": "Wife", "email": wife_email, "password": "Testpass123!"}
    )).json()["access_token"]

    oh = {"Authorization": f"Bearer {owner}"}
    recipe_id = (await client.post("/recipes", json=CHILI, headers=oh)).json()["id"]
    share = await client.post(f"/recipes/{recipe_id}/share", json={"shared": True}, headers=oh)
    assert share.status_code == 200, share.text

    await client.post("/household/members", json={"email": wife_email}, headers=oh)
    acc = await client.post("/household/accept", headers={"Authorization": f"Bearer {wife}"})
    assert acc.status_code == 200, acc.text

    async with AsyncSessionLocal() as session:
        wife_user = (await session.execute(select(User).where(User.email == wife_email))).scalar_one()
    return wife_user, recipe_id


async def test_household_member_can_log_and_price_family_recipe(client, plate_configured):
    """Regression: a co-member logging a partner's FAMILY recipe used to 404 (the endpoints loaded
    the recipe creator-only). Family mode makes shared recipes collaborative, so both the log and
    the nutrition breakdown must succeed for the co-member."""
    wife, recipe_id = await _household_member_and_shared_recipe(client)

    async with AsyncSessionLocal() as db:
        logged = await log_recipe_to_plate(
            db,
            wife,
            uuid.UUID(recipe_id),
            LogToPlateRequest(date="2026-07-01", meal="dinner", servings_eaten=2.0),
            client=_fake_plate([]),
        )
    assert logged.logged == 2 and logged.skipped == 1

    async with AsyncSessionLocal() as db:
        nut = await get_recipe_nutrition(db, wife, uuid.UUID(recipe_id), client=_fake_plate([]))
    assert nut.matched_count == 2


async def test_non_member_cannot_log_someone_elses_recipe(client, plate_configured):
    """A stranger (no household, recipe not shared) still can't log another user's recipe → 404."""
    from fastapi import HTTPException

    _, _, recipe_id = await _user_with_recipe(client)  # a private recipe owned by someone else
    uid = uuid.uuid4().hex[:8]
    stranger_email = f"str_{uid}@cookbook.com"
    await client.post(
        "/auth/register",
        json={"name": "Str", "email": stranger_email, "password": "Testpass123!"},
    )
    async with AsyncSessionLocal() as session:
        stranger = (
            await session.execute(select(User).where(User.email == stranger_email))
        ).scalar_one()

    async with AsyncSessionLocal() as db:
        with pytest.raises(HTTPException) as exc:
            await log_recipe_to_plate(
                db,
                stranger,
                uuid.UUID(recipe_id),
                LogToPlateRequest(date="2026-07-01", meal="dinner"),
                client=_fake_plate([]),
            )
    assert exc.value.status_code == 404


def _fake_plate_capture(seen: list[dict]) -> httpx.AsyncClient:
    """Records every cross-app request (method/path/query/body) and acks log + unlog."""
    import json as _json

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(
            {
                "method": request.method,
                "path": request.url.path,
                "query": dict(request.url.params),
                "body": _json.loads(request.content) if request.content else None,
            }
        )
        if request.url.path == "/cross-app/log-recipe":
            return httpx.Response(200, json={"logged": 2, "skipped": 1})
        if request.url.path == "/cross-app/logged":
            return httpx.Response(200, json={"removed": 2})
        return httpx.Response(404)

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


async def test_confirmation_logs_and_retracts_with_client_ref(client, plate_configured):
    """A meal confirmation logs the recipe under a client_ref (portion-scaled), and the retract
    hits DELETE /cross-app/logged with that same ref — the confirm/adjust/un-eat correlation."""
    user, _, recipe_id = await _user_with_recipe(client)
    ref = f"cbplan:{recipe_id}:{user.id}"
    seen: list[dict] = []

    async with AsyncSessionLocal() as db:
        recipe = (
            await db.execute(select(Recipe).where(Recipe.id == uuid.UUID(recipe_id)))
        ).scalar_one()
        fake = _fake_plate_capture(seen)
        await log_recipe_for_confirmation(
            user.email,
            recipe,
            servings_eaten=2.0,
            date=date(2026, 7, 8),
            meal="dinner",
            client_ref=ref,
            client=fake,
        )
        await retract_confirmation_log(user.email, ref, client=fake)

    logged = next(r for r in seen if r["path"] == "/cross-app/log-recipe")
    assert logged["method"] == "POST"
    assert logged["body"]["client_ref"] == ref
    assert logged["body"]["meal"] == "dinner"
    beef = next(i for i in logged["body"]["items"] if i["name"] == "Ground beef")
    assert beef["quantity"] == pytest.approx(0.5)  # 1 lb / 4 servings * 2 eaten

    retracted = next(r for r in seen if r["path"] == "/cross-app/logged")
    assert retracted["method"] == "DELETE"
    assert retracted["query"]["client_ref"] == ref


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


# --- Link F: 'fits today' badge from Plate remaining macros --------------------------------


def _fake_plate_with_remaining(kcal_remaining: int | None) -> httpx.AsyncClient:
    """Answers resolve-foods (so nutrition computes) and remaining (kcal_remaining None -> 404)."""
    beef_id = str(uuid.uuid4())

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/cross-app/remaining":
            if kcal_remaining is None:
                return httpx.Response(404, json={"detail": "No active goal"})
            return httpx.Response(200, json={
                "date": "2026-07-11",
                "kcal_remaining": kcal_remaining,
                "protein_g_remaining": 50, "carbs_g_remaining": 40, "fat_g_remaining": 20,
            })
        # resolve-foods (POST): match the two known ingredients so per-serving kcal = 350.
        import json as _json
        body = _json.loads(request.content)
        items = []
        for item in body["items"]:
            if item["name"] == "Ground beef":
                items.append({"name": item["name"], "matched": True, "food_id": beef_id,
                              "kcal": 980.0, "protein_g": 84.0, "carbs_g": 0.0, "fat_g": 68.0})
            elif item["name"] == "Beans":
                items.append({"name": item["name"], "matched": True, "food_id": str(uuid.uuid4()),
                              "kcal": 420.0, "protein_g": 28.0, "carbs_g": 76.0, "fat_g": 2.0})
            else:
                items.append({"name": item["name"], "matched": False})
        return httpx.Response(200, json={"items": items})

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


async def test_fits_today_true_when_serving_fits_remaining(client, plate_configured):
    user, _, recipe_id = await _user_with_recipe(client)
    async with AsyncSessionLocal() as db:
        result = await get_recipe_nutrition(
            db, user, uuid.UUID(recipe_id), client=_fake_plate_with_remaining(800)
        )
    assert result.per_serving.kcal == pytest.approx(350.0)
    assert result.fits_today is True  # 350 <= 800


async def test_fits_today_false_when_serving_exceeds_remaining(client, plate_configured):
    user, _, recipe_id = await _user_with_recipe(client)
    async with AsyncSessionLocal() as db:
        result = await get_recipe_nutrition(
            db, user, uuid.UUID(recipe_id), client=_fake_plate_with_remaining(100)
        )
    assert result.fits_today is False  # 350 > 100*1.05


async def test_fits_today_none_when_plate_has_no_goal(client, plate_configured):
    user, _, recipe_id = await _user_with_recipe(client)
    async with AsyncSessionLocal() as db:
        result = await get_recipe_nutrition(
            db, user, uuid.UUID(recipe_id), client=_fake_plate_with_remaining(None)  # 404
        )
    assert result.fits_today is None  # no badge


def _fake_plate_remaining_errors() -> httpx.AsyncClient:
    """resolve-foods works (so nutrition computes); /remaining 500s (so the badge drops)."""
    beef_id = str(uuid.uuid4())

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/cross-app/remaining":
            return httpx.Response(500)
        import json as _json

        body = _json.loads(request.content)
        items = [
            {"name": i["name"], "matched": True, "food_id": beef_id,
             "kcal": 700.0, "protein_g": 40.0, "carbs_g": 40.0, "fat_g": 30.0}
            if i["name"] in ("Ground beef", "Beans")
            else {"name": i["name"], "matched": False}
            for i in body["items"]
        ]
        return httpx.Response(200, json={"items": items})

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


async def test_fits_today_none_when_remaining_call_errors(client, plate_configured):
    """Nutrition still computes; a failing /remaining just drops the badge (degrade to absence)."""
    user, _, recipe_id = await _user_with_recipe(client)
    async with AsyncSessionLocal() as db:
        result = await get_recipe_nutrition(
            db, user, uuid.UUID(recipe_id), client=_fake_plate_remaining_errors()
        )
    assert result.matched_count > 0  # nutrition still worked
    assert result.fits_today is None  # badge dropped on the /remaining error
