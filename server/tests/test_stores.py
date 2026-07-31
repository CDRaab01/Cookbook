"""Store profiles: CRUD, the id-preserving aisle replace, placements, and household access.

The load-bearing behaviours here are (a) a brand-new store routes exactly like the plain category
grouping, so selecting one can never make the list worse, and (b) reordering aisles does not throw
away the placements someone learned by walking the store.
"""

import uuid

from app.models.recipe import STORE_CATEGORIES
from app.services.store_service import default_aisles


async def _register(client, email: str) -> str:
    r = await client.post(
        "/auth/register", json={"name": "U", "email": email, "password": "Testpass123!"}
    )
    assert r.status_code in (200, 201), r.text
    return r.json()["access_token"]


def _h(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


# --- pure ---


def test_default_aisles_cover_every_category_in_walk_order():
    aisles = default_aisles()
    assert [a.categories[0] for a in aisles] == list(STORE_CATEGORIES)
    assert all(len(a.categories) == 1 for a in aisles)
    assert all(a.name and a.id is None for a in aisles)
    # Named for humans, not for the database — a fresh store reads like a store.
    assert aisles[3].name == "Dairy & Eggs"


# --- CRUD ---


async def test_create_store_seeds_the_canonical_walk_order(auth_client):
    r = await auth_client.post("/stores", json={"name": "Meijer", "label": "Maysville Rd"})
    assert r.status_code == 201, r.text
    body = r.json()
    assert body["name"] == "Meijer" and body["label"] == "Maysville Rd"
    assert body["is_owner"] is True
    assert [a["categories"] for a in body["aisles"]] == [[c] for c in STORE_CATEGORIES]
    assert [a["order"] for a in body["aisles"]] == list(range(len(STORE_CATEGORIES)))
    assert body["placements"] == []


async def test_create_store_with_explicit_aisles(auth_client):
    r = await auth_client.post(
        "/stores",
        json={
            "name": "Aldi",
            "aisles": [
                {"name": "Produce", "categories": ["produce"]},
                {"name": "Aisle 3 — Baking", "categories": ["pantry", "bakery"]},
                {"name": "Checkout", "categories": []},
            ],
        },
    )
    assert r.status_code == 201, r.text
    aisles = r.json()["aisles"]
    assert [a["name"] for a in aisles] == ["Produce", "Aisle 3 — Baking", "Checkout"]
    assert aisles[1]["categories"] == ["pantry", "bakery"]
    assert aisles[2]["categories"] == []  # an aisle can exist purely as a placement target


async def test_list_patch_and_delete(auth_client):
    created = (await auth_client.post("/stores", json={"name": "Kroger"})).json()
    sid = created["id"]

    listed = (await auth_client.get("/stores")).json()
    assert [s["id"] for s in listed] == [sid]
    assert "aisles" not in listed[0]  # picker projection stays cheap

    # PATCH convention: None untouched, "" clears the label.
    r = await auth_client.patch(f"/stores/{sid}", json={"label": "Dupont"})
    assert r.json()["label"] == "Dupont" and r.json()["name"] == "Kroger"
    r = await auth_client.patch(f"/stores/{sid}", json={"label": ""})
    assert r.json()["label"] is None
    r = await auth_client.patch(f"/stores/{sid}", json={"name": "Kroger Marketplace"})
    assert r.json()["name"] == "Kroger Marketplace" and r.json()["label"] is None

    assert (await auth_client.delete(f"/stores/{sid}")).status_code == 204
    assert (await auth_client.get(f"/stores/{sid}")).status_code == 404


async def test_invalid_category_is_422(auth_client):
    r = await auth_client.post(
        "/stores",
        json={"name": "Bad", "aisles": [{"name": "A1", "categories": ["not_a_category"]}]},
    )
    assert r.status_code == 422


async def test_empty_store_name_is_422(auth_client):
    assert (await auth_client.post("/stores", json={"name": "   "})).status_code == 422


# --- aisle replace ---


async def test_aisle_put_preserves_ids_so_placements_survive_a_reorder(auth_client):
    store = (
        await auth_client.post(
            "/stores",
            json={
                "name": "Meijer",
                "aisles": [
                    {"name": "Produce", "categories": ["produce"]},
                    {"name": "Aisle 5", "categories": ["pantry"]},
                ],
            },
        )
    ).json()
    sid = store["id"]
    produce_id, aisle5_id = [a["id"] for a in store["aisles"]]

    # Learn that peanut butter is in aisle 5 here.
    placed = (
        await auth_client.post(
            f"/stores/{sid}/placements", json={"name": "Peanut Butter", "aisle_id": aisle5_id}
        )
    ).json()
    assert len(placed["placements"]) == 1
    assert placed["placements"][0]["key"] == "peanut butter"

    # Reorder + rename, carrying the ids: the placement must survive.
    r = await auth_client.put(
        f"/stores/{sid}/aisles",
        json={
            "aisles": [
                {"id": aisle5_id, "name": "Aisle 5 — Spreads", "categories": ["pantry"]},
                {"id": produce_id, "name": "Produce", "categories": ["produce"]},
            ]
        },
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert [a["name"] for a in body["aisles"]] == ["Aisle 5 — Spreads", "Produce"]
    assert [a["order"] for a in body["aisles"]] == [0, 1]
    assert len(body["placements"]) == 1
    assert body["placements"][0]["aisle_id"] == aisle5_id


async def test_dropping_an_aisle_cascades_its_placements(auth_client):
    store = (
        await auth_client.post(
            "/stores",
            json={
                "name": "Meijer",
                "aisles": [
                    {"name": "Produce", "categories": ["produce"]},
                    {"name": "Aisle 5", "categories": ["pantry"]},
                ],
            },
        )
    ).json()
    sid = store["id"]
    produce_id, aisle5_id = [a["id"] for a in store["aisles"]]
    await auth_client.post(
        f"/stores/{sid}/placements", json={"name": "peanut butter", "aisle_id": aisle5_id}
    )

    # Aisle 5 is gone from the layout — so is what it knew. Documented, not silent.
    r = await auth_client.put(
        f"/stores/{sid}/aisles",
        json={"aisles": [{"id": produce_id, "name": "Produce", "categories": ["produce"]}]},
    )
    assert r.status_code == 200, r.text
    assert len(r.json()["aisles"]) == 1
    assert r.json()["placements"] == []


async def test_aisle_put_with_unknown_id_inserts_instead_of_failing(auth_client):
    """A stale id means another device edited the layout; the user's reordering still saves."""
    sid = (await auth_client.post("/stores", json={"name": "S"})).json()["id"]
    r = await auth_client.put(
        f"/stores/{sid}/aisles",
        json={"aisles": [{"id": str(uuid.uuid4()), "name": "Ghost", "categories": []}]},
    )
    assert r.status_code == 200, r.text
    assert [a["name"] for a in r.json()["aisles"]] == ["Ghost"]


# --- placements ---


async def test_placement_upserts_by_normalized_name(auth_client):
    store = (
        await auth_client.post(
            "/stores",
            json={
                "name": "Meijer",
                "aisles": [
                    {"name": "A1", "categories": ["produce"]},
                    {"name": "A2", "categories": ["pantry"]},
                ],
            },
        )
    ).json()
    sid = store["id"]
    a1, a2 = [a["id"] for a in store["aisles"]]

    await auth_client.post(f"/stores/{sid}/placements", json={"name": "Eggs", "aisle_id": a1})
    # Same item, different spelling + different aisle: one row, last write wins.
    body = (
        await auth_client.post(f"/stores/{sid}/placements", json={"name": "egg", "aisle_id": a2})
    ).json()
    assert len(body["placements"]) == 1
    assert body["placements"][0]["aisle_id"] == a2

    pid = body["placements"][0]["id"]
    body = (await auth_client.delete(f"/stores/{sid}/placements/{pid}")).json()
    assert body["placements"] == []


async def test_placement_rejects_an_aisle_from_another_store(auth_client):
    mine = (await auth_client.post("/stores", json={"name": "Mine"})).json()
    other = (await auth_client.post("/stores", json={"name": "Other"})).json()
    r = await auth_client.post(
        f"/stores/{mine['id']}/placements",
        json={"name": "milk", "aisle_id": other["aisles"][0]["id"]},
    )
    assert r.status_code == 404


# --- access ---


async def test_household_shares_stores_but_only_after_accepting(client):
    uid = uuid.uuid4().hex[:8]
    wife_email = f"w_{uid}@cookbook.com"
    owner = await _register(client, f"o_{uid}@cookbook.com")
    wife = await _register(client, wife_email)

    store = (await client.post("/stores", json={"name": "Meijer"}, headers=_h(owner))).json()
    sid = store["id"]
    assert (await client.get(f"/stores/{sid}", headers=_h(wife))).status_code == 404

    r = await client.post("/household/members", json={"email": wife_email}, headers=_h(owner))
    assert r.status_code == 201
    # Pending invite shares nothing.
    assert (await client.get(f"/stores/{sid}", headers=_h(wife))).status_code == 404

    assert (await client.post("/household/accept", headers=_h(wife))).status_code in (200, 204)
    got = await client.get(f"/stores/{sid}", headers=_h(wife))
    assert got.status_code == 200
    assert got.json()["is_owner"] is False
    assert [s["id"] for s in (await client.get("/stores", headers=_h(wife))).json()] == [sid]

    # A co-member reshapes the floor plan (shared knowledge) …
    r = await client.put(
        f"/stores/{sid}/aisles",
        json={"aisles": [{"name": "Produce", "categories": ["produce"]}]},
        headers=_h(wife),
    )
    assert r.status_code == 200
    # … but deleting someone else's store is not hers to do.
    assert (await client.delete(f"/stores/{sid}", headers=_h(wife))).status_code == 404
    assert (await client.delete(f"/stores/{sid}", headers=_h(owner))).status_code == 204


async def test_a_stranger_sees_nothing(client):
    uid = uuid.uuid4().hex[:8]
    owner = await _register(client, f"o_{uid}@cookbook.com")
    stranger = await _register(client, f"s_{uid}@cookbook.com")
    sid = (await client.post("/stores", json={"name": "Meijer"}, headers=_h(owner))).json()["id"]

    assert (await client.get(f"/stores/{sid}", headers=_h(stranger))).status_code == 404
    assert (await client.get("/stores", headers=_h(stranger))).json() == []
    assert (
        await client.put(f"/stores/{sid}/aisles", json={"aisles": []}, headers=_h(stranger))
    ).status_code == 404


async def test_stores_require_auth(client):
    client.headers.pop("Authorization", None)
    assert (await client.get("/stores")).status_code == 401


# --- the placement key the client routes by ---


async def test_list_items_carry_the_placement_key(auth_client):
    lst = (await auth_client.get("/lists/default")).json()
    r = await auth_client.post(f"/lists/{lst['id']}/items", json={"name": "Peanut Butter"})
    assert r.status_code == 201, r.text
    item = r.json()["items"][0]
    # Same key space as a store placement, computed server-side so the client never re-normalizes.
    assert item["key"] == "peanut butter"
