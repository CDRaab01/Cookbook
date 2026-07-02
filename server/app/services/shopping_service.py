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
from app.lists.merge import IncomingItem, combine_quantities, merge_incoming, merge_key, scale_quantity
from app.models.shopping_list import ShoppingList, ShoppingListItem
from app.schemas.shopping import AddRecipeRequest, ItemCreate, ItemUpdate, ListOut
from app.services.recipe_service import load_owned_recipe

DEFAULT_LIST_NAME = "Groceries"


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


async def load_owned_list(
    db: AsyncSession, user_id: uuid.UUID, list_id: uuid.UUID
) -> ShoppingList:
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
        merge_key(item.name, item.unit): item
        for item in shopping_list.items
        if not item.checked
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


async def get_list_out(db: AsyncSession, user_id: uuid.UUID) -> ListOut:
    shopping_list = await get_default_list(db, user_id)
    return ListOut.model_validate(shopping_list)


async def add_item(
    db: AsyncSession, user_id: uuid.UUID, list_id: uuid.UUID, req: ItemCreate
) -> ListOut:
    shopping_list = await load_owned_list(db, user_id, list_id)
    _guard_capacity(shopping_list, adding=1)
    _merge_into_list(
        shopping_list,
        [IncomingItem(name=req.name, quantity=req.quantity, unit=req.unit, category=req.category)],
        recipe_id=None,
    )
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
