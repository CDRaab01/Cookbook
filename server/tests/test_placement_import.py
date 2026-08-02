"""Importing harvested aisle observations into a store's placements (v0.12).

The load-bearing behaviours, each of which is a property the feature is unsafe without:

- an import is **idempotent** — running it twice changes nothing the second time;
- it is **never destructive** — it cannot delete an aisle, drop a placement, or overwrite one with
  "no aisle", because a person who walked the store outranks a scrape;
- it **never touches ``item_history`` or an item's ``category``** — a retailer's shelf map says
  nothing about how *this user* files things, or about the next store they shop;
- discovered aisles claim **no categories**, so one observation can't silently re-route a whole
  category of items nobody has looked up.
"""

import uuid


async def _store(client, **kwargs) -> dict:
    body = {"name": "Meijer", "label": "Maysville Rd"} | kwargs
    r = await client.post("/stores", json=body)
    assert r.status_code == 201, r.text
    return r.json()


async def _default_list(client) -> dict:
    r = await client.get("/lists/default")
    assert r.status_code == 200, r.text
    return r.json()


async def _add(client, list_id: str, name: str, category: str | None = None) -> dict:
    """Add an item and return **that item's** row.

    ``POST /lists/{id}/items`` responds with the whole list, not the created row, so the item has
    to be found by name — and by the *stored* name, since the service may clean it.
    """
    payload = {"name": name}
    if category is not None:
        payload["category"] = category
    r = await client.post(f"/lists/{list_id}/items", json=payload)
    assert r.status_code in (200, 201), r.text
    items = r.json()["items"]
    match = next((i for i in items if i["name"].casefold() == name.casefold()), None)
    assert match is not None, f"{name!r} not in {[i['name'] for i in items]}"
    return match


def _aisle_named(store: dict, name: str) -> dict | None:
    return next((a for a in store["aisles"] if a["name"] == name), None)


def _placement_named(store: dict, name: str) -> dict | None:
    """Look placements up by display name, not by key.

    ``key`` is ``normalize_name``, which singularizes — "paper towels" is stored under
    "paper towel". Hardcoding keys in tests re-implements the normalizer and drifts from it.
    """
    return next((p for p in store["placements"] if p["name"] == name), None)


# --- the happy path ---


async def test_import_creates_aisles_and_places_items(auth_client):
    store = await _store(auth_client)
    r = await auth_client.post(
        f"/stores/{store['id']}/placements/import",
        json={
            "retailer": "meijer",
            "retailer_store_id": "138",
            "observations": [
                {"name": "peanut butter", "aisle": "Aisle B | 16", "section": "39"},
                {"name": "bananas", "aisle": "Aisle A | 11", "section": "10"},
                {"name": "paper towels", "aisle": "Aisle B | 14", "section": "31"},
            ],
        },
    )
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["placed"] == 3
    assert body["aisles_created"] == 3
    assert body["skipped"] == []

    out = body["store"]
    assert out["retailer"] == "meijer" and out["retailer_store_id"] == "138"
    for label in ("Aisle A | 11", "Aisle B | 14", "Aisle B | 16"):
        assert _aisle_named(out, label) is not None
    # A discovered aisle is a placement target, not a claim about a category's home.
    assert all(_aisle_named(out, la)["categories"] == [] for la in ("Aisle A | 11", "Aisle B | 16"))
    assert (
        _placement_named(out, "peanut butter")["aisle_id"]
        == _aisle_named(out, "Aisle B | 16")["id"]
    )


async def test_discovered_aisles_walk_before_the_seeded_category_block(auth_client):
    """The 13 seeded aisles remain as the fallback for unlooked-up items, but they belong *after*
    the real aisles — a tail that visibly shrinks as coverage grows."""
    store = await _store(auth_client)
    r = await auth_client.post(
        f"/stores/{store['id']}/placements/import",
        json={
            "observations": [
                {"name": "peanut butter", "aisle": "B | 16"},
                {"name": "bananas", "aisle": "A | 11"},
                {"name": "milk", "aisle": "B | 7"},
            ]
        },
    )
    aisles = sorted(r.json()["store"]["aisles"], key=lambda a: a["order"])
    names = [a["name"] for a in aisles]
    # Zone, then number — numerically, so 11 follows 2 rather than sorting as a string.
    assert names[:3] == ["Aisle A | 11", "Aisle B | 7", "Aisle B | 16"]
    assert names[3] == "Produce"  # the seeded block starts here, in its own canonical order
    assert [a["order"] for a in aisles] == list(range(len(aisles)))


async def test_spelling_variants_collapse_onto_one_aisle(auth_client):
    """ "Aisle B | 16", "B|16" and "b | 16" are one physical aisle. Three rows would be a bug you
    only notice standing in the store."""
    store = await _store(auth_client)
    r = await auth_client.post(
        f"/stores/{store['id']}/placements/import",
        json={
            "observations": [
                {"name": "peanut butter", "aisle": "Aisle B | 16"},
                {"name": "jelly", "aisle": "B|16"},
                {"name": "honey", "aisle": "b  |  016"},
            ]
        },
    )
    body = r.json()
    assert body["aisles_created"] == 1
    out = body["store"]
    target = _aisle_named(out, "Aisle B | 16")["id"]
    assert {_placement_named(out, k)["aisle_id"] for k in ("peanut butter", "jelly", "honey")} == {
        target
    }


