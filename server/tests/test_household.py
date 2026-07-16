"""Family mode: household sharing + family (shared) vs private recipes."""

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

    # Owner shares the household with the wife.
    r = await client.post("/household/members", json={"email": wife_email}, headers=_h(owner))
    assert r.status_code == 201, r.text
    assert r.json()["shared"] is True

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


async def test_add_unknown_email_is_404(client):
    owner = await _register(client, f"o3_{uuid.uuid4().hex[:8]}@cookbook.com")
    r = await client.post(
        "/household/members", json={"email": "never_here@cookbook.com"}, headers=_h(owner)
    )
    assert r.status_code == 404
