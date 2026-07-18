import asyncio
import os
import uuid

# Must be set before any `app.*` import: the engine is built at app.database import time.
# NullPool keeps pooled asyncpg connections from binding to a single event loop, which is the
# failure mode that plagued Plate's suite locally ("attached to a different loop").
os.environ.setdefault("DB_NULLPOOL", "true")

import pytest
import pytest_asyncio
import sqlalchemy as sa
from httpx import ASGITransport, AsyncClient

from app.database import Base, engine
from app.limiter import limiter
from app.main import app
from app.security import pwd_context

# Disable rate limiting for the test suite so rapid registrations don't
# trigger the 5/minute cap on /auth/register.
limiter.enabled = False

# Cheap hashing for tests only: nearly every test registers a fresh user, and bcrypt at
# production cost dominates the suite's runtime. Security-irrelevant against a throwaway DB.
pwd_context.update(bcrypt__rounds=4)


@pytest.fixture(scope="session")
def event_loop():
    """Share a single event loop across the whole test session."""
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()


@pytest_asyncio.fixture(scope="session", autouse=True)
async def setup_tables():
    """Ensure all tables exist before any test runs (safe to call after alembic)."""
    async with engine.begin() as conn:
        # The suite builds schema with create_all, not migrations, so the fuzzy-suggestion
        # extensions (added in migration 0016) must be created here or similarity()/levenshtein()
        # are undefined.
        await conn.execute(sa.text("CREATE EXTENSION IF NOT EXISTS pg_trgm"))
        await conn.execute(sa.text("CREATE EXTENSION IF NOT EXISTS fuzzystrmatch"))
        await conn.run_sync(Base.metadata.create_all)
    await engine.dispose()
    yield


@pytest_asyncio.fixture
async def client():
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as c:
        yield c


@pytest_asyncio.fixture
async def auth_client(client):
    """HTTP client pre-authenticated as a fresh unique test user."""
    uid = uuid.uuid4().hex[:8]
    resp = await client.post(
        "/auth/register",
        json={
            "name": "Test User",
            "email": f"test_{uid}@cookbook.com",
            "password": "Testpass123!",
        },
    )
    assert resp.status_code == 201, resp.text
    token = resp.json()["access_token"]
    client.headers["Authorization"] = f"Bearer {token}"
    return client
