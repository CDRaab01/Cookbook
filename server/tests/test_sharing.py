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
