"""Pantry operations: what's on hand (scan-confirmed or manual), always-available staples,
and "what can I make?" suggestions.

Matching decisions delegate to the pure module :mod:`app.lists.pantry_match`; this service
only does I/O around it. Item identity is the normalized name (:func:`merge_key`, the
shopping-list precedent) — re-adding "Eggs" updates the existing "eggs" row instead of
duplicating it.
"""

import datetime
import logging
import uuid

import httpx
from fastapi import HTTPException, status
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.lists.categorize import guess_category
from app.lists.merge import merge_key
from app.lists.pantry_match import match_recipes
from app.models.pantry import PantryItem, PantryStaple
from app.models.recipe import Recipe
from app.models.user import User
from app.schemas.pantry import (
    CookbookSuggestion,
    ExternalSuggestion,
    PantryConfirmRequest,
    PantryItemIn,
    PantryItemUpdate,
    PantrySuggestionsOut,
    StaplesOut,
    StaplesPut,
)
from app.services.recipe_discovery_service import _build_source

log = logging.getLogger(__name__)

# Seeded staples: things almost every kitchen keeps that a fridge photo won't show.
# Deliberately excludes meal-defining items (rice, onions, eggs) — assuming those would
# suggest recipes the user can't actually make.
DEFAULT_STAPLES = [
    "salt",
    "black pepper",
    "olive oil",
    "vegetable oil",
    "butter",
    "all-purpose flour",
    "sugar",
    "brown sugar",
    "garlic",
    "baking powder",
    "baking soda",
    "vanilla extract",
    "soy sauce",
    "vinegar",
    "honey",
    "ketchup",
    "mustard",
    "mayonnaise",
    "dried oregano",
    "cumin",
    "paprika",
    "cinnamon",
]

MAX_EXTERNAL_SUGGESTIONS = 10
MAX_MISSING_ALLOWED = 5


async def list_pantry(db: AsyncSession, user_id: uuid.UUID) -> list[PantryItem]:
    result = await db.execute(
        select(PantryItem)
        .where(PantryItem.user_id == user_id)
        .order_by(PantryItem.category.nulls_last(), PantryItem.name)
    )
    return list(result.scalars())


async def _find_by_key(db: AsyncSession, user_id: uuid.UUID, name: str) -> PantryItem | None:
    """The row this name merges onto, if any — identity is the normalized name."""
    key = merge_key(name)
    for item in await list_pantry(db, user_id):
        if merge_key(item.name) == key:
            return item
    return None


async def add_item(
    db: AsyncSession, user_id: uuid.UUID, req: PantryItemIn, *, source: str = "manual"
) -> PantryItem:
    existing = await _find_by_key(db, user_id, req.name)
    if existing is not None:
        # Same item, maybe better metadata — update rather than duplicate.
        if req.category is not None:
            existing.category = req.category
        existing.updated_at = datetime.datetime.now(datetime.UTC)
        await db.commit()
        await db.refresh(existing)
        return existing
    item = PantryItem(
        user_id=user_id,
        name=req.name,
        category=req.category or guess_category(req.name),
        source=source,
    )
    db.add(item)
    await db.commit()
    await db.refresh(item)
    return item


async def confirm_items(
    db: AsyncSession, user_id: uuid.UUID, req: PantryConfirmRequest
) -> list[PantryItem]:
    """The confirmation screen's bulk write. ``replace`` swaps the whole pantry for this
    batch (fresh full scan); otherwise the batch merges into what's there."""
    if req.replace:
        await db.execute(delete(PantryItem).where(PantryItem.user_id == user_id))
        await db.flush()

    existing_by_key = {merge_key(i.name): i for i in await list_pantry(db, user_id)}
    seen_batch: set[str] = set()
    for incoming in req.items:
        key = merge_key(incoming.name)
        if key in seen_batch:
            continue
        seen_batch.add(key)
        current = existing_by_key.get(key)
        if current is not None:
            if incoming.category is not None:
                current.category = incoming.category
            current.updated_at = datetime.datetime.now(datetime.UTC)
            continue
        db.add(
            PantryItem(
                user_id=user_id,
                name=incoming.name,
                category=incoming.category or guess_category(incoming.name),
                source="scan",
            )
        )
    await db.commit()
    return await list_pantry(db, user_id)


async def _load_owned_item(db: AsyncSession, user_id: uuid.UUID, item_id: uuid.UUID) -> PantryItem:
    item = await db.get(PantryItem, item_id)
    if item is None or item.user_id != user_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Item not found")
    return item


