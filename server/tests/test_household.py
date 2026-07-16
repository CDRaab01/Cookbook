"""Family mode: household sharing + family (shared) vs private recipes.

Sharing is **consented**: adding someone creates a *pending* invite that shares nothing until the
invitee accepts it (nothing is joined silently).
"""

import uuid


async def _register(client, email: str) -> str:
    r = await client.post(
        "/auth/register", json={"name": "U", "email": email, "password": "Testpass123!"}
    )
    assert r.status_code in (200, 201), r.text
    return r.json()["access_token"]


def _h(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


async def _make_recipe(client, token: str, name: str, *, shared: bool) -> str:
    r = await client.post(
        "/recipes",
        json={"name": name, "servings": 2, "steps": ["mix"], "ingredients": [{"name": "egg"}]},
        headers=_h(token),
    )
    assert r.status_code == 201, r.text
    rid = r.json()["id"]
    if shared:
        s = await client.post(f"/recipes/{rid}/share", json={"shared": True}, headers=_h(token))
        assert s.status_code == 200, s.text
        assert s.json()["shared"] is True
    return rid


async def test_family_recipes_shared_private_recipes_not(client):
    uid = uuid.uuid4().hex[:8]
    wife_email = f"w_{uid}@cookbook.com"
    owner = await _register(client, f"o_{uid}@cookbook.com")
    wife = await _register(client, wife_email)

    private_id = await _make_recipe(client, owner, "Secret Sauce", shared=False)
    family_id = await _make_recipe(client, owner, "Family Chili", shared=True)

    # Before sharing the household, the wife sees neither and can't open the family recipe.
    got = {r["id"] for r in (await client.get("/recipes", headers=_h(wife))).json()}
    assert private_id not in got and family_id not in got
    assert (await client.get(f"/recipes/{family_id}", headers=_h(wife))).status_code == 404

    # Owner invites the wife — PENDING, so she still sees nothing until she accepts.
    r = await client.post("/household/members", json={"email": wife_email}, headers=_h(owner))
    assert r.status_code == 201, r.text
    assert r.json()["shared"] is False
    got = {r["id"] for r in (await client.get("/recipes", headers=_h(wife))).json()}
    assert family_id not in got

    # She has a pending invite naming the owner, and accepts it.
    invite = (await client.get("/household/invite", headers=_h(wife))).json()
    assert invite is not None and invite["owner_email"] == f"o_{uid}@cookbook.com"
    acc = await client.post("/household/accept", headers=_h(wife))
    assert acc.status_code == 200, acc.text
    assert acc.json()["shared"] is True

    # Now she sees the FAMILY recipe (flagged shared, not hers) but NOT the private one.
    listing = {r["id"]: r for r in (await client.get("/recipes", headers=_h(wife))).json()}
    assert family_id in listing and private_id not in listing
    assert listing[family_id]["shared"] is True
    assert listing[family_id]["is_owner"] is False

    # She can view + edit the family recipe (collaborative)...
    assert (await client.get(f"/recipes/{family_id}", headers=_h(wife))).status_code == 200
    edit = await client.patch(
        f"/recipes/{family_id}", json={"notes": "add cumin"}, headers=_h(wife)
    )
    assert edit.status_code == 200, edit.text
    # ...but can't delete it (creator-only), and still can't touch the private one.
    assert (await client.delete(f"/recipes/{family_id}", headers=_h(wife))).status_code == 404
    assert (await client.get(f"/recipes/{private_id}", headers=_h(wife))).status_code == 404


async def test_only_owner_manages_and_leaving_reverts(client):
    uid = uuid.uuid4().hex[:8]
    wife_email = f"w2_{uid}@cookbook.com"
    owner = await _register(client, f"o2_{uid}@cookbook.com")
    wife = await _register(client, wife_email)
    family_id = await _make_recipe(client, owner, "Shared Stew", shared=True)
    await client.post("/household/members", json={"email": wife_email}, headers=_h(owner))
    assert (await client.post("/household/accept", headers=_h(wife))).status_code == 200

    # A member can't add others.
    r = await client.post(
        "/household/members", json={"email": "someone@cookbook.com"}, headers=_h(wife)
    )
    assert r.status_code == 403

    # She sees the family recipe while a member...
    assert family_id in {r["id"] for r in (await client.get("/recipes", headers=_h(wife))).json()}
    # ...leaves, and no longer does.
    assert (await client.post("/household/leave", headers=_h(wife))).status_code == 204
    assert family_id not in {
        r["id"] for r in (await client.get("/recipes", headers=_h(wife))).json()
    }


async def test_household_shares_the_shopping_list_and_default(client):
    uid = uuid.uuid4().hex[:8]
    wife_email = f"lw_{uid}@cookbook.com"
    owner = await _register(client, f"lo_{uid}@cookbook.com")
    wife = await _register(client, wife_email)

    # The owner's default list (created on first touch).
    owner_list_id = (await client.get("/lists/default", headers=_h(owner))).json()["id"]

    # Before sharing, the wife's list listing does NOT include the owner's list, and her own default
    # is a different (private) list.
    wife_lists = (await client.get("/lists", headers=_h(wife))).json()
    assert all(entry["id"] != owner_list_id for entry in wife_lists)
    assert (await client.get("/lists/default", headers=_h(wife))).json()["id"] != owner_list_id

    # Invite + accept.
    r = await client.post("/household/members", json={"email": wife_email}, headers=_h(owner))
    assert r.status_code == 201, r.text
    assert (await client.post("/household/accept", headers=_h(wife))).status_code == 200

    # Now the owner's list shows up in the wife's listing, flagged shared...
    wife_lists = (await client.get("/lists", headers=_h(wife))).json()
    shared_owner_list = [e for e in wife_lists if e["id"] == owner_list_id]
    assert shared_owner_list and shared_owner_list[0]["shared"] is True
    # ...and her default now resolves to the owner's list — one shared list (and one shared plan).
    assert (await client.get("/lists/default", headers=_h(wife))).json()["id"] == owner_list_id


async def test_decline_removes_the_invite(client):
    uid = uuid.uuid4().hex[:8]
    wife_email = f"dw_{uid}@cookbook.com"
    owner = await _register(client, f"do_{uid}@cookbook.com")
    wife = await _register(client, wife_email)
    family_id = await _make_recipe(client, owner, "Declined Dish", shared=True)
    await client.post("/household/members", json={"email": wife_email}, headers=_h(owner))

    # She declines — the invite is gone and she never gained access.
    assert (await client.post("/household/decline", headers=_h(wife))).status_code == 204
    assert (await client.get("/household/invite", headers=_h(wife))).json() is None
    assert family_id not in {
        r["id"] for r in (await client.get("/recipes", headers=_h(wife))).json()
    }

    # Declining frees her to start her own household later (no lingering pending row): she can now
    # invite a fresh third user into a household of her own.
    third_email = f"dt_{uid}@cookbook.com"
    await _register(client, third_email)
    r = await client.post("/household/members", json={"email": third_email}, headers=_h(wife))
    assert r.status_code == 201, r.text


async def test_pending_invitee_cannot_start_own_household(client):
    uid = uuid.uuid4().hex[:8]
    wife_email = f"pw_{uid}@cookbook.com"
    owner = await _register(client, f"po_{uid}@cookbook.com")
    wife = await _register(client, wife_email)
    await client.post("/household/members", json={"email": wife_email}, headers=_h(owner))

    # With an invite outstanding, inviting someone (which would create her own household) is refused.
    r = await client.post(
        "/household/members", json={"email": "third@cookbook.com"}, headers=_h(wife)
    )
    assert r.status_code == 409


async def test_add_unknown_email_is_404(client):
    owner = await _register(client, f"o3_{uuid.uuid4().hex[:8]}@cookbook.com")
    r = await client.post(
        "/household/members", json={"email": "never_here@cookbook.com"}, headers=_h(owner)
    )
    assert r.status_code == 404
