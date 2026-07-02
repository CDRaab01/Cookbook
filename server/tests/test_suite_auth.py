import time

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from jose import jwk
from jose import jwt as jose_jwt
from sqlalchemy import func, select

from app.config import settings
from app.database import AsyncSessionLocal
from app.models.user import User

ISSUER = "http://id.test"
KID = "test-kid"

_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_PRIVATE_PEM = _key.private_bytes(
    serialization.Encoding.PEM,
    serialization.PrivateFormat.PKCS8,
    serialization.NoEncryption(),
).decode()
_PUBLIC_PEM = (
    _key.public_key()
    .public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
    .decode()
)


def _jwks() -> dict:
    d = jwk.construct(_PUBLIC_PEM, "RS256").to_dict()
    d.update({"kid": KID, "use": "sig", "alg": "RS256"})
    return {"keys": [d]}


def _suite_token(email: str, *, iss: str = ISSUER, aud: str = "suite", name: str = "SSO User") -> str:
    now = int(time.time())
    claims = {
        "iss": iss,
        "sub": "suite-user-1",
        "aud": aud,
        "email": email,
        "name": name,
        "iat": now,
        "exp": now + 300,
    }
    return jose_jwt.encode(claims, _PRIVATE_PEM, algorithm="RS256", headers={"kid": KID})


@pytest.fixture
def suite_enabled(monkeypatch):
    monkeypatch.setattr(settings, "suite_jwks_url", "http://id.test/jwks")
    monkeypatch.setattr(settings, "suite_issuer", ISSUER)
    monkeypatch.setattr(settings, "suite_audience", "suite")

    async def _fake_fetch(*, force: bool = False):
        return _jwks()

    monkeypatch.setattr("app.services.suite_auth._fetch_jwks", _fake_fetch)


async def _count_users(email: str) -> int:
    async with AsyncSessionLocal() as s:
        return (
            await s.execute(select(func.count()).select_from(User).where(User.email == email))
        ).scalar()


async def test_disabled_by_default_returns_404(client):
    # No suite_* config → the endpoint is off, app's own login unaffected.
    r = await client.post("/auth/suite", json={"suite_token": "anything"})
    assert r.status_code == 404


async def test_new_email_creates_and_links(client, suite_enabled):
    email = "brandnew@example.com"
    assert await _count_users(email) == 0
    r = await client.post("/auth/suite", json={"suite_token": _suite_token(email)})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["access_token"] and body["refresh_token"]
    assert await _count_users(email) == 1
    # Second suite login for the same email must reuse the account, not duplicate it.
    r = await client.post("/auth/suite", json={"suite_token": _suite_token(email)})
    assert r.status_code == 200
    assert await _count_users(email) == 1


async def test_links_to_existing_password_account(client, suite_enabled):
    email = "existing@example.com"
    reg = await client.post(
        "/auth/register",
        json={"name": "Existing", "email": email, "password": "Password123!"},
    )
    assert reg.status_code == 201, reg.text
    assert await _count_users(email) == 1
    # Suite login for the same email links to the existing account — no new row.
    r = await client.post("/auth/suite", json={"suite_token": _suite_token(email)})
    assert r.status_code == 200
    assert await _count_users(email) == 1


async def test_wrong_issuer_rejected(client, suite_enabled):
    r = await client.post(
        "/auth/suite", json={"suite_token": _suite_token("x@example.com", iss="http://evil")}
    )
    assert r.status_code == 401


async def test_wrong_audience_rejected(client, suite_enabled):
    r = await client.post(
        "/auth/suite", json={"suite_token": _suite_token("y@example.com", aud="not-suite")}
    )
    assert r.status_code == 401


async def test_garbage_token_rejected(client, suite_enabled):
    r = await client.post("/auth/suite", json={"suite_token": "not-a-jwt"})
    assert r.status_code == 401
