"""Shopping-list operations (CLAUDE.md §4, §6, Phase 3).

All merge decisions delegate to the pure module :mod:`app.lists.merge`; this service only does
I/O around it. Merging only ever targets **unchecked** items — a checked-off item is history for
this trip, and folding new needs into it would silently hide them.
"""

import datetime
import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.limits import MAX_LIST_ITEMS
from app.lists.categorize import guess_category
from app.lists.merge import (
    IncomingItem,
    combine_quantities,
    merge_incoming,
    merge_key,
    normalize_name,
    scale_quantity,
)
from app.models.item_history import ItemHistory
from app.models.shopping_list import ShoppingList, ShoppingListItem
from app.schemas.shopping import AddRecipeRequest, ItemCreate, ItemUpdate, ListOut, SuggestionOut
from app.services.recipe_service import load_owned_recipe

DEFAULT_LIST_NAME = "Groceries"
MAX_SUGGESTIONS = 8


async def _reload(db: AsyncSession, list_id: uuid.UUID) -> ShoppingList:
    """Re-fetch with items eagerly loaded (the relationship is selectin)."""
    result = await db.execute(select(ShoppingList).where(ShoppingList.id == list_id))
    return result.scalar_one()


async def get_default_list(db: AsyncSession, user_id: uuid.UUID) -> ShoppingList:
    """The user's default list, created on first touch (v1 UI shows exactly one)."""
    result = await db.execute(
        select(ShoppingList)
        .where(ShoppingList.user_id == user_id)
        .order_by(ShoppingList.created_at)
        .limit(1)
    )
    existing = result.scalar_one_or_none()
    if existing is not None:
        return existing
    created = ShoppingList(user_id=user_id, name=DEFAULT_LIST_NAME)
    db.add(created)
    await db.commit()
    return await _reload(db, created.id)


async def load_owned_list(db: AsyncSession, user_id: uuid.UUID, list_id: uuid.UUID) -> ShoppingList:
    shopping_list = await db.get(ShoppingList, list_id)
    if shopping_list is None or shopping_list.user_id != user_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="List not found")
    return shopping_list


def _next_order(shopping_list: ShoppingList) -> int:
    return max((i.order for i in shopping_list.items), default=-1) + 1


def _guard_capacity(shopping_list: ShoppingList, adding: int) -> None:
    if len(shopping_list.items) + adding > MAX_LIST_ITEMS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"A list holds at most {MAX_LIST_ITEMS} items.",
        )


def _merge_into_list(
    shopping_list: ShoppingList,
    incoming: list[IncomingItem],
    recipe_id: uuid.UUID | None,
) -> None:
    """Fold incoming rows into the list: sum into same-key unchecked items, append the rest."""
    unchecked_by_key = {
        merge_key(item.name, item.unit): item for item in shopping_list.items if not item.checked
    }
    order = _next_order(shopping_list)
    appended = 0
    for inc in incoming:
        key = merge_key(inc.name, inc.unit)
        target = unchecked_by_key.get(key)
        if target is not None:
            target.quantity = combine_quantities(target.quantity, inc.quantity)
            if target.category is None:
                target.category = inc.category
            # Multi-recipe provenance collapses to "manual/mixed" (NULL) rather than lying.
            if recipe_id is not None and target.recipe_id != recipe_id:
                target.recipe_id = None
        else:
            new_item = ShoppingListItem(
                name=inc.name,
                quantity=inc.quantity,
                unit=inc.unit,
                category=inc.category,
                recipe_id=recipe_id,
                order=order,
            )
            order += 1
            appended += 1
            shopping_list.items.append(new_item)
            unchecked_by_key[key] = new_item


async def _record_history(db: AsyncSession, user_id: uuid.UUID, items: list[IncomingItem]) -> None:
    """Upsert item_history rows (v0.2) — the memory behind autocomplete and category recall.

    Flushed with the caller's commit; per-key rows are unique per user, so a re-add just bumps
    the count and refreshes the stored spelling/unit/category to the latest use.
    """
    keys = {normalize_name(i.name): i for i in items if i.name.strip()}
    if not keys:
        return
    result = await db.execute(
        select(ItemHistory).where(ItemHistory.user_id == user_id, ItemHistory.key.in_(keys.keys()))
    )
    existing = {row.key: row for row in result.scalars().all()}
    now = datetime.datetime.now(datetime.timezone.utc)
    for key, item in keys.items():
        row = existing.get(key)
        if row is not None:
            row.use_count += 1
            row.last_used = now
            row.name = item.name
            if item.unit is not None:
                row.unit = item.unit
            if item.category is not None:
                row.category = item.category
        else:
            db.add(
                ItemHistory(
                    user_id=user_id,
                    key=key,
                    name=item.name,
                    unit=item.unit,
                    category=item.category,
                    last_used=now,
                )
            )


