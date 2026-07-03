"""Pantry CRUD, staples lifecycle, and "what can I make?" suggestions."""

import httpx

from app.recipes_ext.base import IngredientSearchHit
from app.recipes_ext.spoonacular import SpoonacularSource
from app.services.pantry_service import DEFAULT_STAPLES


async def _register_second_user(client):
    import uuid as _uuid

    resp = await client.post(
        "/auth/register",
        json={
            "name": "Other",
            "email": f"other_{_uuid.uuid4().hex[:8]}@cookbook.com",
            "password": "Testpass123!",
        },
    )
    assert resp.status_code == 201
    return resp.json()["access_token"]


async def _create_recipe(auth_client, name, ingredient_names):
    resp = await auth_client.post(
        "/recipes",
        json={
            "name": name,
            "ingredients": [{"name": n} for n in ingredient_names],
            "steps": ["Cook."],
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


class TestPantryCrud:
    async def test_empty_pantry(self, auth_client):
        resp = await auth_client.get("/pantry")
        assert resp.status_code == 200
        assert resp.json() == []

    async def test_add_and_list(self, auth_client):
        resp = await auth_client.post("/pantry/items", json={"name": "Cheddar cheese"})
        assert resp.status_code == 201
        body = resp.json()
        assert body["name"] == "Cheddar cheese"
        assert body["category"] == "dairy"  # auto-guessed
        assert body["source"] == "manual"

        listed = (await auth_client.get("/pantry")).json()
        assert [i["name"] for i in listed] == ["Cheddar cheese"]

    async def test_add_duplicate_returns_existing(self, auth_client):
        first = (await auth_client.post("/pantry/items", json={"name": "Eggs"})).json()
        second = (
            await auth_client.post("/pantry/items", json={"name": "egg", "category": "dairy"})
        ).json()
        assert second["id"] == first["id"]
        assert len((await auth_client.get("/pantry")).json()) == 1

    async def test_update_and_delete(self, auth_client):
        item = (await auth_client.post("/pantry/items", json={"name": "Mlik"})).json()
        patched = (
            await auth_client.patch(
                f"/pantry/items/{item['id']}", json={"name": "Milk", "category": "dairy"}
            )
        ).json()
        assert patched["name"] == "Milk"
        assert patched["category"] == "dairy"

        resp = await auth_client.delete(f"/pantry/items/{item['id']}")
        assert resp.status_code == 204
        assert (await auth_client.get("/pantry")).json() == []

    async def test_invalid_category_rejected(self, auth_client):
        resp = await auth_client.post("/pantry/items", json={"name": "Thing", "category": "fridge"})
        assert resp.status_code == 422

    async def test_cross_user_isolation(self, auth_client):
        item = (await auth_client.post("/pantry/items", json={"name": "Steak"})).json()
        other_token = await _register_second_user(auth_client)
        headers = {"Authorization": f"Bearer {other_token}"}
        assert (await auth_client.get("/pantry", headers=headers)).json() == []
        resp = await auth_client.delete(f"/pantry/items/{item['id']}", headers=headers)
        assert resp.status_code == 404


class TestConfirm:
    async def test_confirm_merges_by_default(self, auth_client):
        await auth_client.post("/pantry/items", json={"name": "Butter"})
        resp = await auth_client.post(
            "/pantry/confirm",
            json={"items": [{"name": "Pasta"}, {"name": "butter"}, {"name": "PASTA"}]},
        )
        assert resp.status_code == 200
        names = sorted(i["name"] for i in resp.json())
        assert names == ["Butter", "Pasta"]  # in-batch dupe collapsed, existing row kept
        scanned = next(i for i in resp.json() if i["name"] == "Pasta")
        assert scanned["source"] == "scan"

    async def test_confirm_replace_swaps_pantry(self, auth_client):
        await auth_client.post("/pantry/items", json={"name": "Old thing"})
        resp = await auth_client.post(
            "/pantry/confirm",
            json={"items": [{"name": "New thing"}], "replace": True},
        )
        assert [i["name"] for i in resp.json()] == ["New thing"]


class TestStaples:
    async def test_defaults_before_confirmation(self, auth_client):
        body = (await auth_client.get("/pantry/staples")).json()
        assert body["confirmed"] is False
        assert body["staples"] == DEFAULT_STAPLES

    async def test_put_confirms_and_persists(self, auth_client):
        resp = await auth_client.put(
            "/pantry/staples", json={"staples": ["salt", "olive oil", "Salt "]}
        )
        body = resp.json()
        assert body["confirmed"] is True
        # "Salt " dedupes against "salt" on the normalized name; first spelling wins.
        assert sorted(body["staples"]) == ["olive oil", "salt"]

        again = (await auth_client.get("/pantry/staples")).json()
        assert again["confirmed"] is True
        assert len(again["staples"]) == 2

    async def test_empty_put_stays_confirmed(self, auth_client):
        await auth_client.put("/pantry/staples", json={"staples": ["salt"]})
        body = (await auth_client.put("/pantry/staples", json={"staples": []})).json()
        assert body["confirmed"] is True
        assert body["staples"] == []


class _FakeSource:
    def __init__(self):
        self.calls = []

    async def find_by_ingredients(self, ingredients, *, limit):
        self.calls.append((list(ingredients), limit))
        return [
            IngredientSearchHit(
                source_id="777",
                title="Web pasta bake",
                image="http://img",
                used_count=3,
                missed_count=1,
                missing=["cream"],
            )
        ]


class TestSuggestions:
    async def test_empty_pantry_empty_payload(self, auth_client):
        body = (await auth_client.get("/pantry/suggestions")).json()
        assert body == {"cookbook": [], "external": [], "external_available": False}

    async def test_local_matches_with_staples(self, auth_client):
        await _create_recipe(
            auth_client, "Carbonara", ["spaghetti", "eggs", "parmesan cheese", "salt"]
        )
        await _create_recipe(auth_client, "Beef stew", ["beef", "carrot", "potato", "onion"])
        await auth_client.post(
            "/pantry/confirm",
            json={"items": [{"name": "spaghetti"}, {"name": "eggs"}, {"name": "parmesan"}]},
        )
        body = (await auth_client.get("/pantry/suggestions")).json()
        assert [c["name"] for c in body["cookbook"]] == ["Carbonara"]  # salt is a default staple
        match = body["cookbook"][0]
        assert match["matched"] == match["total"]
        assert match["missing"] == []
        assert body["external_available"] is False  # no Spoonacular key in tests

    async def test_missing_list_reported(self, auth_client):
        await _create_recipe(auth_client, "Stir fry", ["chicken", "broccoli", "soy sauce"])
        await auth_client.post("/pantry/items", json={"name": "chicken"})
        body = (await auth_client.get("/pantry/suggestions?max_missing=1")).json()
        assert [c["name"] for c in body["cookbook"]] == ["Stir fry"]
        assert body["cookbook"][0]["missing"] == ["broccoli"]

    async def test_external_via_injected_source(self, auth_client, monkeypatch):
        import app.services.pantry_service as pantry_service

        fake = _FakeSource()
        monkeypatch.setattr(pantry_service.settings, "spoonacular_api_key", "test-key")
        monkeypatch.setattr(pantry_service, "_build_source", lambda client: fake)

        await auth_client.post("/pantry/items", json={"name": "chicken"})
        body = (await auth_client.get("/pantry/suggestions")).json()
        assert body["external_available"] is True
        assert body["external"][0]["source_id"] == "777"
        assert body["external"][0]["missing"] == ["cream"]
        # Pantry names lead the search; staples follow.
        searched = fake.calls[0][0]
        assert searched[0] == "chicken"

    async def test_external_failure_degrades(self, auth_client, monkeypatch):
        import app.services.pantry_service as pantry_service

        class _FailingSource:
            async def find_by_ingredients(self, ingredients, *, limit):
                raise httpx.ConnectError("down")

        monkeypatch.setattr(pantry_service.settings, "spoonacular_api_key", "test-key")
        monkeypatch.setattr(pantry_service, "_build_source", lambda client: _FailingSource())

        await _create_recipe(auth_client, "Omelette", ["eggs", "butter"])
        await auth_client.post("/pantry/items", json={"name": "eggs"})
        body = (await auth_client.get("/pantry/suggestions")).json()
        assert body["external_available"] is False
        assert body["external"] == []
        assert [c["name"] for c in body["cookbook"]] == ["Omelette"]  # local survives


class TestFindByIngredientsClient:
    async def test_params_and_mapping(self):
        seen = {}

        def handler(request: httpx.Request) -> httpx.Response:
            seen["params"] = dict(request.url.params)
            return httpx.Response(
                200,
                json=[
                    {
                        "id": 42,
                        "title": "Pasta bake",
                        "image": "http://img",
                        "usedIngredientCount": 3,
                        "missedIngredientCount": 2,
                        "missedIngredients": [{"name": "cream"}, {"name": "basil"}],
                    }
                ],
            )

        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        source = SpoonacularSource(client, "key", "https://api.spoonacular.com")
        hits = await source.find_by_ingredients(["pasta", "cheese"], limit=5)

        assert seen["params"]["ingredients"] == "pasta,cheese"
        assert seen["params"]["ranking"] == "2"
        assert seen["params"]["ignorePantry"] == "true"
        assert seen["params"]["number"] == "5"
        assert seen["params"]["apiKey"] == "key"
        assert hits[0].source_id == "42"
        assert hits[0].used_count == 3
        assert hits[0].missing == ["cream", "basil"]
