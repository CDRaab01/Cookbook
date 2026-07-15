"""Weekly meal planner (v0.3): decide → shop → cook, closed into one loop.

Entries are lightweight — a recipe or a note on a date+slot. The payoff is
:func:`plan_to_list`: every planned recipe in a range lands on a shopping list through the
same merge math as a manual add, so a week of dinners becomes one aggregated list.
"""

import datetime
import logging
import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.lists.merge import IncomingItem, merge_incoming, scale_quantity
from app.models.meal_confirmation import MealConfirmation
from app.models.meal_plan import MealPlanEntry
from app.models.recipe import Recipe
from app.schemas.plan import PlanEntryCreate, PlanEntryOut, PlanToListRequest, PlanToListResult
from app.services.recipe_service import load_owned_recipe
from app.services.shopping_service import (
    _guard_capacity,
    _merge_into_list,
    _record_history,
    get_default_list,
    load_accessible_list,
)

log = logging.getLogger(__name__)

MAX_RANGE_DAYS = 31


def _to_out(entry: MealPlanEntry, confirmation: MealConfirmation | None = None) -> PlanEntryOut:
    # `eaten`/`servings` are the requesting user's own (per-user confirmation), NOT the retired
    # shared `entry.eaten` column. No confirmation = not yet eaten, default one serving.
    return PlanEntryOut(
        id=entry.id,
        date=entry.date,
        slot=entry.slot,
        recipe_id=entry.recipe_id,
        recipe_name=entry.recipe.name if entry.recipe is not None else None,
        recipe_image_url=entry.recipe.image_url if entry.recipe is not None else None,
        note=entry.note,
        scale=entry.scale,
        eaten=bool(confirmation and confirmation.eaten),
        servings=confirmation.servings if confirmation else 1.0,
    )


async def _confirmations_for(
    db: AsyncSession, user_id: uuid.UUID, entry_ids: list[uuid.UUID]
) -> dict[uuid.UUID, MealConfirmation]:
    """This user's confirmations for the given entries, keyed by entry_id (for per-user eaten)."""
    if not entry_ids:
        return {}
    result = await db.execute(
        select(MealConfirmation).where(
            MealConfirmation.user_id == user_id,
            MealConfirmation.entry_id.in_(entry_ids),
        )
    )
    return {c.entry_id: c for c in result.scalars().all()}


def _validate_range(start: datetime.date, end: datetime.date) -> None:
    if end < start or (end - start).days > MAX_RANGE_DAYS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Date range must run forward and span at most {MAX_RANGE_DAYS} days.",
        )


async def _resolve_plan_list(
    db: AsyncSession, user_id: uuid.UUID, list_id: uuid.UUID | None
) -> uuid.UUID:
    """The list a plan is for: the given one (access-checked, so members can plan too) or, when
    unspecified, the caller's own default list. This is what makes a shared list's plan collaborative."""
    if list_id is None:
        return (await get_default_list(db, user_id)).id
    await load_accessible_list(db, user_id, list_id)  # owner or member
    return list_id


async def get_plan(
    db: AsyncSession,
    user_id: uuid.UUID,
    start: datetime.date,
    end: datetime.date,
    *,
    list_id: uuid.UUID | None = None,
) -> list[PlanEntryOut]:
    _validate_range(start, end)
    plan_list = await _resolve_plan_list(db, user_id, list_id)
    result = await db.execute(
        select(MealPlanEntry)
        .where(
            MealPlanEntry.list_id == plan_list,
            MealPlanEntry.date >= start,
            MealPlanEntry.date <= end,
        )
        .order_by(MealPlanEntry.date, MealPlanEntry.created_at)
    )
    entries = list(result.scalars().all())
    confs = await _confirmations_for(db, user_id, [e.id for e in entries])
    return [_to_out(e, confs.get(e.id)) for e in entries]


async def add_entry(
    db: AsyncSession,
    user_id: uuid.UUID,
    req: PlanEntryCreate,
    *,
    list_id: uuid.UUID | None = None,
) -> PlanEntryOut:
    if (req.recipe_id is None) == (req.note is None):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Provide exactly one of recipe_id or note.",
        )
    plan_list = await _resolve_plan_list(db, user_id, list_id)
    if req.recipe_id is not None:
        await load_owned_recipe(db, user_id, req.recipe_id)  # ownership gate
    entry = MealPlanEntry(
        user_id=user_id,
        list_id=plan_list,
        date=req.date,
        slot=req.slot,
        recipe_id=req.recipe_id,
        note=req.note,
        scale=req.scale,
    )
    db.add(entry)
    await db.commit()
    result = await db.execute(select(MealPlanEntry).where(MealPlanEntry.id == entry.id))
    return _to_out(result.scalar_one())


async def _load_recipe(db: AsyncSession, recipe_id: uuid.UUID) -> Recipe:
    """A recipe with its ingredients, WITHOUT the ownership gate — access is already established by
    plan-list membership, so a household member can log the planner's recipe into their own diary."""
    result = await db.execute(select(Recipe).where(Recipe.id == recipe_id))
    return result.scalar_one()