# --- idempotence + non-destructiveness ---


async def test_reimport_is_a_no_op(auth_client):
    store = await _store(auth_client)
    payload = {
        "observations": [
            {"name": "peanut butter", "aisle": "B | 16"},
            {"name": "bananas", "aisle": "A | 11"},
        ]
    }
    first = (
        await auth_client.post(f"/stores/{store['id']}/placements/import", json=payload)
    ).json()
    assert first["placed"] == 2 and first["aisles_created"] == 2

    second = (
        await auth_client.post(f"/stores/{store['id']}/placements/import", json=payload)
    ).json()
    # Counts report *changes*, so an unchanged re-import says 0 rather than restating the batch.
    assert second["placed"] == 0
    assert second["aisles_created"] == 0
    assert len(second["store"]["placements"]) == 2
    assert [a["name"] for a in second["store"]["aisles"]] == [
        a["name"] for a in first["store"]["aisles"]
    ]


async def test_observation_without_an_aisle_is_skipped_not_applied(auth_client):
    """A service counter genuinely has no aisle. Recording that as a skip is honest; inventing a
    home for it is not."""
    store = await _store(auth_client)
    body = (
        await auth_client.post(
            f"/stores/{store['id']}/placements/import",
            json={
                "observations": [
                    {"name": "rotisserie chicken", "aisle": None},
                    {"name": "sliced turkey", "aisle": ""},
                    {"name": "bananas", "aisle": "A | 11"},
                ]
            },
        )
    ).json()
    assert body["placed"] == 1
    assert sorted(body["skipped"]) == ["rotisserie chicken", "sliced turkey"]
    assert len(body["store"]["placements"]) == 1


async def test_an_empty_observation_never_clears_a_placement_the_user_made(auth_client):
    """The user walked the store and filed it. A later scrape that can't find an aisle must not
    undo that — evidence does not outrank the person who was standing there."""
    store = await _store(auth_client)
    aisle_id = store["aisles"][0]["id"]
    await auth_client.post(
        f"/stores/{store['id']}/placements", json={"name": "peanut butter", "aisle_id": aisle_id}
    )

    body = (
        await auth_client.post(
            f"/stores/{store['id']}/placements/import",
            json={"observations": [{"name": "peanut butter", "aisle": None}]},
        )
    ).json()
    assert body["skipped"] == ["peanut butter"]
    assert _placement_named(body["store"], "peanut butter")["aisle_id"] == aisle_id


async def test_import_never_deletes_an_existing_aisle(auth_client):
    """Unlike the aisle PUT (a full replace), an import is purely additive."""
    store = await _store(auth_client)
    before = {a["name"] for a in store["aisles"]}
    body = (
        await auth_client.post(
            f"/stores/{store['id']}/placements/import",
            json={"observations": [{"name": "bananas", "aisle": "A | 11"}]},
        )
    ).json()
    after = {a["name"] for a in body["store"]["aisles"]}
    assert before <= after
    assert after - before == {"Aisle A | 11"}


async def test_a_newer_observation_moves_an_existing_placement(auth_client):
    """Idempotence must not mean immutability — a genuinely different aisle is a real update."""
    store = await _store(auth_client)
    await auth_client.post(
        f"/stores/{store['id']}/placements/import",
        json={"observations": [{"name": "peanut butter", "aisle": "B | 16"}]},
    )
    body = (
        await auth_client.post(
            f"/stores/{store['id']}/placements/import",
            json={"observations": [{"name": "peanut butter", "aisle": "B | 18"}]},
        )
    ).json()
    assert body["placed"] == 1
    out = body["store"]
    assert (
        _placement_named(out, "peanut butter")["aisle_id"]
        == _aisle_named(out, "Aisle B | 18")["id"]
    )
    assert len([p for p in out["placements"] if p["key"] == "peanut butter"]) == 1


# --- the invariants borrowed from upsert_placement ---


async def test_import_never_touches_the_item_category_or_history(auth_client):
    """Where a thing sits in *this* store says nothing about the next one, and a retailer's shelf
    map is not a statement about how the user files things."""
    store = await _store(auth_client)
    shopping_list = await _default_list(auth_client)
    item = await _add(auth_client, shopping_list["id"], "paper towels", category="household")

    await auth_client.post(
        f"/stores/{store['id']}/placements/import",
        json={"observations": [{"name": "paper towels", "aisle": "B | 14"}]},
    )

    r = await auth_client.get(f"/lists/{shopping_list['id']}")
    row = next(i for i in r.json()["items"] if i["id"] == item["id"])
    assert row["category"] == "household"

    # item_history is the record of where *you* file things; a scrape must not become "remembered".
    suggestions = await auth_client.get("/lists/suggestions", params={"q": "paper"})
    if suggestions.status_code == 200:
        for hit in suggestions.json():
            assert hit.get("category") in (None, "household")


