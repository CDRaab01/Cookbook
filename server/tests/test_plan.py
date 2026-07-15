"""Weekly meal planner (v0.3): entries, ranges, and the plan → shopping-list payoff."""


async def _recipe(auth_client, name: str, ingredients: list[dict]) -> str:
    resp = await auth_client.post("/recipes", json={"name": name, "ingredients": ingredients})
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


async def test_plan_crud_and_range(auth_client):
    chili = await _recipe(auth_client, "Chili", [{"name": "Beans", "quantity": 2, "unit": "can"}])

    created = await auth_client.post(
        "/plan", json={"date": "2026-07-06", "slot": "dinner", "recipe_id": chili}
    )
    assert created.status_code == 201, created.text
    assert created.json()["recipe_name"] == "Chili"

    note = await auth_client.post(
        "/plan", json={"date": "2026-07-07", "slot": "dinner", "note": "Leftovers"}
    )
    assert note.status_code == 201
    assert note.json()["note"] == "Leftovers"

    week = (
        await auth_client.get("/plan", params={"start": "2026-07-06", "end": "2026-07-12"})
    ).json()
    assert [e["slot"] for e in week] == ["dinner", "dinner"]

    resp = await auth_client.delete(f"/plan/{note.json()['id']}")
    assert resp.status_code == 204
    week = (
        await auth_client.get("/plan", params={"start": "2026-07-06", "end": "2026-07-12"})
    ).json()
    assert len(week) == 1


async def test_mark_entry_eaten(auth_client):
    chili = await _recipe(auth_client, "Chili", [{"name": "Beans", "quantity": 2, "unit": "can"}])
    created = await auth_client.post(
        "/plan", json={"date": "2026-07-06", "slot": "dinner", "recipe_id": chili}
    )
    entry_id = created.json()["id"]
    assert created.json()["eaten"] is False

    patched = await auth_client.patch(f"/plan/{entry_id}", json={"eaten": True})
    assert patched.status_code == 200, patched.text
    assert patched.json()["eaten"] is True
    # The recipe name must still resolve after the mutate+re-query (async lazy-load guard).
    assert patched.json()["recipe_name"] == "Chili"

    week = (
        await auth_client.get("/plan", params={"start": "2026-07-06", "end": "2026-07-06"})
    ).json()
    assert week[0]["eaten"] is True

    # Un-eat works too.
    unpatched = await auth_client.patch(f"/plan/{entry_id}", json={"eaten": False})
    assert unpatched.json()["eaten"] is False


async def test_entry_needs_exactly_one_of_recipe_or_note(auth_client):
    chili = await _recipe(auth_client, "Chili", [{"name": "Beans", "quantity": 2, "unit": "can"}])
    both = await auth_client.post(
        "/plan",
        json={"date": "2026-07-06", "slot": "dinner", "recipe_id": chili, "note": "??"},
    )
    assert both.status_code == 422
    neither = await auth_client.post("/plan", json={"date": "2026-07-06", "slot": "dinner"})
    assert neither.status_code == 422
    bad_slot = await auth_client.post(
        "/plan", json={"date": "2026-07-06", "slot": "brunch", "recipe_id": chili}
    )
    assert bad_slot.status_code == 422