async def _sync_confirmation_to_plate(
    user,
    conf: MealConfirmation,
    *,
    eaten: bool,
    recipe: Recipe | None,
    entry_date: datetime.date,
    slot: str,
    ref: str,
) -> str | None:
    """Reflect a confirmation in the user's Plate diary and return the plate_ref it should carry.
    Best-effort and NETWORK-ONLY (no DB writes): on any failure we log and leave the ref unchanged,
    so a Plate outage never blocks confirming a meal. Returns the new plate_ref (or the old one)."""
    from app.services import plate_nutrition_service as plate

    if not plate.plate_enabled():
        return conf.plate_ref
    try:
        if eaten and recipe is not None:
            # Portion change = retract the old log, then re-log at the new servings (same ref).
            if conf.plate_ref:
                await plate.retract_confirmation_log(user.email, conf.plate_ref)
            await plate.log_recipe_for_confirmation(
                user.email,
                recipe,
                servings_eaten=conf.servings,
                date=entry_date,
                meal=slot,
                client_ref=ref,
            )
            return ref
        if not eaten and conf.plate_ref:
            # Un-eat: retract the diary entries this confirmation created.
            await plate.retract_confirmation_log(user.email, conf.plate_ref)
            return None
    except Exception as e:  # noqa: BLE001 — diary sync is best-effort; the confirmation still stands
        log.warning("Plate diary sync for confirmation %s failed: %s", ref, e)
    return conf.plate_ref


async def set_eaten(
    db: AsyncSession, user, entry_id: uuid.UUID, eaten: bool, servings: float = 1.0
) -> PlanEntryOut:
    """Confirm (or un-confirm) that THIS user ate a planned meal, at ``servings`` — per-user, so a
    shared plan tracks each member's own eating. A recipe confirmation also logs to (or, on un-eat,
    retracts from) this user's Plate diary. Any member of the entry's list may confirm their own."""
    entry = await db.get(MealPlanEntry, entry_id)
    if entry is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Entry not found")
    await load_accessible_list(db, user.id, entry.list_id)  # owner or member
    # Capture the scalars the Plate sync needs before any commit expires the instance.
    recipe_id = entry.recipe_id
    entry_date = entry.date
    slot = entry.slot

    result = await db.execute(
        select(MealConfirmation).where(
            MealConfirmation.entry_id == entry_id,
            MealConfirmation.user_id == user.id,
        )
    )
    conf = result.scalar_one_or_none()
    if conf is None:
        conf = MealConfirmation(entry_id=entry_id, user_id=user.id, eaten=eaten, servings=servings)
        db.add(conf)
    else:
        conf.eaten = eaten
        if eaten:
            conf.servings = servings

    # A stable, per-(entry,user) correlation ref so a re-log lands on the same Plate rows.
    ref = f"cbplan:{entry_id}:{user.id}"
    recipe = await _load_recipe(db, recipe_id) if (eaten and recipe_id is not None) else None
    conf.plate_ref = await _sync_confirmation_to_plate(
        user, conf, eaten=eaten, recipe=recipe, entry_date=entry_date, slot=slot, ref=ref
    )
    # Snapshot the per-user values before commit expires `conf` (reading them after would trigger
    # an async lazy refresh — MissingGreenlet).
    eaten_final, servings_final = conf.eaten, conf.servings
    await db.commit()

    # Re-query the entry FRESH so its selectin `recipe` relationship fires (avoids a MissingGreenlet
    # async lazy-load in _to_out for recipe entries), exactly like get_plan.
    db.expunge(entry)
    fresh = await db.execute(select(MealPlanEntry).where(MealPlanEntry.id == entry_id))
    out = _to_out(fresh.scalar_one(), None)
    out.eaten, out.servings = eaten_final, servings_final
    return out


async def delete_entry(db: AsyncSession, user_id: uuid.UUID, entry_id: uuid.UUID) -> None:
    entry = await db.get(MealPlanEntry, entry_id)
    if entry is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Entry not found")
    await load_accessible_list(db, user_id, entry.list_id)  # owner or member
    await db.delete(entry)
    await db.commit()


async def plan_to_list(
    db: AsyncSession, user_id: uuid.UUID, req: PlanToListRequest
) -> PlanToListResult:
    """Every planned recipe in the range → one merged add onto that plan's own list. A recipe
    planned on two nights contributes twice (you're cooking it twice). The plan and its target list
    are the same household list."""
    _validate_range(req.start, req.end)
    plan_list = await _resolve_plan_list(db, user_id, req.list_id)
    result = await db.execute(
        select(MealPlanEntry).where(
            MealPlanEntry.list_id == plan_list,
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

    shopping_list = await load_accessible_list(db, user_id, plan_list)

    incoming: list[IncomingItem] = []
    for entry in entries:
        recipe = entry.recipe
        if recipe is None:
            continue
        # Combine the request's global scale with this entry's own cooking scale, so "make the whole
        # week 2×" and "make just the chili 2×" compose.
        entry_scale = req.scale * entry.scale
        for ing in recipe.ingredients:
            incoming.append(
                IncomingItem(
                    name=ing.name,
                    quantity=scale_quantity(ing.quantity, entry_scale),
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
