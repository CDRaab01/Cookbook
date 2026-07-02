import pytest

from app.config import settings
from app.recipes_ext.base import NormalizedIngredient, NormalizedRecipe, RecipeSource, RecipeSummary
from app.recipes_ext.spoonacular import map_aisle, normalize_information

# ── Pure normalization ───────────────────────────────────────────────────────


@pytest.mark.parametrize(
    ("aisle", "expected"),
    [
        ("Produce", "produce"),
        ("Meat", "meat"),
        ("Seafood", "meat"),
        ("Milk, Eggs, Other Dairy", "dairy"),
        ("Cheese", "dairy"),
        ("Bakery/Bread", "bakery"),
        ("Frozen", "frozen"),
        ("Spices and Seasonings", "pantry"),
        ("Canned and Jarred", "pantry"),
        ("Pasta and Rice", "pantry"),
        ("Oil, Vinegar, Salad Dressing", "pantry"),
        ("Alcoholic Beverages", "pantry"),
        ("", None),
        (None, None),
        ("Gourmet", None),
    ],
)
def test_map_aisle(aisle, expected):
    assert map_aisle(aisle) == expected


SPOONACULAR_PAYLOAD = {
    "id": 715538,
    "title": "Bruschetta Style Pork & Pasta",
    "image": "https://img.spoonacular.com/recipes/715538-556x370.jpg",
    "servings": 5,
    "readyInMinutes": 35,
    "sourceUrl": "https://example.com/recipe",
    "summary": "A <b>tasty</b> dinner.",
    "extendedIngredients": [
        {
            "name": "pork tenderloin",
            "amount": 1.5,
            "unit": "lb",
            "aisle": "Meat",
            "original": "1.5 lb pork tenderloin, trimmed",
        },
        {"name": "penne pasta", "amount": 8, "unit": "oz", "aisle": "Pasta and Rice"},
        {
            "name": "basil",
            "amount": 0,
            "unit": "",
            "aisle": "Produce",
            "original": "basil to taste",
        },
    ],
    "analyzedInstructions": [
        {
            "steps": [
                {"number": 1, "step": "Season the pork."},
                {"number": 2, "step": "Sear, then roast 20 minutes."},
            ]
        }
    ],
}


def test_normalize_information_maps_ingredients_and_steps():
    recipe = normalize_information(SPOONACULAR_PAYLOAD)
    assert recipe.source_id == "715538"
    assert recipe.title == "Bruschetta Style Pork & Pasta"
    assert recipe.servings == 5
    assert recipe.ready_in_minutes == 35
    assert recipe.summary == "A tasty dinner."  # tags stripped

    pork, penne, basil = recipe.ingredients
    assert (pork.quantity, pork.unit, pork.category) == (1.5, "lb", "meat")
    assert pork.original_text == "1.5 lb pork tenderloin, trimmed"
    assert penne.category == "pantry"
    # amount 0 ⇒ unquantified ("to taste")
    assert basil.quantity is None

    assert recipe.steps == ["Season the pork.", "Sear, then roast 20 minutes."]


def test_normalize_information_falls_back_to_flat_instructions():
    payload = dict(SPOONACULAR_PAYLOAD, analyzedInstructions=[])
    payload["instructions"] = "<p>Cook it all together.</p>"
    recipe = normalize_information(payload)
    assert recipe.steps == ["Cook it all together."]


# ── Endpoints, with the provider faked at the build seam ─────────────────────


class FakeSource(RecipeSource):
    source_tag = "fake"

    async def discover(self, query: str, *, limit: int) -> list[RecipeSummary]:
        return [
            RecipeSummary(
                source_id="42",
                title=f"{query.title()} Supreme",
                ready_in_minutes=25,
                servings=4,
            )
        ]

    async def fetch(self, source_id: str) -> NormalizedRecipe | None:
        if source_id != "42":
            return None
        return NormalizedRecipe(
            source_id="42",
            title="Chili Supreme",
            ingredients=[
                NormalizedIngredient(name="ground beef", quantity=1.0, unit="lb", category="meat"),
                NormalizedIngredient(name="beans", quantity=2.0, unit="can", category="pantry"),
            ],
            steps=["Brown the beef.", "Simmer with beans."],
            servings=4,
            ready_in_minutes=45,
            source_url="https://example.com/chili",
        )


@pytest.fixture
def fake_spoonacular(monkeypatch):
    monkeypatch.setattr(settings, "spoonacular_api_key", "test-key")
    monkeypatch.setattr(
        "app.services.recipe_discovery_service._build_source",
        lambda client: FakeSource(),
    )


async def test_discover_disabled_without_key(auth_client, monkeypatch):
    monkeypatch.setattr(settings, "spoonacular_api_key", None)
    resp = await auth_client.get("/recipes/discover", params={"q": "chili"})
    assert resp.status_code == 503


async def test_discover_returns_hits(auth_client, fake_spoonacular):
    resp = await auth_client.get("/recipes/discover", params={"q": "chili"})
    assert resp.status_code == 200, resp.text
    hits = resp.json()
    assert hits == [
        {
            "source_id": "42",
            "title": "Chili Supreme",
            "image": None,
            "ready_in_minutes": 25,
            "servings": 4,
        }
    ]


async def test_import_creates_editable_recipe(auth_client, fake_spoonacular):
    resp = await auth_client.post("/recipes/import", json={"source_id": "42"})
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["source"] == "imported"
    assert body["name"] == "Chili Supreme"
    assert body["servings"] == 4
    assert [i["name"] for i in body["ingredients"]] == ["ground beef", "beans"]
    assert body["ingredients"][0]["category"] == "meat"
    assert [s["text"] for s in body["steps"]] == ["Brown the beef.", "Simmer with beans."]

    # It's a plain recipe: shows in the list and can hit the shopping list.
    listed = (await auth_client.get("/recipes")).json()
    entry = next(r for r in listed if r["id"] == body["id"])
    assert entry["source"] == "imported"

    lst = (await auth_client.get("/lists/default")).json()
    add = await auth_client.post(f"/lists/{lst['id']}/add-recipe", json={"recipe_id": body["id"]})
    assert add.status_code == 200
    assert len(add.json()["items"]) == 2


async def test_import_unknown_id_404(auth_client, fake_spoonacular):
    resp = await auth_client.post("/recipes/import", json={"source_id": "999"})
    assert resp.status_code == 404
