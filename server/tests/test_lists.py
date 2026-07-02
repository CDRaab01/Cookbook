CHILI = {
    "name": "Chili",
    "servings": 4,
    "ingredients": [
        {"name": "Ground beef", "quantity": 1, "unit": "lb", "category": "meat"},
        {"name": "Onions", "quantity": 2, "category": "produce"},
        {"name": "Beans", "quantity": 2, "unit": "can", "category": "pantry"},
    ],
}

TACOS = {
    "name": "Tacos",
    "servings": 2,
    "ingredients": [
        {"name": "Ground beef", "quantity": 0.5, "unit": "lb", "category": "meat"},
        {"name": "Tortillas", "quantity": 8, "category": "bakery"},
        {"name": "Onion", "quantity": 1, "category": "produce"},
    ],
}


async def _default_list(client):
    resp = await client.get("/lists/default")
    assert resp.status_code == 200, resp.text
    return resp.json()


async def test_default_list_created_on_first_touch(auth_client):
    body = await _default_list(auth_client)
    assert body["name"] == "Groceries"
    assert body["items"] == []
    # Idempotent: a second GET returns the same list.
    again = await _default_list(auth_client)
    assert again["id"] == body["id"]


async def test_manual_add_and_merge(auth_client):
    lst = await _default_list(auth_client)

    resp = await auth_client.post(
        f"/lists/{lst['id']}/items", json={"name": "Paper towels", "quantity": 1}
    )
    assert resp.status_code == 201
    resp = await auth_client.post(
        f"/lists/{lst['id']}/items", json={"name": "paper towels", "quantity": 2}
    )
    items = resp.json()["items"]
    assert len(items) == 1
    assert items[0]["quantity"] == 3


async def test_add_recipe_autofills_list(auth_client):
    recipe = (await auth_client.post("/recipes", json=CHILI)).json()
    lst = await _default_list(auth_client)

    resp = await auth_client.post(
        f"/lists/{lst['id']}/add-recipe", json={"recipe_id": recipe["id"]}
    )
    assert resp.status_code == 200, resp.text
    items = resp.json()["items"]
    assert len(items) == 3
    by_name = {i["name"]: i for i in items}
    assert by_name["Ground beef"]["quantity"] == 1
    assert by_name["Ground beef"]["recipe_id"] == recipe["id"]
    assert by_name["Onions"]["category"] == "produce"


async def test_add_recipe_scales_quantities(auth_client):
    recipe = (await auth_client.post("/recipes", json=CHILI)).json()
    lst = await _default_list(auth_client)

    resp = await auth_client.post(
        f"/lists/{lst['id']}/add-recipe", json={"recipe_id": recipe["id"], "scale": 2.0}
    )
    by_name = {i["name"]: i for i in resp.json()["items"]}
    assert by_name["Ground beef"]["quantity"] == 2
    assert by_name["Beans"]["quantity"] == 4


async def test_two_recipes_merge_shared_ingredients(auth_client):
    chili = (await auth_client.post("/recipes", json=CHILI)).json()
    tacos = (await auth_client.post("/recipes", json=TACOS)).json()
    lst = await _default_list(auth_client)

    await auth_client.post(f"/lists/{lst['id']}/add-recipe", json={"recipe_id": chili["id"]})
    resp = await auth_client.post(
        f"/lists/{lst['id']}/add-recipe", json={"recipe_id": tacos["id"]}
    )
    items = resp.json()["items"]
    by_name = {i["name"]: i for i in items}

    # Ground beef 1lb + 0.5lb merged into one line.
    assert by_name["Ground beef"]["quantity"] == 1.5
    # Merged across recipes ⇒ provenance collapses to NULL rather than lying.
    assert by_name["Ground beef"]["recipe_id"] is None
    # "Onions" + "Onion" fold via singularize-lite (both unitless).
    assert by_name["Onions"]["quantity"] == 3
    # Tortillas + Beans stay separate lines.
    assert len(items) == 4


