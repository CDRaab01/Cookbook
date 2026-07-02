"""Multiple named lists (v0.3): CRUD, counts, isolation, and the default's self-healing."""


async def test_lists_index_creates_default_and_counts(auth_client):
    resp = await auth_client.get("/lists")
    assert resp.status_code == 200, resp.text
    lists = resp.json()
    assert len(lists) == 1
    assert lists[0]["name"] == "Groceries"
    assert lists[0]["unchecked_count"] == 0

    lid = lists[0]["id"]
    await auth_client.post(f"/lists/{lid}/items", json={"name": "Milk"})
    items = (
        await auth_client.post(f"/lists/{lid}/items", json={"name": "Bread"})
    ).json()["items"]
    await auth_client.patch(
        f"/lists/{lid}/items/{items[0]['id']}", json={"checked": True}
    )

    lists = (await auth_client.get("/lists")).json()
    assert lists[0]["unchecked_count"] == 1
    assert lists[0]["total_count"] == 2


async def test_create_rename_delete_list(auth_client):
    created = await auth_client.post("/lists", json={"name": "Costco"})
    assert created.status_code == 201, created.text
    costco = created.json()
    assert costco["name"] == "Costco"

    # Items on the new list are independent of the default.
    await auth_client.post(f"/lists/{costco['id']}/items", json={"name": "Bulk rice"})
    default = (await auth_client.get("/lists/default")).json()
    assert all(i["name"] != "Bulk rice" for i in default["items"])

    renamed = await auth_client.patch(f"/lists/{costco['id']}", json={"name": "Warehouse run"})
    assert renamed.json()["name"] == "Warehouse run"

    resp = await auth_client.delete(f"/lists/{costco['id']}")
    assert resp.status_code == 204
    names = [entry["name"] for entry in (await auth_client.get("/lists")).json()]
    assert "Warehouse run" not in names


async def test_default_self_heals_after_deleting_everything(auth_client):
    lists = (await auth_client.get("/lists")).json()
    for entry in lists:
        assert (await auth_client.delete(f"/lists/{entry['id']}")).status_code == 204

    # First touch after wipeout recreates the default.
    resp = await auth_client.get("/lists/default")
    assert resp.status_code == 200
    assert resp.json()["name"] == "Groceries"


async def test_suggest_still_routes_with_list_id_paths(auth_client):
    """Regression: /lists/suggest must not parse "suggest" as a list id (422)."""
    resp = await auth_client.get("/lists/suggest", params={"q": "an"})
    assert resp.status_code == 200


async def test_cross_user_isolation_on_lists(auth_client, client):
    import uuid as _uuid

    mine = (await auth_client.post("/lists", json={"name": "Private"})).json()

    uid = _uuid.uuid4().hex[:8]
    other = await client.post(
        "/auth/register",
        json={"name": "O", "email": f"o_{uid}@cookbook.com", "password": "Testpass123!"},
    )
    headers = {"Authorization": f"Bearer {other.json()['access_token']}"}
    assert (await client.get(f"/lists/{mine['id']}", headers=headers)).status_code == 404
    assert (
        await client.patch(f"/lists/{mine['id']}", json={"name": "Stolen"}, headers=headers)
    ).status_code == 404
    assert (await client.delete(f"/lists/{mine['id']}", headers=headers)).status_code == 404
    # And the other user's index shows only their own default.
    theirs = (await client.get("/lists", headers=headers)).json()
    assert all(entry["name"] != "Private" for entry in theirs)