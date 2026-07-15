"""Weekly meal planner (v0.3): decide → shop → cook, closed into one loop.

Entries are lightweight — a recipe or a note on a date+slot. The payoff is
:func:`plan_to_list`: every planned recipe in a range lands on a shopping list through the
same merge math as a manual add, so a week of dinners becomes one aggregated list.
"""

import datetime
import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.lists.merge import IncomingItem, merge_incoming, scale_quantity
from app.models.meal_plan import MealPlanEntry
from app.schemas.plan import PlanEntryCreate, PlanEntryOut, PlanToListRequest, PlanToListResult
from app.services.recipe_service import load_owned_recipe
from app.services.shopping_service import (
    _guard_capacity,
    _merge_into_list,
    _record_history,
    get_default_list,
    load_accessible_list,
)

MAX_RANGE_DAYS = 31


def _to_out(entry: MealPlanEntry) -> PlanEntryOut:
    return PlanEntryOut(
        id=entry.id,
        date=entry.date,
        slot=entry.slot,
        recipe_id=entry.recipe_id,
        recipe_name=entry.recipe.name if entry.recipe is not None else None,
        recipe_image_url=entry.recipe.image_url if entry.recipe is not None else None,
        note=entry.note,
        eaten=entry.eaten,
    )


def _validate_range(start: datetime.date, end: datetime.date) -> None:
    if end < start or (end - start).days > MAX_RANGE_DAYS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Date range must run forward and span at most {MAX_RANGE_DAYS} days.",
        )


async def get_plan(
    db: AsyncSession, user_id: uuid.UUID, start: datetime.date, end: datetime.date
) -> list[PlanEntryOut]:
    _validate_range(start, end)
    result = await db.execute(
        select(MealPlanEntry)
        .where(
            MealPlanEntry.user_id == user_id,
            MealPlanEntry.date >= start,
            MealPlanEntry.date <= end,
        )
        .order_by(MealPlanEntry.date, MealPlanEntry.created_at)
    )
    return [_to_out(e) for e in result.scalars().all()]


async def add_entry(db: AsyncSession, user_id: uuid.UUID, req: PlanEntryCreate) -> PlanEntryOut:
    if (req.recipe_id is None) == (req.note is None):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Provide exactly one of recipe_id or note.",
        )
    if req.recipe_id is not None:
        await load_owned_recipe(db, user_id, req.recipe_id)  # ownership gate
    entry = MealPlanEntry(
        user_id=user_id,
        date=req.date,
        slot=req.slot,
        recipe_id=req.recipe_id,
        note=req.note,
    )
    db.add(entry)
    await db.commit()
    result = await db.execute(select(MealPlanEntry).where(MealPlanEntry.id == entry.id))
    return _to_out(result.scalar_one())


async def set_eaten(
    db: AsyncSession, user_id: uuid.UUID, entry_id: uuid.UUID, eaten: bool
) -> PlanEntryOut:
    """Mark a planned meal eaten (or un-eat it). Ownership-checked; returns the updated entry."""
    entry = await db.get(MealPlanEntry, entry_id)
    if entry is None or entry.user_id != user_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Entry not found")
    entry.eaten = eaten
    await db.commit()
    # Expunge then re-query so the entry loads FRESH (not from the identity map) and its selectin
    # `recipe` relationship fires — exactly like get_plan. A re-query of the still-mapped instance
    # returns it with `recipe` unloaded, and accessing it in _to_out would trigger an async lazy
    # load (MissingGreenlet) for recipe entries.
    db.expunge(entry)
    result = await db.execute(select(MealPlanEntry).where(MealPlanEntry.id == entry_id))
    return _to_out(result.scalar_one())


async def delete_entry(db: AsyncSession, user_id: uuid.UUID, entry_id: uuid.UUID) -> None:
    entry = await db.get(MealPlanEntry, entry_id)
    if entry is None or entry.user_id != user_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Entry not found")
    await db.delete(entry)
    await db.commit()


async def plan_to_list(
    db: AsyncSession, user_id: uuid.UUID, req: PlanToListRequest
) -> PlanToListResult:
    """Every planned recipe in the range → one merged shopping-list add. A recipe planned on
    two nights contributes twice (you're cooking it twice)."""
    _validate_range(req.start, req.end)
    result = await db.execute(
        select(MealPlanEntry).where(
            MealPlanEntry.user_id == user_id,
            MealPlanEntry.date >= req.start,
            MealPlanEntry.date <= req.end,
            MealPlanEntry.recipe_id.is_not(None),
        )
    )
    entries = list(result.scalars().all())
    if not entries:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="No planned recipes in that range.",
        )

    if req.list_id is not None:
        shopping_list = await load_accessible_list(db, user_id, req.list_id)
    else:
        shopping_list = await get_default_list(db, user_id)

    incoming: list[IncomingItem] = []
    for entry in entries:
        recipe = entry.recipe
        if recipe is None:
            continue
        for ing in recipe.ingredients:
            incoming.append(
                IncomingItem(
                    name=ing.name,
                    quantity=scale_quantity(ing.quantity, req.scale),
                    unit=ing.unit,
                    category=ing.category,
                )
            )
    merged = merge_incoming(incoming)
    if not merged:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Nothing on those recipes needs buying.",
        )
    _guard_capacity(shopping_list, adding=len(merged))
    # Multi-recipe by construction — provenance stays NULL rather than lying.
    _merge_into_list(shopping_list, merged, recipe_id=None)
    await _record_history(db, user_id, merged)
    await db.commit()

    refreshed = await load_accessible_list(db, user_id, shopping_list.id)
    return PlanToListResult(
        recipes_added=len(entries),
        items_on_list=len(refreshed.items),
        list_id=shopping_list.id,
    )