async def test_re_add_same_recipe_conflicts_unless_forced(auth_client):
    recipe = (await auth_client.post("/recipes", json=CHILI)).json()
    lst = await _default_list(auth_client)

    await auth_client.post(f"/lists/{lst['id']}/add-recipe", json={"recipe_id": recipe["id"]})
    resp = await auth_client.post(
        f"/lists/{lst['id']}/add-recipe", json={"recipe_id": recipe["id"]}
    )
    assert resp.status_code == 409

    resp = await auth_client.post(
        f"/lists/{lst['id']}/add-recipe", json={"recipe_id": recipe["id"], "force": True}
    )
    assert resp.status_code == 200
    by_name = {i["name"]: i for i in resp.json()["items"]}
    assert by_name["Ground beef"]["quantity"] == 2  # doubled up


async def test_check_off_and_clear(auth_client):
    recipe = (await auth_client.post("/recipes", json=CHILI)).json()
    lst = await _default_list(auth_client)
    items = (
        await auth_client.post(f"/lists/{lst['id']}/add-recipe", json={"recipe_id": recipe["id"]})
    ).json()["items"]

    beef = next(i for i in items if i["name"] == "Ground beef")
    resp = await auth_client.patch(
        f"/lists/{lst['id']}/items/{beef['id']}", json={"checked": True}
    )
    updated = next(i for i in resp.json()["items"] if i["id"] == beef["id"])
    assert updated["checked"] is True
    assert updated["checked_at"] is not None

    resp = await auth_client.post(f"/lists/{lst['id']}/clear-checked")
    names = [i["name"] for i in resp.json()["items"]]
    assert "Ground beef" not in names
    assert len(names) == 2


async def test_checked_items_do_not_absorb_new_adds(auth_client):
    lst = await _default_list(auth_client)
    items = (
        await auth_client.post(f"/lists/{lst['id']}/items", json={"name": "Milk", "quantity": 1})
    ).json()["items"]
    milk = items[0]
    await auth_client.patch(f"/lists/{lst['id']}/items/{milk['id']}", json={"checked": True})

    # Adding milk again creates a fresh unchecked line instead of merging into history.
    resp = await auth_client.post(
        f"/lists/{lst['id']}/items", json={"name": "Milk", "quantity": 2}
    )
    items = resp.json()["items"]
    assert len(items) == 2
    unchecked = next(i for i in items if not i["checked"])
    assert unchecked["quantity"] == 2


async def test_uncheck_clears_checked_at(auth_client):
    lst = await _default_list(auth_client)
    items = (
        await auth_client.post(f"/lists/{lst['id']}/items", json={"name": "Bread"})
    ).json()["items"]
    item = items[0]
    await auth_client.patch(f"/lists/{lst['id']}/items/{item['id']}", json={"checked": True})
    resp = await auth_client.patch(
        f"/lists/{lst['id']}/items/{item['id']}", json={"checked": False}
    )
    updated = resp.json()["items"][0]
    assert updated["checked"] is False
    assert updated["checked_at"] is None


async def test_delete_item(auth_client):
    lst = await _default_list(auth_client)
    items = (
        await auth_client.post(f"/lists/{lst['id']}/items", json={"name": "Bread"})
    ).json()["items"]
    resp = await auth_client.delete(f"/lists/{lst['id']}/items/{items[0]['id']}")
    assert resp.status_code == 200
    assert resp.json()["items"] == []


async def test_deleting_recipe_keeps_list_items(auth_client):
    recipe = (await auth_client.post("/recipes", json=CHILI)).json()
    lst = await _default_list(auth_client)
    await auth_client.post(f"/lists/{lst['id']}/add-recipe", json={"recipe_id": recipe["id"]})

    resp = await auth_client.delete(f"/recipes/{recipe['id']}")
    assert resp.status_code == 204

    body = await _default_list(auth_client)
    assert len(body["items"]) == 3  # groceries survive; provenance FK went NULL
    assert all(i["recipe_id"] is None for i in body["items"])


