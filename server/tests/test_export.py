"""GET /export — full per-user data export (ROADMAP T3 #6)."""


async def test_export_empty_user(auth_client):
    r = await auth_client.get("/export")
    assert r.status_code == 200, r.text
    assert "attachment" in r.headers.get("content-disposition", "")
    assert r.headers["content-disposition"].endswith('.json"')

    data = r.json()
    assert data["app"] == "cookbook"
    assert data["schema_version"] >= 1
    assert data["exported_at"]
    # User is present but auth secrets are redacted.
    assert data["user"]["email"].endswith("@cookbook.com")
    assert "hashed_password" not in data["user"]
    assert "reset_token" not in data["user"]
    # Every collection is present as a (here empty) list.
    for key in (
        "recipes",
        "recipe_steps",
        "recipe_ingredients",
        "shopping_lists",
        "shopping_list_items",
        "meal_plan_entries",
        "cook_events",
        "item_history",
        "pantry_items",
        "pantry_staples",
    ):
        assert data[key] == [], key


async def test_export_includes_user_data(auth_client):
    create = await auth_client.post(
        "/recipes",
        json={
            "name": "Export Test Stew",
            "servings": 4,
            "steps": ["Chop", "Simmer"],
            "ingredients": [
                {"name": "Carrot", "quantity": 2, "unit": "cup"},
                {"name": "Onion", "quantity": 1},
            ],
        },
    )
    assert create.status_code in (200, 201), create.text

    data = (await auth_client.get("/export")).json()
    assert len(data["recipes"]) == 1
    assert data["recipes"][0]["name"] == "Export Test Stew"
    assert {i["name"] for i in data["recipe_ingredients"]} == {"Carrot", "Onion"}
    assert {s["text"] for s in data["recipe_steps"]} == {"Chop", "Simmer"}
    # UUIDs and timestamps serialize as strings (JSON-safe).
    assert isinstance(data["recipes"][0]["id"], str)
    assert isinstance(data["recipes"][0]["created_at"], str)


async def test_export_requires_auth(client):
    assert (await client.get("/export")).status_code == 401
