import uuid


def _creds():
    uid = uuid.uuid4().hex[:8]
    return {
        "name": "Auth Tester",
        "email": f"auth_{uid}@cookbook.com",
        "password": "Testpass123!",
    }


async def test_register_and_login(client):
    creds = _creds()
    resp = await client.post("/auth/register", json=creds)
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["access_token"]
    assert body["refresh_token"]
    assert body["token_type"] == "bearer"

    resp = await client.post(
        "/auth/login", json={"email": creds["email"], "password": creds["password"]}
    )
    assert resp.status_code == 200
    assert resp.json()["access_token"]


async def test_register_duplicate_email_conflict(client):
    creds = _creds()
    resp = await client.post("/auth/register", json=creds)
    assert resp.status_code == 201
    resp = await client.post("/auth/register", json=creds)
    assert resp.status_code == 409


async def test_register_weak_password_rejected(client):
    creds = _creds()
    creds["password"] = "short"
    resp = await client.post("/auth/register", json=creds)
    assert resp.status_code == 422


async def test_login_wrong_password(client):
    creds = _creds()
    await client.post("/auth/register", json=creds)
    resp = await client.post(
        "/auth/login", json={"email": creds["email"], "password": "WrongPass123!"}
    )
    assert resp.status_code == 401


async def test_refresh_rotates_tokens(client):
    creds = _creds()
    resp = await client.post("/auth/register", json=creds)
    refresh_token = resp.json()["refresh_token"]

    resp = await client.post("/auth/refresh", json={"refresh_token": refresh_token})
    assert resp.status_code == 200
    assert resp.json()["access_token"]


async def test_refresh_rejects_access_token(client):
    creds = _creds()
    resp = await client.post("/auth/register", json=creds)
    access_token = resp.json()["access_token"]

    resp = await client.post("/auth/refresh", json={"refresh_token": access_token})
    assert resp.status_code == 401


async def test_me_requires_auth(client):
    resp = await client.get("/users/me")
    assert resp.status_code == 401


async def test_me_returns_profile(auth_client):
    resp = await auth_client.get("/users/me")
    assert resp.status_code == 200
    body = resp.json()
    assert body["name"] == "Test User"
    assert body["email"].endswith("@cookbook.com")