async def test_same_item_different_units_is_one_line_with_measures(auth_client):
    """The v0.2.1 buyable-list rule: you buy one 'oil' no matter how recipes measured it."""
    recipe = (
        await auth_client.post(
            "/recipes",
            json={
                "name": "Bread",
                "ingredients": [
                    {"name": "Oil", "quantity": 2, "unit": "tablespoons"},
                    {"name": "oil", "quantity": 2, "unit": "tsp"},
                    {"name": "Bread flour", "quantity": 4, "unit": "Cups"},
                ],
            },
        )
    ).json()
    # Units are canonicalized at the recipe layer already.
    assert {i["unit"] for i in recipe["ingredients"]} == {"tbsp", "tsp", "cup"}

    lst = await _default_list(auth_client)
    items = (
        await auth_client.post(f"/lists/{lst['id']}/add-recipe", json={"recipe_id": recipe["id"]})
    ).json()["items"]

    assert len(items) == 2  # oil collapsed to one line
    oil = next(i for i in items if i["name"] == "Oil")
    assert oil["measures"] == [
        {"quantity": 2.0, "unit": "tbsp"},
        {"quantity": 2.0, "unit": "tsp"},
    ]
    # Mixed measures ⇒ the legacy single-measure columns go null rather than lying.
    assert oil["quantity"] is None and oil["unit"] is None

    flour = next(i for i in items if i["name"] == "Bread flour")
    assert flour["quantity"] == 4 and flour["unit"] == "cup"
    assert flour["measures"] == [{"quantity": 4.0, "unit": "cup"}]


async def test_water_never_reaches_the_list(auth_client):
    recipe = (
        await auth_client.post(
            "/recipes",
            json={
                "name": "Dough",
                "ingredients": [
                    {"name": "Warm water", "quantity": 1.5, "unit": "cups"},
                    {"name": "Yeast", "quantity": 3, "unit": "teaspoons"},
                ],
            },
        )
    ).json()
    lst = await _default_list(auth_client)
    items = (
        await auth_client.post(f"/lists/{lst['id']}/add-recipe", json={"recipe_id": recipe["id"]})
    ).json()["items"]
    assert [i["name"] for i in items] == ["Yeast"]
    assert items[0]["unit"] == "tsp"


async def test_all_water_recipe_400s(auth_client):
    recipe = (
        await auth_client.post(
            "/recipes",
            json={"name": "Hydration", "ingredients": [{"name": "Water", "quantity": 8, "unit": "cups"}]},
        )
    ).json()
    lst = await _default_list(auth_client)
    resp = await auth_client.post(
        f"/lists/{lst['id']}/add-recipe", json={"recipe_id": recipe["id"]}
    )
    assert resp.status_code == 400


async def test_unquantified_add_keeps_known_amounts(auth_client):
    lst = await _default_list(auth_client)
    await auth_client.post(
        f"/lists/{lst['id']}/items", json={"name": "Oil", "quantity": 2, "unit": "tbsp"}
    )
    # Bare re-add must not erase the 2 tbsp (the old combine rule nulled it).
    resp = await auth_client.post(f"/lists/{lst['id']}/items", json={"name": "oil"})
    item = resp.json()["items"][0]
    assert item["quantity"] == 2 and item["unit"] == "tbsp"


async def test_edit_replaces_the_aggregate(auth_client):
    lst = await _default_list(auth_client)
    await auth_client.post(
        f"/lists/{lst['id']}/items", json={"name": "Oil", "quantity": 2, "unit": "tbsp"}
    )
    items = (
        await auth_client.post(
            f"/lists/{lst['id']}/items", json={"name": "oil", "quantity": 2, "unit": "tsp"}
        )
    ).json()["items"]
    oil = items[0]
    assert len(oil["measures"]) == 2

    # An explicit edit is an override: one bottle, done.
    resp = await auth_client.patch(
        f"/lists/{lst['id']}/items/{oil['id']}", json={"quantity": 1, "unit": "bottle"}
    )
    edited = resp.json()["items"][0]
    assert edited["measures"] == [{"quantity": 1.0, "unit": "bottle"}]
    assert edited["quantity"] == 1 and edited["unit"] == "bottle"


async def test_cross_user_list_isolation(auth_client, client):
    import uuid as _uuid

    lst = await _default_list(auth_client)

    uid = _uuid.uuid4().hex[:8]
    other = await client.post(
        "/auth/register",
        json={"name": "Other", "email": f"other_{uid}@cookbook.com", "password": "Testpass123!"},
    )
    headers = {"Authorization": f"Bearer {other.json()['access_token']}"}
    resp = await client.post(
        f"/lists/{lst['id']}/items", json={"name": "Sneaky"}, headers=headers
    )
    assert resp.status_code == 404