async def test_matched_name_never_becomes_the_item_name(auth_client):
    """Meijer matched "Bounty Advanced Paper Towels"; the item is still the user's "paper towels"."""
    store = await _store(auth_client)
    body = (
        await auth_client.post(
            f"/stores/{store['id']}/placements/import",
            json={
                "observations": [
                    {
                        "name": "paper towels",
                        "aisle": "B | 14",
                        "matched_name": "Bounty Advanced Paper Towels, 6 Double Rolls",
                    }
                ]
            },
        )
    ).json()
    assert _placement_named(body["store"], "paper towels")["name"] == "paper towels"


# --- the worklist ---


async def test_unplaced_lists_only_what_this_store_has_no_home_for(auth_client):
    store = await _store(auth_client)
    shopping_list = await _default_list(auth_client)
    await _add(auth_client, shopping_list["id"], "bananas")
    await _add(auth_client, shopping_list["id"], "peanut butter")

    r = await auth_client.get(
        f"/stores/{store['id']}/unplaced", params={"list_id": shopping_list["id"]}
    )
    assert r.status_code == 200, r.text
    assert {i["name"] for i in r.json()["items"]} == {"bananas", "peanut butter"}

    await auth_client.post(
        f"/stores/{store['id']}/placements/import",
        json={"observations": [{"name": "bananas", "aisle": "A | 11"}]},
    )
    r = await auth_client.get(
        f"/stores/{store['id']}/unplaced", params={"list_id": shopping_list["id"]}
    )
    # Progress is measurable, not assumed: the imported item drops off the worklist.
    assert [i["name"] for i in r.json()["items"]] == ["peanut butter"]


async def test_unplaced_skips_checked_items(auth_client):
    """A checked item is history for this trip — looking up where to find it is wasted effort."""
    store = await _store(auth_client)
    shopping_list = await _default_list(auth_client)
    item = await _add(auth_client, shopping_list["id"], "bananas")
    await _add(auth_client, shopping_list["id"], "peanut butter")
    await auth_client.patch(
        f"/lists/{shopping_list['id']}/items/{item['id']}", json={"checked": True}
    )

    r = await auth_client.get(
        f"/stores/{store['id']}/unplaced", params={"list_id": shopping_list["id"]}
    )
    assert [i["name"] for i in r.json()["items"]] == ["peanut butter"]


async def test_unplaced_reports_the_linked_retailer_store(auth_client):
    """The harvester asks the server which store to look things up in, rather than being told."""
    store = await _store(auth_client)
    shopping_list = await _default_list(auth_client)
    await auth_client.post(
        f"/stores/{store['id']}/placements/import",
        json={"retailer": "meijer", "retailer_store_id": "138", "observations": []},
    )
    r = await auth_client.get(
        f"/stores/{store['id']}/unplaced", params={"list_id": shopping_list["id"]}
    )
    body = r.json()
    assert body["retailer"] == "meijer" and body["retailer_store_id"] == "138"


# --- access ---


async def test_import_requires_access_to_the_store(client, auth_client):
    store = await _store(auth_client)
    other = uuid.uuid4().hex[:8]
    r = await client.post(
        "/auth/register",
        json={"name": "Other", "email": f"other_{other}@cookbook.com", "password": "Testpass123!"},
    )
    token = r.json()["access_token"]
    r = await client.post(
        f"/stores/{store['id']}/placements/import",
        json={"observations": [{"name": "bananas", "aisle": "A | 11"}]},
        headers={"Authorization": f"Bearer {token}"},
    )
    assert r.status_code == 404


async def test_import_requires_auth(client, auth_client):
    store = await _store(auth_client)
    r = await client.post(
        f"/stores/{store['id']}/placements/import",
        json={"observations": []},
        headers={"Authorization": "Bearer nope"},
    )
    assert r.status_code == 401


async def test_unplaced_carries_a_search_query_for_the_harvester(auth_client):
    """The worklist tells the harvester what to type into the retailer's search box, so no client
    re-implements the cleaning — the same reason ``ItemOut.key`` is computed server-side."""
    store = await _store(auth_client)
    shopping_list = await _default_list(auth_client)
    await _add(auth_client, shopping_list["id"], "cream cheese at room temp")
    await _add(auth_client, shopping_list["id"], "soy sauce")

    r = await auth_client.get(
        f"/stores/{store['id']}/unplaced", params={"list_id": shopping_list["id"]}
    )
    by_name = {i["name"]: i["search_query"] for i in r.json()["items"]}
    assert by_name["cream cheese at room temp"] == "cream cheese"
    # An already-clean name passes through untouched.
    assert by_name["soy sauce"] == "soy sauce"


async def test_a_search_query_is_never_empty(auth_client):
    """An empty query finds nothing, which reads as "this store doesn't stock it"."""
    store = await _store(auth_client)
    shopping_list = await _default_list(auth_client)
    await _add(auth_client, shopping_list["id"], "chopped fresh")

    r = await auth_client.get(
        f"/stores/{store['id']}/unplaced", params={"list_id": shopping_list["id"]}
    )
    assert all(i["search_query"] for i in r.json()["items"])
