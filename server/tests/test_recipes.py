import uuid

FULL_RECIPE = {
    "name": "Chicken Parm",
    "description": "Weeknight staple.",
    "servings": 4,
    "prep_minutes": 15,
    "cook_minutes": 30,
    "steps": [
        "Pound the chicken flat.",
        "Bread and fry until golden.",
        "Top with sauce and cheese; bake.",
    ],
    "ingredients": [
        {"name": "Chicken breast", "quantity": 2, "unit": "LB", "category": "meat"},
        {"name": "Marinara sauce", "quantity": 24, "unit": "oz", "category": "pantry"},
        {"name": "Mozzarella", "quantity": 8, "unit": "oz", "category": "dairy"},
        {"name": "Salt", "note": "to taste"},
    ],
}


async def test_create_and_read_recipe(auth_client):
    resp = await auth_client.post("/recipes", json=FULL_RECIPE)
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["name"] == "Chicken Parm"
    assert body["servings"] == 4
    assert body["source"] == "manual"
    assert [s["order"] for s in body["steps"]] == [0, 1, 2]
    assert len(body["ingredients"]) == 4
    # Units are normalized lowercase for merge math.
    assert body["ingredients"][0]["unit"] == "lb"
    # Quantity-less ingredients are allowed ("salt, to taste").
    assert body["ingredients"][3]["quantity"] is None

    resp = await auth_client.get(f"/recipes/{body['id']}")
    assert resp.status_code == 200
    assert resp.json()["name"] == "Chicken Parm"


async def test_list_returns_summaries(auth_client):
    await auth_client.post("/recipes", json=FULL_RECIPE)
    resp = await auth_client.get("/recipes")
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) >= 1
    entry = next(r for r in body if r["name"] == "Chicken Parm")
    assert entry["ingredient_count"] == 4
    assert entry["step_count"] == 3
    assert "steps" not in entry


async def test_update_replaces_children(auth_client):
    created = (await auth_client.post("/recipes", json=FULL_RECIPE)).json()

    resp = await auth_client.patch(
        f"/recipes/{created['id']}",
        json={
            "name": "Chicken Parmesan",
            "steps": ["One pan, one step."],
            "ingredients": [{"name": "Chicken thighs", "quantity": 1.5, "unit": "lb"}],
        },
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["name"] == "Chicken Parmesan"
    assert len(body["steps"]) == 1
    assert len(body["ingredients"]) == 1
    assert body["servings"] == 4  # untouched fields survive a partial update


async def test_update_without_children_keeps_them(auth_client):
    created = (await auth_client.post("/recipes", json=FULL_RECIPE)).json()

    resp = await auth_client.patch(f"/recipes/{created['id']}", json={"servings": 6})
    body = resp.json()
    assert body["servings"] == 6
    assert len(body["steps"]) == 3
    assert len(body["ingredients"]) == 4


async def test_delete_recipe(auth_client):
    created = (await auth_client.post("/recipes", json=FULL_RECIPE)).json()

    resp = await auth_client.delete(f"/recipes/{created['id']}")
    assert resp.status_code == 204
    resp = await auth_client.get(f"/recipes/{created['id']}")
    assert resp.status_code == 404


async def test_cross_user_isolation(auth_client, client):
    created = (await auth_client.post("/recipes", json=FULL_RECIPE)).json()

    # A second, separate user must not see or mutate the first user's recipe.
    uid = uuid.uuid4().hex[:8]
    other = await client.post(
        "/auth/register",
        json={"name": "Other", "email": f"other_{uid}@cookbook.com", "password": "Testpass123!"},
    )
    other_token = other.json()["access_token"]
    headers = {"Authorization": f"Bearer {other_token}"}

    assert (await client.get(f"/recipes/{created['id']}", headers=headers)).status_code == 404
    assert (
        await client.patch(f"/recipes/{created['id']}", json={"name": "Mine"}, headers=headers)
    ).status_code == 404
    assert (await client.delete(f"/recipes/{created['id']}", headers=headers)).status_code == 404


async def test_validation_rejects_bad_input(auth_client):
    # Blank name
    resp = await auth_client.post("/recipes", json={"name": "   "})
    assert resp.status_code == 422
    # Bad category
    resp = await auth_client.post(
        "/recipes",
        json={"name": "X", "ingredients": [{"name": "Y", "category": "aisle9"}]},
    )
    assert resp.status_code == 422
    # Zero quantity
    resp = await auth_client.post(
        "/recipes",
        json={"name": "X", "ingredients": [{"name": "Y", "quantity": 0}]},
    )
    assert resp.status_code == 422
    # Blank step
    resp = await auth_client.post("/recipes", json={"name": "X", "steps": ["ok", "  "]})
    assert resp.status_code == 422
    # Servings out of bounds
    resp = await auth_client.post("/recipes", json={"name": "X", "servings": 0})
    assert resp.status_code == 422


async def test_recipes_require_auth(client):
    assert (await client.get("/recipes")).status_code == 401
    assert (await client.post("/recipes", json={"name": "X"})).status_code == 401


SECTIONED_RECIPE = {
    "name": "Steak Fajitas",
    "servings": 6,
    "ingredients": [
        {"name": "Lime juice", "quantity": 0.33, "unit": "cup", "section": "Steak Marinade"},
        {"name": "Ground cumin", "quantity": 2, "unit": "tsp", "section": "Steak Marinade"},
        {"name": "Skirt steak", "quantity": 1.5, "unit": "lb", "section": "Fajitas"},
        {"name": "White onions", "quantity": 2, "section": "Fajitas"},
    ],
}


async def test_ingredient_sections_round_trip(auth_client):
    """A recipe keeps the groupings it was written with — that's what makes step 1 ("combine
    the ingredients for the marinade") mean something."""
    created = (await auth_client.post("/recipes", json=SECTIONED_RECIPE)).json()
    sections = [i["section"] for i in created["ingredients"]]
    assert sections == ["Steak Marinade", "Steak Marinade", "Fajitas", "Fajitas"]
    # Order is the recipe's own; sections are contiguous runs in it, never a sort key.
    assert [i["order"] for i in created["ingredients"]] == [0, 1, 2, 3]

    fetched = (await auth_client.get(f"/recipes/{created['id']}")).json()
    assert [i["section"] for i in fetched["ingredients"]] == sections


async def test_ingredient_section_is_optional_and_normalized(auth_client):
    resp = await auth_client.post(
        "/recipes",
        json={
            "name": "Mixed",
            "ingredients": [
                {"name": "Flour"},  # omitted entirely
                {"name": "Sugar", "section": ""},  # blank clears
                {"name": "Butter", "section": "  For the crust:  "},  # trimmed, colon dropped
                {"name": "Salt", "section": "x" * 200},  # capped, not rejected
            ],
        },
    )
    assert resp.status_code == 201, resp.text
    ingredients = resp.json()["ingredients"]
    assert ingredients[0]["section"] is None
    assert ingredients[1]["section"] is None
    assert ingredients[2]["section"] == "For the crust"
    assert len(ingredients[3]["section"]) == 80


async def test_update_can_clear_sections(auth_client):
    created = (await auth_client.post("/recipes", json=SECTIONED_RECIPE)).json()
    resp = await auth_client.patch(
        f"/recipes/{created['id']}",
        json={"ingredients": [{"name": "Skirt steak", "quantity": 2, "unit": "lb"}]},
    )
    assert resp.status_code == 200, resp.text
    assert resp.json()["ingredients"][0]["section"] is None