async def update_item(
    db: AsyncSession, user_id: uuid.UUID, item_id: uuid.UUID, req: PantryItemUpdate
) -> PantryItem:
    item = await _load_owned_item(db, user_id, item_id)
    if req.name is not None:
        item.name = req.name
    if req.category is not None:
        item.category = req.category
    item.updated_at = datetime.datetime.now(datetime.UTC)
    await db.commit()
    await db.refresh(item)
    return item


async def delete_item(db: AsyncSession, user_id: uuid.UUID, item_id: uuid.UUID) -> None:
    item = await _load_owned_item(db, user_id, item_id)
    await db.delete(item)
    await db.commit()


async def _staple_rows(db: AsyncSession, user_id: uuid.UUID) -> list[PantryStaple]:
    result = await db.execute(
        select(PantryStaple).where(PantryStaple.user_id == user_id).order_by(PantryStaple.name)
    )
    return list(result.scalars())


async def get_staples(db: AsyncSession, user: User) -> StaplesOut:
    if user.staples_confirmed_at is None:
        return StaplesOut(confirmed=False, staples=DEFAULT_STAPLES)
    return StaplesOut(confirmed=True, staples=[s.name for s in await _staple_rows(db, user.id)])


async def put_staples(db: AsyncSession, user: User, req: StaplesPut) -> StaplesOut:
    """Wholesale replace (the Settings editor saves the full list) + first-use confirm."""
    await db.execute(delete(PantryStaple).where(PantryStaple.user_id == user.id))
    seen: set[str] = set()
    for name in req.staples:
        key = merge_key(name)
        if key in seen:
            continue
        seen.add(key)
        db.add(PantryStaple(user_id=user.id, name=name))
    if user.staples_confirmed_at is None:
        user.staples_confirmed_at = datetime.datetime.now(datetime.UTC)
    await db.commit()
    return await get_staples(db, user)


async def _staple_names(db: AsyncSession, user: User) -> list[str]:
    """Staples for matching: the seeded defaults still count before first confirmation —
    an unconfirmed list shouldn't mean 'no staples' and hide every suggestion."""
    if user.staples_confirmed_at is None:
        return DEFAULT_STAPLES
    return [s.name for s in await _staple_rows(db, user.id)]


async def get_suggestions(
    db: AsyncSession,
    user: User,
    *,
    max_missing: int = 2,
    source=None,
) -> PantrySuggestionsOut:
    """Recipes makeable from the pantry: saved Cookbook recipes matched locally, plus
    Spoonacular findByIngredients when configured. ``source`` is injectable for tests."""
    pantry = await list_pantry(db, user.id)
    if not pantry:
        return PantrySuggestionsOut()
    pantry_names = [i.name for i in pantry]
    staple_names = await _staple_names(db, user)

    result = await db.execute(select(Recipe).where(Recipe.user_id == user.id))
    recipes = [
        (r.id, r.name, r.image_url, [i.name for i in r.ingredients]) for r in result.scalars()
    ]
    matches = match_recipes(recipes, pantry_names, staple_names, max_missing=max_missing)
    cookbook = [
        CookbookSuggestion(
            recipe_id=m.recipe_id,
            name=m.name,
            image_url=m.image_url,
            total=m.total,
            matched=m.matched,
            missing=m.missing,
        )
        for m in matches
    ]

    # Searched names: actual pantry items first, staples filling the remaining slots — the
    # provider caps at 20 and the fridge contents matter more than the spice rack.
    search_names = pantry_names + staple_names
    external: list[ExternalSuggestion] = []
    external_available = False
    try:
        if source is not None:
            hits = await source.find_by_ingredients(search_names, limit=MAX_EXTERNAL_SUGGESTIONS)
        elif settings.spoonacular_api_key:
            async with httpx.AsyncClient(timeout=settings.external_timeout_seconds) as client:
                hits = await _build_source(client).find_by_ingredients(
                    search_names, limit=MAX_EXTERNAL_SUGGESTIONS
                )
        else:
            hits = None
        if hits is not None:
            external = [
                ExternalSuggestion(
                    source_id=h.source_id,
                    title=h.title,
                    image=h.image,
                    used_count=h.used_count,
                    missed_count=h.missed_count,
                    missing=h.missing,
                )
                for h in hits
            ]
            external_available = True
    except httpx.HTTPError as exc:
        # External ideas are a bonus, never a blocker — local matches still return.
        log.info("pantry suggestions: external lookup failed: %s", exc)

    return PantrySuggestionsOut(
        cookbook=cookbook, external=external, external_available=external_available
    )
