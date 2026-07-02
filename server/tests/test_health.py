async def test_health(client):
    resp = await client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


async def test_version(client):
    resp = await client.get("/version")
    assert resp.status_code == 200
    body = resp.json()
    assert body["name"] == "Cookbook API"
    assert body["version"]
    # Unstamped dev/test runs report "unknown"; a deploy injects the real values.
    assert "commit" in body
    assert "built_at" in body
