"""URL import (v0.2): the JSON-LD parser tables + the /recipes/import-url endpoint."""

import pytest

from app.recipes_ext.jsonld import (
    _minutes,
    find_recipe_node,
    normalize_jsonld,
    parse_ingredient_line,
)

# ── Ingredient-line parsing ──────────────────────────────────────────────────


@pytest.mark.parametrize(
    ("line", "quantity", "unit", "name"),
    [
        ("2 cups all-purpose flour", 2.0, "cup", "all-purpose flour"),
        ("1 1/2 pounds ground beef", 1.5, "lb", "ground beef"),
        ("1½ tsp vanilla extract", 1.5, "tsp", "vanilla extract"),
        ("½ cup sugar", 0.5, "cup", "sugar"),
        ("3 eggs", 3.0, None, "eggs"),
        ("2.5 kg potatoes", 2.5, "kg", "potatoes"),
        ("1 (15 oz) can black beans", 1.0, None, "(15 oz) can black beans"),
        ("Salt to taste", None, None, "Salt to taste"),
        ("1 pinch of saffron", 1.0, "pinch", "saffron"),
        ("2 Tbsp. olive oil", 2.0, "tbsp", "olive oil"),
        ("4 cloves garlic, minced", 4.0, "clove", "garlic, minced"),
        # Ranges keep the lower bound; the tail must not leak into the name.
        ("2-3 lbs chicken thighs", 2.0, "lb", "chicken thighs"),
        ("2 to 3 cups broth", 2.0, "cup", "broth"),
        ("1 - 1 1/2 pounds shrimp", 1.0, "lb", "shrimp"),
    ],
)
def test_parse_ingredient_line(line, quantity, unit, name):
    parsed = parse_ingredient_line(line)
    assert parsed.quantity == pytest.approx(quantity) if quantity else parsed.quantity is None
    assert parsed.unit == unit
    assert parsed.name == name


def test_parse_ingredient_line_guesses_category():
    assert parse_ingredient_line("2 cups shredded cheddar cheese").category == "dairy"
    assert parse_ingredient_line("1 lb chicken thighs").category == "meat"
    assert parse_ingredient_line("3 roma tomatoes").category == "produce"
    assert parse_ingredient_line("1 mystery widget").category is None


# ── Duration + node location ─────────────────────────────────────────────────


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("PT30M", 30),
        ("PT1H30M", 90),
        ("PT2H", 120),
        ("P0DT1H15M", 75),
        ("PT45S", 1),
        ("not-a-duration", None),
        (None, None),
    ],
)
def test_minutes(value, expected):
    assert _minutes(value) == expected


JSONLD_DOC = {
    "@context": "https://schema.org",
    "@graph": [
        {"@type": "WebPage", "name": "Some Blog"},
        {
            "@type": ["Recipe", "NewsArticle"],
            "name": "Weeknight Chili",
            "image": [{"@type": "ImageObject", "url": "https://img.example.com/chili.jpg"}],
            "recipeYield": ["6", "6 servings"],
            "totalTime": "PT45M",
            "description": "A <b>cozy</b> chili.",
            "recipeIngredient": [
                "1 1/2 pounds ground beef",
                "2 cans kidney beans",
                "Salt to taste",
            ],
            "recipeInstructions": [
                {
                    "@type": "HowToSection",
                    "name": "Cook",
                    "itemListElement": [
                        {"@type": "HowToStep", "text": "Brown the beef."},
                        {"@type": "HowToStep", "text": "Simmer 30 minutes."},
                    ],
                }
            ],
        },
    ],
}


def test_find_and_normalize_jsonld():
    node = find_recipe_node(JSONLD_DOC)
    assert node is not None
    recipe = normalize_jsonld(node, "https://blog.example.com/chili")
    assert recipe.title == "Weeknight Chili"
    assert recipe.image == "https://img.example.com/chili.jpg"
    assert recipe.servings == 6
    assert recipe.ready_in_minutes == 45
    assert recipe.summary == "A cozy chili."
    assert recipe.steps == ["Brown the beef.", "Simmer 30 minutes."]
    beef = recipe.ingredients[0]
    assert (beef.quantity, beef.unit, beef.category) == (1.5, "lb", "meat")
    assert recipe.source_url == "https://blog.example.com/chili"


def test_normalize_jsonld_rejects_empty():
    assert normalize_jsonld({"@type": "Recipe", "name": "X"}, "u") is None  # no ingredients
    assert find_recipe_node({"@type": "WebSite"}) is None


# ── Endpoint ─────────────────────────────────────────────────────────────────


async def test_import_url_endpoint(auth_client, monkeypatch):
    from app.recipes_ext.base import NormalizedIngredient, NormalizedRecipe

    async def fake_fetch(url, client):
        return NormalizedRecipe(
            source_id=url,
            title="Blog Chili",
            ingredients=[
                NormalizedIngredient(name="ground beef", quantity=1.5, unit="lb", category="meat")
            ],
            steps=["Cook it."],
            servings=4,
            source_url=url,
        )

    monkeypatch.setattr("app.services.recipe_discovery_service.fetch_recipe_from_url", fake_fetch)

    resp = await auth_client.post(
        "/recipes/import-url", json={"url": "https://blog.example.com/chili"}
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["name"] == "Blog Chili"
    assert body["source"] == "imported"
    assert body["description"] == "https://blog.example.com/chili"
    assert body["ingredients"][0]["category"] == "meat"


async def test_import_url_is_idempotent(auth_client, monkeypatch):
    """Re-importing the same URL returns the existing recipe, not a duplicate row — the fix for
    the same recipe appearing twice in the list after two imports (or a double-tap)."""
    from app.recipes_ext.base import NormalizedIngredient, NormalizedRecipe

    async def fake_fetch(url, client):
        return NormalizedRecipe(
            source_id=url,
            title="Blog Chili",
            ingredients=[
                NormalizedIngredient(name="ground beef", quantity=1.5, unit="lb", category="meat")
            ],
            steps=["Cook it."],
            servings=4,
            source_url=url,
        )

    monkeypatch.setattr("app.services.recipe_discovery_service.fetch_recipe_from_url", fake_fetch)
    url = "https://blog.example.com/chili"

    first = await auth_client.post("/recipes/import-url", json={"url": url})
    second = await auth_client.post("/recipes/import-url", json={"url": url})
    assert first.status_code == 201 and second.status_code == 201, second.text
    assert first.json()["id"] == second.json()["id"]

    listing = await auth_client.get("/recipes")
    assert listing.status_code == 200
    assert sum(1 for r in listing.json() if r["name"] == "Blog Chili") == 1


async def test_import_url_rejects_unsafe_urls(auth_client):
    for url in (
        "ftp://example.com/recipe",
        "http://localhost/recipe",
        "http://127.0.0.1/recipe",
        "http://192.168.1.10/recipe",
        "not a url",
    ):
        resp = await auth_client.post("/recipes/import-url", json={"url": url})
        assert resp.status_code == 422, url


async def test_import_url_no_recipe_found(auth_client, monkeypatch):
    async def fake_fetch(url, client):
        return None

    monkeypatch.setattr("app.services.recipe_discovery_service.fetch_recipe_from_url", fake_fetch)
    # No Spoonacular key in the test env ⇒ no fallback ⇒ clean 422.
    monkeypatch.setattr("app.services.recipe_discovery_service.settings.spoonacular_api_key", None)
    resp = await auth_client.post(
        "/recipes/import-url", json={"url": "https://blog.example.com/not-a-recipe"}
    )
    assert resp.status_code == 422
