"""One-time recipe migration from Plate (CLAUDE.md §6, Phase 6).

Cookbook mints a short-lived cross-app JWT (typed ``cross_app``, carrying the signed-in user's
email — the only identity shared across the apps' independent user tables), calls Plate's
read-only ``GET /recipes/export``, and imports each hit as a ``source='plate'`` recipe with
free-text ingredients derived from the food names + amounts. Idempotent: recipes whose
``source_id`` already exists for this user are skipped, so re-running is safe.
"""

import logging
from datetime import datetime, timedelta, timezone

import httpx
from fastapi import HTTPException, status
from jose import jwt
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.limits import MAX_RECIPE_INGREDIENTS, QUANTITY_BOUNDS
from app.models.recipe import Recipe
from app.schemas.recipe import IngredientIn, RecipeCreate
from app.services.recipe_service import create_recipe

log = logging.getLogger(__name__)

_DISABLED = HTTPException(
    status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
    detail="Plate integration is not configured (set PLATE_BASE_URL and CROSS_APP_SECRET).",
)


class PlateMigrationResult(BaseModel):
    imported: int
    skipped: int


def mint_cross_app_token(email: str) -> str:
    """The token Plate's ``get_cross_app_user`` validates — same shape Plate mints for Spotter."""
    expire = datetime.now(timezone.utc) + timedelta(seconds=settings.cross_app_token_ttl_seconds)
    return jwt.encode(
        {"email": email, "type": "cross_app", "exp": expire},
        settings.cross_app_secret,
        algorithm=settings.algorithm,
    )


async def _fetch_exports(email: str, client: httpx.AsyncClient) -> list[dict]:
    url = settings.plate_base_url.rstrip("/") + "/recipes/export"
    resp = await client.get(
        url, headers={"Authorization": f"Bearer {mint_cross_app_token(email)}"}
    )
    if resp.status_code == 401:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Plate rejected the cross-app token (secrets out of sync?).",
        )
    resp.raise_for_status()
    return resp.json()


async def migrate_from_plate(
    db: AsyncSession,
    user,
    *,
    client: httpx.AsyncClient | None = None,
) -> PlateMigrationResult:
    """Pull the user's Plate recipes across. ``client`` is injectable for tests."""
    if not settings.plate_base_url or not settings.cross_app_secret:
        raise _DISABLED

    if client is not None:
        exports = await _fetch_exports(user.email, client)
    else:
        async with httpx.AsyncClient(timeout=settings.plate_timeout_seconds) as owned:
            exports = await _fetch_exports(user.email, owned)

    existing = await db.execute(
        select(Recipe.source_id).where(Recipe.user_id == user.id, Recipe.source == "plate")
    )
    already = {row for (row,) in existing.all() if row}

    imported = 0
    skipped = 0
    for export in exports:
        source_id = str(export.get("id") or "")
        name = (export.get("name") or "").strip()
        if not source_id or not name:
            skipped += 1
            continue
        if source_id in already:
            skipped += 1
            continue
        ingredients = []
        for item in (export.get("items") or [])[:MAX_RECIPE_INGREDIENTS]:
            food_name = (item.get("food_name") or "").strip()
            if not food_name:
                continue
            quantity = item.get("quantity")
            if not isinstance(quantity, (int, float)) or quantity <= QUANTITY_BOUNDS[0]:
                quantity = None
            elif quantity > QUANTITY_BOUNDS[1]:
                quantity = QUANTITY_BOUNDS[1]
            unit = (item.get("unit") or "").strip().lower() or None
            ingredients.append(
                IngredientIn(name=food_name[:255], quantity=quantity, unit=unit)
            )
        req = RecipeCreate(
            name=name[:255],
            description=export.get("description"),
            ingredients=ingredients,
        )
        await create_recipe(db, user.id, req, source="plate", source_id=source_id)
        already.add(source_id)
        imported += 1

    return PlateMigrationResult(imported=imported, skipped=skipped)
