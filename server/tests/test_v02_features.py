"""v0.2 endpoint coverage: favorites/tags, suggestions + category recall, discover preview."""

import pytest

from app.config import settings
from app.recipes_ext.base import NormalizedIngredient, NormalizedRecipe, RecipeSource, RecipeSummary

# ── Favorites + tags ─────────────────────────────────────────────────────────


async def test_create_with_tags_and_toggle_favorite(auth_client):
    resp = await auth_client.post(
        "/recipes",
        json={
            "name": "Grill Night Chicken",
            "tags": ["Grill", " weeknight ", "grill"],  # dedupe + normalize
            "ingredients": [{"name": "Chicken", "quantity": 2, "unit": "lb"}],
        },
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["tags"] == ["grill", "weeknight"]
    assert body["favorite"] is False

    resp = await auth_client.patch(f"/recipes/{body['id']}", json={"favorite": True})
    assert resp.json()["favorite"] is True

    # Summaries carry both.
    listed = (await auth_client.get("/recipes")).json()
    entry = next(r for r in listed if r["id"] == body["id"])
    assert entry["favorite"] is True
    assert entry["tags"] == ["grill", "weeknight"]

    # Tags replace wholesale; empty list clears.
    resp = await auth_client.patch(f"/recipes/{body['id']}", json={"tags": []})
    assert resp.json()["tags"] == []


async def test_notes_set_and_clear(auth_client):
    recipe = (
        await auth_client.post(
            "/recipes",
            json={"name": "Chili", "ingredients": [{"name": "Beans", "quantity": 2, "unit": "can"}]},
        )
    ).json()
    assert recipe["notes"] is None

    resp = await auth_client.patch(
        f"/recipes/{recipe['id']}", json={"notes": "Half the sugar next time."}
    )
    assert resp.json()["notes"] == "Half the sugar next time."

    # Other PATCHes leave notes untouched (null = skip).
    resp = await auth_client.patch(f"/recipes/{recipe['id']}", json={"favorite": True})
    assert resp.json()["notes"] == "Half the sugar next time."

    # "" clears, per the clearing convention.
    resp = await auth_client.patch(f"/recipes/{recipe['id']}", json={"notes": "  "})
    assert resp.json()["notes"] is None


async def test_too_many_tags_rejected(auth_client):
    resp = await auth_client.post(
        "/recipes",
        json={"name": "X", "tags": [f"tag{i}" for i in range(11)]},
    )
    assert resp.status_code == 422


# ── Suggestions + category recall ────────────────────────────────────────────


async def test_suggest_from_history_and_category_recall(auth_client):
    lst = (await auth_client.get("/lists/default")).json()

    # Manual add with an explicit (unusual) category teaches the history.
    await auth_client.post(
        f"/lists/{lst['id']}/items",
        json={"name": "Protein powder", "quantity": 1, "unit": "tub", "category": "frozen"},
    )
    # Re-add twice more to bump use_count.
    for _ in range(2):
        await auth_client.post(
            f"/lists/{lst['id']}/items", json={"name": "protein powder", "quantity": 1, "unit": "tub"}
        )

    resp = await auth_client.get("/lists/suggest", params={"q": "prot"})
    assert resp.status_code == 200, resp.text
    hits = resp.json()
    assert any(h["name"].lower() == "protein powder" and h["category"] == "frozen" for h in hits)

    # Category recall: the uncategorized re-adds landed in the remembered category, not "other".
    items = (await auth_client.get("/lists/default")).json()["items"]
    powder = next(i for i in items if i["name"].lower() == "protein powder")
    assert powder["category"] == "frozen"


async def test_manual_add_keyword_guess_when_no_history(auth_client):
    lst = (await auth_client.get("/lists/default")).json()
    resp = await auth_client.post(
        f"/lists/{lst['id']}/items", json={"name": "Cheddar cheese block"}
    )
    item = next(i for i in resp.json()["items"] if "Cheddar" in i["name"])
    assert item["category"] == "dairy"


async def test_recipe_adds_feed_suggestions(auth_client):
    recipe = (
        await auth_client.post(
            "/recipes",
            json={
                "name": "Taco Tuesday",
                "ingredients": [{"name": "Flank steak", "quantity": 1, "unit": "lb", "category": "meat"}],
            },
        )
    ).json()
    lst = (await auth_client.get("/lists/default")).json()
    await auth_client.post(f"/lists/{lst['id']}/add-recipe", json={"recipe_id": recipe["id"]})

    hits = (await auth_client.get("/lists/suggest", params={"q": "flank"})).json()
    assert any(h["name"] == "Flank steak" for h in hits)


async def test_suggest_blank_query_empty(auth_client):
    resp = await auth_client.get("/lists/suggest", params={"q": "  "})
    assert resp.json() == []


# ── Discover preview ─────────────────────────────────────────────────────────


class PreviewFakeSource(RecipeSource):
    source_tag = "fake"

    async def discover(self, query: str, *, limit: int) -> list[RecipeSummary]:
        return []

    async def fetch(self, source_id: str) -> NormalizedRecipe | None:
        if source_id != "77":
            return None
        return NormalizedRecipe(
            source_id="77",
            title="Preview Chili",
            ingredients=[
                NormalizedIngredient(
                    name="ground beef", quantity=1.0, unit="lb", category="meat",
                    original_text="1 lb ground beef, 85/15",
                )
            ],
            steps=["Brown.", "Simmer."],
            image="https://img.example.com/x.jpg",
            servings=4,
            ready_in_minutes=45,
            source_url="https://example.com/chili",
        )


@pytest.fixture
def preview_source(monkeypatch):
    monkeypatch.setattr(settings, "spoonacular_api_key", "test-key")
    monkeypatch.setattr(
        "app.services.recipe_discovery_service._build_source",
        lambda client: PreviewFakeSource(),
    )


async def test_discover_preview(auth_client, preview_source):
    resp = await auth_client.get("/recipes/discover/77")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["title"] == "Preview Chili"
    assert body["image"] == "https://img.example.com/x.jpg"
    assert body["source_url"] == "https://example.com/chili"
    assert body["ingredients"][0]["note"] == "1 lb ground beef, 85/15"
    assert body["steps"] == ["Brown.", "Simmer."]

    # Nothing was saved.
    listed = (await auth_client.get("/recipes")).json()
    assert not any(r["name"] == "Preview Chili" for r in listed)


async def test_discover_preview_unknown_404(auth_client, preview_source):
    resp = await auth_client.get("/recipes/discover/999")
    assert resp.status_code == 404
