"""Per-user data export (ROADMAP T3 #6) — the honest "what if I leave the ecosystem" backstop.

Gathers every row the signed-in user owns into one JSON-serializable document. Columns are
serialized generically (introspection over ``__table__.columns``) so this keeps working as the
schema grows; secret columns on the user row are redacted. Read-only, own-session auth.
"""

import datetime
import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.cook_event import CookEvent
from app.models.item_history import ItemHistory
from app.models.meal_plan import MealPlanEntry
from app.models.pantry import PantryItem, PantryStaple
from app.models.recipe import Recipe, RecipeIngredient, RecipeStep
from app.models.shopping_list import ShoppingList, ShoppingListItem

EXPORT_SCHEMA_VERSION = 1

# Never leave the server in an export — auth secrets, not user content.
_USER_REDACT = {"hashed_password", "reset_token", "reset_token_expires_at"}


def _jsonify(value):
    if isinstance(value, (datetime.datetime, datetime.date)):
        return value.isoformat()
    if isinstance(value, uuid.UUID):
        return str(value)
    return value  # str/int/float/bool/None + JSON columns (already list/dict)


def _row(obj, *, exclude: frozenset = frozenset()) -> dict:
    return {
        c.name: _jsonify(getattr(obj, c.name))
        for c in obj.__table__.columns
        if c.name not in exclude
    }


async def _all(db: AsyncSession, stmt) -> list:
    return list((await db.execute(stmt)).scalars().all())


async def build_export(db: AsyncSession, user) -> dict:
    """Assemble the full export document for ``user``. Children are fetched by parent id so the
    export is complete without relying on (raise-configured) ORM relationships."""
    recipes = await _all(db, select(Recipe).where(Recipe.user_id == user.id))
    recipe_ids = [r.id for r in recipes]
    lists = await _all(db, select(ShoppingList).where(ShoppingList.user_id == user.id))
    list_ids = [lst.id for lst in lists]

    steps = (
        await _all(db, select(RecipeStep).where(RecipeStep.recipe_id.in_(recipe_ids)))
        if recipe_ids
        else []
    )
    ingredients = (
        await _all(db, select(RecipeIngredient).where(RecipeIngredient.recipe_id.in_(recipe_ids)))
        if recipe_ids
        else []
    )
    items = (
        await _all(db, select(ShoppingListItem).where(ShoppingListItem.list_id.in_(list_ids)))
        if list_ids
        else []
    )

    return {
        "app": "cookbook",
        "schema_version": EXPORT_SCHEMA_VERSION,
        "exported_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "user": _row(user, exclude=frozenset(_USER_REDACT)),
        "recipes": [_row(r) for r in recipes],
        "recipe_steps": [_row(s) for s in steps],
        "recipe_ingredients": [_row(i) for i in ingredients],
        "shopping_lists": [_row(lst) for lst in lists],
        "shopping_list_items": [_row(i) for i in items],
        "meal_plan_entries": [
            _row(e) for e in await _all(db, select(MealPlanEntry).where(MealPlanEntry.user_id == user.id))
        ],
        "cook_events": [
            _row(e) for e in await _all(db, select(CookEvent).where(CookEvent.user_id == user.id))
        ],
        "item_history": [
            _row(h) for h in await _all(db, select(ItemHistory).where(ItemHistory.user_id == user.id))
        ],
        "pantry_items": [
            _row(p) for p in await _all(db, select(PantryItem).where(PantryItem.user_id == user.id))
        ],
        "pantry_staples": [
            _row(p) for p in await _all(db, select(PantryStaple).where(PantryStaple.user_id == user.id))
        ],
    }