async def test_plan_to_list_merges_the_week(auth_client):
    chili = await _recipe(
        auth_client,
        "Chili",
        [
            {"name": "Ground beef", "quantity": 1, "unit": "lb", "category": "meat"},
            {"name": "Onions", "quantity": 2, "category": "produce"},
        ],
    )
    tacos = await _recipe(
        auth_client,
        "Tacos",
        [
            {"name": "Ground beef", "quantity": 0.5, "unit": "lb", "category": "meat"},
            {"name": "Tortillas", "quantity": 8, "category": "bakery"},
        ],
    )
    for date, recipe in (("2026-07-06", chili), ("2026-07-07", tacos), ("2026-07-08", chili)):
        await auth_client.post("/plan", json={"date": date, "slot": "dinner", "recipe_id": recipe})
    # A note entry must not affect the shopping math.
    await auth_client.post(
        "/plan", json={"date": "2026-07-09", "slot": "dinner", "note": "Takeout"}
    )

    resp = await auth_client.post(
        "/plan/to-list", json={"start": "2026-07-06", "end": "2026-07-12"}
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["recipes_added"] == 3

    items = (await auth_client.get(f"/lists/{body['list_id']}")).json()["items"]
    by_name = {i["name"]: i for i in items}
    # Chili twice + Tacos once: 1 + 1 + 0.5 lb beef on one line.
    assert by_name["Ground beef"]["quantity"] == 2.5
    assert by_name["Onions"]["quantity"] == 4  # chili planned twice
    assert by_name["Tortillas"]["quantity"] == 8
    assert len(items) == 3


async def test_plan_to_list_empty_range_400(auth_client):
    resp = await auth_client.post(
        "/plan/to-list", json={"start": "2026-08-01", "end": "2026-08-07"}
    )
    assert resp.status_code == 400


async def test_range_limits(auth_client):
    resp = await auth_client.get("/plan", params={"start": "2026-07-06", "end": "2026-09-30"})
    assert resp.status_code == 422
    resp = await auth_client.get("/plan", params={"start": "2026-07-06", "end": "2026-07-01"})
    assert resp.status_code == 422


async def test_deleting_recipe_cascades_plan_entries(auth_client):
    chili = await _recipe(auth_client, "Chili", [{"name": "Beans", "quantity": 2, "unit": "can"}])
    await auth_client.post(
        "/plan", json={"date": "2026-07-06", "slot": "dinner", "recipe_id": chili}
    )
    assert (await auth_client.delete(f"/recipes/{chili}")).status_code == 204
    week = (
        await auth_client.get("/plan", params={"start": "2026-07-06", "end": "2026-07-06"})
    ).json()
    assert week == []


async def test_plan_entry_scale_scales_its_list_contribution(auth_client):
    chili = await _recipe(
        auth_client, "Chili", [{"name": "Beef", "quantity": 1, "unit": "lb", "category": "meat"}]
    )
    tacos = await _recipe(
        auth_client, "Tacos", [{"name": "Tortillas", "quantity": 8, "category": "bakery"}]
    )
    # Chili at 2x (cooking for leftovers), tacos as written.
    await auth_client.post(
        "/plan", json={"date": "2026-08-03", "slot": "dinner", "recipe_id": chili, "scale": 2}
    )
    await auth_client.post(
        "/plan", json={"date": "2026-08-04", "slot": "dinner", "recipe_id": tacos}
    )

    body = (
        await auth_client.post("/plan/to-list", json={"start": "2026-08-03", "end": "2026-08-09"})
    ).json()
    items = {i["name"]: i for i in (await auth_client.get(f"/lists/{body['list_id']}")).json()["items"]}
    assert items["Beef"]["quantity"] == 2.0       # chili 1 lb x2
    assert items["Tortillas"]["quantity"] == 8.0  # tacos unscaled


async def test_plan_entry_scale_composes_with_global_scale(auth_client):
    chili = await _recipe(
        auth_client, "Chili", [{"name": "Beef", "quantity": 1, "unit": "lb", "category": "meat"}]
    )
    await auth_client.post(
        "/plan", json={"date": "2026-08-10", "slot": "dinner", "recipe_id": chili, "scale": 2}
    )
    body = (
        await auth_client.post(
            "/plan/to-list", json={"start": "2026-08-10", "end": "2026-08-16", "scale": 1.5}
        )
    ).json()
    items = {i["name"]: i for i in (await auth_client.get(f"/lists/{body['list_id']}")).json()["items"]}
    assert items["Beef"]["quantity"] == 3.0  # 1 lb x entry 2 x global 1.5


async def test_plan_entry_out_carries_scale(auth_client):
    chili = await _recipe(auth_client, "Chili", [{"name": "Beef", "quantity": 1, "unit": "lb"}])
    created = await auth_client.post(
        "/plan", json={"date": "2026-08-17", "slot": "dinner", "recipe_id": chili, "scale": 2}
    )
    assert created.json()["scale"] == 2.0
    listed = (
        await auth_client.get("/plan", params={"start": "2026-08-17", "end": "2026-08-17"})
    ).json()
    assert listed[0]["scale"] == 2.0
