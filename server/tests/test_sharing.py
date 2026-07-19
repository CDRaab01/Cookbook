"""Household sharing: share a shopping list by invite; members view + edit, owner manages."""

import uuid


async def _register(client, email: str) -> str:
    resp = await client.post(
        "/auth/register",
        json={"name": "U", "email": email, "password": "Testpass123!"},
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["access_token"]


def _h(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


async def _two_users(client):
    uid = uuid.uuid4().hex[:8]
    owner = await _register(client, f"owner_{uid}@cookbook.com")
    invitee_email = f"invitee_{uid}@cookbook.com"
    invitee = await _register(client, invitee_email)
    return owner, invitee, invitee_email


async def test_share_grants_access_and_shows_in_both_indexes(client):
    owner, invitee, invitee_email = await _two_users(client)
    lst = (await client.get("/lists/default", headers=_h(owner))).json()
    lid = lst["id"]

    # Before sharing: invitee can't see the list at all.
    assert (await client.get(f"/lists/{lid}", headers=_h(invitee))).status_code == 404

    share = await client.post(
        f"/lists/{lid}/members", json={"email": invitee_email}, headers=_h(owner)
    )
    assert share.status_code == 201, share.text
    members = share.json()
    assert len(members) == 2
    assert sum(1 for m in members if m["is_owner"]) == 1

    # Invitee can now view AND edit the shared list.
    assert (await client.get(f"/lists/{lid}", headers=_h(invitee))).status_code == 200
    add = await client.post(f"/lists/{lid}/items", json={"name": "Milk"}, headers=_h(invitee))
    assert add.status_code == 201, add.text

    # The shared list appears in the invitee's index (shared, not owner)…
    inv_idx = (await client.get("/lists", headers=_h(invitee))).json()
    inv_row = next(x for x in inv_idx if x["id"] == lid)
    assert inv_row["shared"] is True and inv_row["is_owner"] is False
    # …and is badged shared in the owner's index too.
    own_idx = (await client.get("/lists", headers=_h(owner))).json()
    own_row = next(x for x in own_idx if x["id"] == lid)
    assert own_row["shared"] is True and own_row["is_owner"] is True


async def test_share_unknown_email_404(client):
    owner, _, _ = await _two_users(client)
    lid = (await client.get("/lists/default", headers=_h(owner))).json()["id"]
    resp = await client.post(
        f"/lists/{lid}/members", json={"email": "nobody@nowhere.com"}, headers=_h(owner)
    )
    assert resp.status_code == 404


async def test_share_is_idempotent(client):
    owner, invitee, invitee_email = await _two_users(client)
    lid = (await client.get("/lists/default", headers=_h(owner))).json()["id"]
    for _ in range(2):
        resp = await client.post(
            f"/lists/{lid}/members", json={"email": invitee_email}, headers=_h(owner)
        )
        assert resp.status_code == 201
        assert len(resp.json()) == 2  # not duplicated


async def test_member_cannot_manage_or_rename(client):
    owner, invitee, invitee_email = await _two_users(client)
    lid = (await client.get("/lists/default", headers=_h(owner))).json()["id"]
    await client.post(f"/lists/{lid}/members", json={"email": invitee_email}, headers=_h(owner))

    # A member can't rename or delete the owner's list (owner-only → 404).
    assert (await client.patch(f"/lists/{lid}", json={"name": "Hijacked"}, headers=_h(invitee))).status_code == 404
    assert (await client.delete(f"/lists/{lid}", headers=_h(invitee))).status_code == 404


async def test_shared_list_plan_is_collaborative(client):
    owner, invitee, invitee_email = await _two_users(client)
    lid = (await client.get("/lists/default", headers=_h(owner))).json()["id"]
    await client.post(f"/lists/{lid}/members", json={"email": invitee_email}, headers=_h(owner))

    # Owner and invitee each plan a meal on the shared list.
    r1 = await client.post(
        f"/plan?list_id={lid}",
        json={"date": "2026-07-06", "slot": "dinner", "note": "Tacos"},
        headers=_h(owner),
    )
    assert r1.status_code == 201, r1.text
    r2 = await client.post(
        f"/plan?list_id={lid}",
        json={"date": "2026-07-07", "slot": "dinner", "note": "Pasta"},
        headers=_h(invitee),
    )
    assert r2.status_code == 201, r2.text

    # Both see the same combined household plan.
    for tok in (owner, invitee):
        plan = (
            await client.get(
                f"/plan?start=2026-07-06&end=2026-07-12&list_id={lid}", headers=_h(tok)
            )
        ).json()
        assert {e["note"] for e in plan} == {"Tacos", "Pasta"}

    # A member can act on the other's entry (mark eaten) — it's a shared plan.
    assert (
        await client.patch(f"/plan/{r1.json()['id']}", json={"eaten": True}, headers=_h(invitee))
    ).status_code == 200

    # A non-member can't read the shared plan.
    stranger = await _register(client, f"stranger_{uuid.uuid4().hex[:6]}@cookbook.com")
    assert (
        await client.get(
            f"/plan?start=2026-07-06&end=2026-07-12&list_id={lid}", headers=_h(stranger)
        )
    ).status_code == 404


async def test_eaten_is_per_user_on_shared_plan(client):
    """On a shared plan, confirming a meal is per-person: one member marking eaten (at a portion)
    must not flip it eaten for the other — calories are per-user."""
    owner, invitee, invitee_email = await _two_users(client)
    lid = (await client.get("/lists/default", headers=_h(owner))).json()["id"]
    await client.post(f"/lists/{lid}/members", json={"email": invitee_email}, headers=_h(owner))

    entry = await client.post(
        f"/plan?list_id={lid}",
        json={"date": "2026-07-08", "slot": "dinner", "note": "Curry"},
        headers=_h(owner),
    )
    eid = entry.json()["id"]
    assert entry.json()["eaten"] is False and entry.json()["servings"] == 1.0

    # Owner confirms at 2 servings.
    owned = await client.patch(
        f"/plan/{eid}", json={"eaten": True, "servings": 2}, headers=_h(owner)
    )
    assert owned.status_code == 200, owned.text
    assert owned.json()["eaten"] is True and owned.json()["servings"] == 2.0

    window = f"/plan?start=2026-07-06&end=2026-07-12&list_id={lid}"

    def _entry(plan):
        return next(e for e in plan if e["id"] == eid)

    # Invitee sees the SAME shared entry, but as not-yet-eaten (their own confirmation).
    inv_view = _entry((await client.get(window, headers=_h(invitee))).json())
    assert inv_view["eaten"] is False
    # Owner still sees their own confirmation.
    own_view = _entry((await client.get(window, headers=_h(owner))).json())
    assert own_view["eaten"] is True and own_view["servings"] == 2.0

    # Invitee confirms independently; owner's confirmation is untouched.
    await client.patch(f"/plan/{eid}", json={"eaten": True}, headers=_h(invitee))
    own_after = _entry((await client.get(window, headers=_h(owner))).json())
    assert own_after["eaten"] is True and own_after["servings"] == 2.0


async def test_concurrent_cross_user_adds_merge_into_one_list(client):
    """The merge kernel is list-scoped, so two members adding the *same* item (any spelling/unit)
    fold into a single line — the household buys one carton of milk, not two. Distinct items from
    each member coexist, and both members see the identical merged list."""
    owner, invitee, invitee_email = await _two_users(client)
    lid = (await client.get("/lists/default", headers=_h(owner))).json()["id"]
    await client.post(f"/lists/{lid}/members", json={"email": invitee_email}, headers=_h(owner))

    # Owner and invitee independently add "milk" (different case + unit spelling) to the shared
    # list. Liters is a unit you actually buy milk by, so it belongs on the list and merges.
    await client.post(
        f"/lists/{lid}/items",
        json={"name": "Milk", "quantity": 1, "unit": "liter"},
        headers=_h(owner),
    )
    await client.post(
        f"/lists/{lid}/items",
        json={"name": "milk", "quantity": 1, "unit": "liters"},
        headers=_h(invitee),
    )
    # Each also adds something only they need.
    await client.post(f"/lists/{lid}/items", json={"name": "Eggs"}, headers=_h(owner))
    await client.post(f"/lists/{lid}/items", json={"name": "Bread"}, headers=_h(invitee))

    # Both members see the SAME list, and milk is a single merged line summing to 2 L.
    for tok in (owner, invitee):
        items = (await client.get(f"/lists/{lid}", headers=_h(tok))).json()["items"]
        names = sorted(i["name"].lower() for i in items)
        assert names == ["bread", "eggs", "milk"]  # one milk line, not two
        milk = next(i for i in items if i["name"].lower() == "milk")
        assert milk["quantity"] == 2.0 and milk["unit"] == "l"
        assert milk["measures"] == [{"quantity": 2.0, "unit": "l"}]


async def test_non_member_cannot_access_or_mutate_shared_list(client):
    """Authorization: a stranger who is neither owner nor member is walled off from every read and
    write on the list — each returns 404 (existence isn't leaked)."""
    owner, invitee, invitee_email = await _two_users(client)
    lid = (await client.get("/lists/default", headers=_h(owner))).json()["id"]
    # Share with the invitee so the list is genuinely shared — the stranger still gets nothing.
    await client.post(f"/lists/{lid}/members", json={"email": invitee_email}, headers=_h(owner))
    await client.post(f"/lists/{lid}/items", json={"name": "Milk"}, headers=_h(owner))

    stranger = await _register(client, f"stranger_{uuid.uuid4().hex[:6]}@cookbook.com")
    fake_item = str(uuid.uuid4())

    # Reads.
    assert (await client.get(f"/lists/{lid}", headers=_h(stranger))).status_code == 404
    assert (await client.get(f"/lists/{lid}/members", headers=_h(stranger))).status_code == 404
    # Item mutations.
    assert (
        await client.post(f"/lists/{lid}/items", json={"name": "Sneak"}, headers=_h(stranger))
    ).status_code == 404
    assert (
        await client.patch(
            f"/lists/{lid}/items/{fake_item}", json={"checked": True}, headers=_h(stranger)
        )
    ).status_code == 404
    assert (
        await client.delete(f"/lists/{lid}/items/{fake_item}", headers=_h(stranger))
    ).status_code == 404
    assert (
        await client.post(f"/lists/{lid}/clear-checked", headers=_h(stranger))
    ).status_code == 404
    # Owner-only actions are equally invisible to a stranger.
    assert (
        await client.post(f"/lists/{lid}/members", json={"email": "x@y.com"}, headers=_h(stranger))
    ).status_code == 404
    assert (
        await client.patch(f"/lists/{lid}", json={"name": "Nope"}, headers=_h(stranger))
    ).status_code == 404
    assert (await client.delete(f"/lists/{lid}", headers=_h(stranger))).status_code == 404

    # And the list is untouched by all that probing.
    items = (await client.get(f"/lists/{lid}", headers=_h(owner))).json()["items"]
    assert [i["name"] for i in items] == ["Milk"]


async def test_member_can_leave(client):
    owner, invitee, invitee_email = await _two_users(client)
    lid = (await client.get("/lists/default", headers=_h(owner))).json()["id"]
    members = (await client.post(
        f"/lists/{lid}/members", json={"email": invitee_email}, headers=_h(owner)
    )).json()
    invitee_id = next(m["user_id"] for m in members if not m["is_owner"])

    # Invitee removes themselves.
    leave = await client.delete(f"/lists/{lid}/members/{invitee_id}", headers=_h(invitee))
    assert leave.status_code == 204
    # Access is gone.
    assert (await client.get(f"/lists/{lid}", headers=_h(invitee))).status_code == 404
    # And the owner's list is no longer badged shared.
    own_row = next(x for x in (await client.get("/lists", headers=_h(owner))).json() if x["id"] == lid)
    assert own_row["shared"] is False
