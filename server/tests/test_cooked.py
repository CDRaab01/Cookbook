"""Made-it tracking: POST /recipes/{id}/cooked + undo + aggregates on the outs."""


async def _recipe(auth_client) -> str:
    resp = await auth_client.post(
        "/recipes",
        json={"name": "Chili", "ingredients": [{"name": "Beans", "quantity": 2, "unit": "can"}]},
    )
    return resp.json()["id"]


async def test_mark_cooked_and_aggregates(auth_client):
    recipe_id = await _recipe(auth_client)

    resp = await auth_client.post(f"/recipes/{recipe_id}/cooked")
    assert resp.status_code == 201, resp.text
    assert resp.json()["times_cooked"] == 1
    assert resp.json()["last_cooked_at"] is not None

    resp = await auth_client.post(f"/recipes/{recipe_id}/cooked")
    assert resp.json()["times_cooked"] == 2

    detail = (await auth_client.get(f"/recipes/{recipe_id}")).json()
    assert detail["times_cooked"] == 2
    assert detail["last_cooked_at"] is not None

    listed = (await auth_client.get("/recipes")).json()
    entry = next(r for r in listed if r["id"] == recipe_id)
    assert entry["times_cooked"] == 2


async def test_undo_removes_latest(auth_client):
    recipe_id = await _recipe(auth_client)
    await auth_client.post(f"/recipes/{recipe_id}/cooked")

    resp = await auth_client.delete(f"/recipes/{recipe_id}/cooked/last")
    assert resp.status_code == 200
    assert resp.json()["times_cooked"] == 0
    assert resp.json()["last_cooked_at"] is None

    # Nothing left to undo.
    resp = await auth_client.delete(f"/recipes/{recipe_id}/cooked/last")
    assert resp.status_code == 404


async def test_rating_is_averaged_across_cooks(auth_client):
    recipe_id = await _recipe(auth_client)

    resp = await auth_client.post(f"/recipes/{recipe_id}/cooked", json={"rating": 5})
    assert resp.status_code == 201, resp.text
    assert resp.json()["avg_rating"] == 5.0

    resp = await auth_client.post(f"/recipes/{recipe_id}/cooked", json={"rating": 4})
    assert resp.json()["avg_rating"] == 4.5

    # A rating-less cook doesn't drag the average (SQL avg ignores nulls).
    await auth_client.post(f"/recipes/{recipe_id}/cooked")
    detail = (await auth_client.get(f"/recipes/{recipe_id}")).json()
    assert detail["times_cooked"] == 3
    assert detail["avg_rating"] == 4.5

    listed = (await auth_client.get("/recipes")).json()
    assert next(r for r in listed if r["id"] == recipe_id)["avg_rating"] == 4.5


async def test_no_rating_leaves_avg_null(auth_client):
    recipe_id = await _recipe(auth_client)
    resp = await auth_client.post(f"/recipes/{recipe_id}/cooked")
    assert resp.status_code == 201
    assert resp.json()["avg_rating"] is None


async def test_rating_out_of_range_rejected(auth_client):
    recipe_id = await _recipe(auth_client)
    assert (
        await auth_client.post(f"/recipes/{recipe_id}/cooked", json={"rating": 6})
    ).status_code == 422
    assert (
        await auth_client.post(f"/recipes/{recipe_id}/cooked", json={"rating": 0})
    ).status_code == 422


async def test_cook_events_isolated_per_user(auth_client, client):
    import uuid as _uuid

    recipe_id = await _recipe(auth_client)
    await auth_client.post(f"/recipes/{recipe_id}/cooked")

    uid = _uuid.uuid4().hex[:8]
    other = await client.post(
        "/auth/register",
        json={"name": "Other", "email": f"o_{uid}@cookbook.com", "password": "Testpass123!"},
    )
    headers = {"Authorization": f"Bearer {other.json()['access_token']}"}
    resp = await client.post(f"/recipes/{recipe_id}/cooked", headers=headers)
    assert resp.status_code == 404  # not their recipe


async def test_deleting_recipe_cascades_events(auth_client):
    recipe_id = await _recipe(auth_client)
    await auth_client.post(f"/recipes/{recipe_id}/cooked")
    resp = await auth_client.delete(f"/recipes/{recipe_id}")
    assert resp.status_code == 204  # FK cascade, no 409 from the events table