async def recall_category(db: AsyncSession, user_id: uuid.UUID, name: str) -> str | None:
    """The category to use for an uncategorized add: your history first, keyword guess second."""
    result = await db.execute(
        select(ItemHistory.category).where(
            ItemHistory.user_id == user_id, ItemHistory.key == normalize_name(name)
        )
    )
    remembered = result.scalar_one_or_none()
    return remembered or guess_category(name)


async def suggest_items(db: AsyncSession, user_id: uuid.UUID, q: str) -> list[SuggestionOut]:
    """Autocomplete for the add dialog: your own most-used items matching the prefix."""
    text = q.strip()
    if not text:
        return []
    result = await db.execute(
        select(ItemHistory)
        .where(ItemHistory.user_id == user_id, ItemHistory.name.ilike(f"%{text}%"))
        .order_by(ItemHistory.use_count.desc(), ItemHistory.last_used.desc())
        .limit(MAX_SUGGESTIONS)
    )
    return [
        SuggestionOut(name=row.name, unit=row.unit, category=row.category)
        for row in result.scalars().all()
    ]


async def get_list_out(db: AsyncSession, user_id: uuid.UUID) -> ListOut:
    shopping_list = await get_default_list(db, user_id)
    return ListOut.model_validate(shopping_list)


async def add_item(
    db: AsyncSession, user_id: uuid.UUID, list_id: uuid.UUID, req: ItemCreate
) -> ListOut:
    shopping_list = await load_owned_list(db, user_id, list_id)
    _guard_capacity(shopping_list, adding=1)
    # Uncategorized adds get auto-sorted: the category you used last time, else a keyword guess.
    category = req.category or await recall_category(db, user_id, req.name)
    incoming = IncomingItem(name=req.name, quantity=req.quantity, unit=req.unit, category=category)
    _merge_into_list(shopping_list, [incoming], recipe_id=None)
    await _record_history(db, user_id, [incoming])
    await db.commit()
    return ListOut.model_validate(await _reload(db, list_id))


async def add_recipe(
    db: AsyncSession, user_id: uuid.UUID, list_id: uuid.UUID, req: AddRecipeRequest
) -> ListOut:
    shopping_list = await load_owned_list(db, user_id, list_id)
    recipe = await load_owned_recipe(db, user_id, req.recipe_id)
    if not recipe.ingredients:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Recipe has no ingredients"
        )

    # "Adding the same recipe twice warns and offers re-add/skip" (CLAUDE.md §6): the warn is a
    # 409 the client turns into that dialog; force=true is the re-add.
    if not req.force:
        already = any(
            item.recipe_id == req.recipe_id and not item.checked for item in shopping_list.items
        )
        if already:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="This recipe's items are already on the list. Re-add to double up.",
            )

    incoming = merge_incoming(
        [
            IncomingItem(
                name=ing.name,
                quantity=scale_quantity(ing.quantity, req.scale),
                unit=ing.unit,
                category=ing.category,
                note=ing.note,
            )
            for ing in recipe.ingredients
        ]
    )
    _guard_capacity(shopping_list, adding=len(incoming))
    _merge_into_list(shopping_list, incoming, recipe_id=recipe.id)
    await _record_history(db, user_id, incoming)
    await db.commit()
    return ListOut.model_validate(await _reload(db, list_id))


async def update_item(
    db: AsyncSession,
    user_id: uuid.UUID,
    list_id: uuid.UUID,
    item_id: uuid.UUID,
    req: ItemUpdate,
) -> ListOut:
    shopping_list = await load_owned_list(db, user_id, list_id)
    item = next((i for i in shopping_list.items if i.id == item_id), None)
    if item is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Item not found")

    if req.name is not None:
        item.name = req.name
    if req.quantity is not None:
        item.quantity = req.quantity
    if req.unit is not None:
        item.unit = req.unit
    if req.category is not None:
        item.category = req.category
    if req.checked is not None and req.checked != item.checked:
        item.checked = req.checked
        item.checked_at = datetime.datetime.now(datetime.timezone.utc) if req.checked else None
    await db.commit()
    return ListOut.model_validate(await _reload(db, list_id))


async def delete_item(
    db: AsyncSession, user_id: uuid.UUID, list_id: uuid.UUID, item_id: uuid.UUID
) -> ListOut:
    shopping_list = await load_owned_list(db, user_id, list_id)
    item = next((i for i in shopping_list.items if i.id == item_id), None)
    if item is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Item not found")
    shopping_list.items.remove(item)  # delete-orphan removes the row
    await db.commit()
    return ListOut.model_validate(await _reload(db, list_id))


async def clear_checked(db: AsyncSession, user_id: uuid.UUID, list_id: uuid.UUID) -> ListOut:
    shopping_list = await load_owned_list(db, user_id, list_id)
    shopping_list.items = [i for i in shopping_list.items if not i.checked]
    await db.commit()
    return ListOut.model_validate(await _reload(db, list_id))
