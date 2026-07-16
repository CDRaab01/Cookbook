"""Shopping-list operations (CLAUDE.md §4, §6, Phase 3; buyable-list semantics since v0.2.1).

All merge decisions delegate to the pure module :mod:`app.lists.merge`; this service only does
I/O around it. Merging only ever targets **unchecked** items — a checked-off item is history for
this trip, and folding new needs into it would silently hide them. Line-item identity is the
normalized *name*; amounts aggregate as measures ("2 tbsp + 2 tsp") rather than pretending to
sum across units.
"""

import datetime
import uuid

from fastapi import HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.limits import MAX_LIST_ITEMS
from app.lists.categorize import guess_category
from app.lists.merge import (
    IncomingItem,
    Measure,
    add_measure,
    canonical_unit,
    is_purchasable,
    merge_incoming,
    merge_key,
    normalize_name,
    scale_quantity,
)
from app.models.item_history import ItemHistory
from app.models.shopping_list import ListMember, ShoppingList, ShoppingListItem
from app.models.user import User
from app.schemas.shopping import (
    AddRecipeRequest,
    ItemCreate,
    ItemUpdate,
    ListCreate,
    ListOut,
    ListRename,
    ListSummaryOut,
    MemberOut,
    SuggestionOut,
)
from app.services.recipe_service import load_accessible_recipe

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
    """Owner-only access — for rename/delete/manage-members. Shopping actions use
    :func:`load_accessible_list` so shared members can edit too."""
    shopping_list = await db.get(ShoppingList, list_id)
    if shopping_list is None or shopping_list.user_id != user_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="List not found")
    return shopping_list


async def load_accessible_list(
    db: AsyncSession, user_id: uuid.UUID, list_id: uuid.UUID
) -> ShoppingList:
    """Access for the shopping actions: the user owns the list OR is a member (household sharing)."""
    shopping_list = await db.get(ShoppingList, list_id)
    if shopping_list is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="List not found")
    if shopping_list.user_id == user_id:
        return shopping_list
    member = await db.execute(
        select(ListMember).where(ListMember.list_id == list_id, ListMember.user_id == user_id)
    )
    if member.scalar_one_or_none() is not None:
        return shopping_list  # legacy per-list share
    # Family mode: household co-members share each other's lists (and their plans).
    from app.services.household_service import household_member_ids

    if shopping_list.user_id in await household_member_ids(db, user_id):
        return shopping_list
    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="List not found")


def _next_order(shopping_list: ShoppingList) -> int:
    return max((i.order for i in shopping_list.items), default=-1) + 1


def _guard_capacity(shopping_list: ShoppingList, adding: int) -> None:
    if len(shopping_list.items) + adding > MAX_LIST_ITEMS:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"A list holds at most {MAX_LIST_ITEMS} items.",
        )


def _item_measures(item: ShoppingListItem) -> list[Measure]:
    """The item's aggregate, reading legacy quantity/unit rows (pre-0003) transparently."""
    if item.measures:
        return [
            Measure(quantity=m["quantity"], unit=m.get("unit"))
            for m in item.measures
            if isinstance(m, dict) and isinstance(m.get("quantity"), (int, float))
        ]
    if item.quantity is not None:
        return [Measure(quantity=item.quantity, unit=canonical_unit(item.unit))]
    return []


def _store_measures(item: ShoppingListItem, measures: list[Measure]) -> None:
    """Write the aggregate back, keeping the legacy single-measure columns coherent."""
    item.measures = [{"quantity": m.quantity, "unit": m.unit} for m in measures]
    if len(measures) == 1:
        item.quantity = measures[0].quantity
        item.unit = measures[0].unit
    else:
        item.quantity = None
        item.unit = None


def _merge_into_list(
    shopping_list: ShoppingList,
    incoming: list[IncomingItem],
    recipe_id: uuid.UUID | None,
) -> None:
    """Fold incoming rows into the list: same-name unchecked items aggregate; the rest append."""
    unchecked_by_key = {
        merge_key(item.name): item for item in shopping_list.items if not item.checked
    }
    order = _next_order(shopping_list)
    for inc in incoming:
        key = merge_key(inc.name)
        target = unchecked_by_key.get(key)
        if target is not None:
            measures = _item_measures(target)
            for m in inc.measures:
                add_measure(measures, m.quantity, m.unit)
            _store_measures(target, measures)
            if target.category is None:
                target.category = inc.category
            # Multi-recipe provenance collapses to "manual/mixed" (NULL) rather than lying.
            if recipe_id is not None and target.recipe_id != recipe_id:
                target.recipe_id = None
        else:
            new_item = ShoppingListItem(
                name=inc.name,
                category=inc.category,
                recipe_id=recipe_id,
                order=order,
            )
            _store_measures(new_item, inc.measures)
            order += 1
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
        unit = item.measures[0].unit if item.measures else canonical_unit(item.unit)
        row = existing.get(key)
        if row is not None:
            row.use_count += 1
            row.last_used = now
            row.name = item.name
            if unit is not None:
                row.unit = unit
            if item.category is not None:
                row.category = item.category
        else:
            db.add(
                ItemHistory(
                    user_id=user_id,
                    key=key,
                    name=item.name,
                    unit=unit,
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


def _list_summary(shopping_list: ShoppingList, *, is_owner: bool, shared: bool) -> ListSummaryOut:
    unchecked = sum(1 for i in shopping_list.items if not i.checked)
    return ListSummaryOut(
        id=shopping_list.id,
        name=shopping_list.name,
        unchecked_count=unchecked,
        total_count=len(shopping_list.items),
        shared=shared,
        is_owner=is_owner,
    )


async def list_all_lists(db: AsyncSession, user_id: uuid.UUID) -> list[ListSummaryOut]:
    """Every list the user can see — their own (oldest/default first) then lists shared with them.
    Ensures the default exists on first touch."""
    await get_default_list(db, user_id)
    owned = (
        (
            await db.execute(
                select(ShoppingList)
                .where(ShoppingList.user_id == user_id)
                .order_by(ShoppingList.created_at)
            )
        )
        .scalars()
        .all()
    )
    shared_with_me = (
        (
            await db.execute(
                select(ShoppingList)
                .join(ListMember, ListMember.list_id == ShoppingList.id)
                .where(ListMember.user_id == user_id)
                .order_by(ShoppingList.created_at)
            )
        )
        .scalars()
        .all()
    )
    # Which of my OWN lists have members (so the picker can badge them "shared")?
    owned_ids = [sl.id for sl in owned]
    shared_owned_ids: set[uuid.UUID] = set()
    if owned_ids:
        shared_owned_ids = set(
            (await db.execute(select(ListMember.list_id).where(ListMember.list_id.in_(owned_ids))))
            .scalars()
            .all()
        )
    return [_list_summary(sl, is_owner=True, shared=sl.id in shared_owned_ids) for sl in owned] + [
        _list_summary(sl, is_owner=False, shared=True) for sl in shared_with_me
    ]


async def add_member_by_email(
    db: AsyncSession, owner_id: uuid.UUID, list_id: uuid.UUID, email: str
) -> "list[MemberOut]":
    """Invite a suite user (by their SSO email) to a list. Owner-only. Idempotent — re-inviting an
    existing member is a no-op. 404s an unknown email (they must have a suite account first)."""
    await load_owned_list(db, owner_id, list_id)  # owner gate
    normalized = email.strip().lower()
    invitee = (
        await db.execute(select(User).where(func.lower(User.email) == normalized))
    ).scalar_one_or_none()
    if invitee is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="No Dragonfly account uses that email. Ask them to sign in once, then share.",
        )
    if invitee.id == owner_id:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="You already own this list.",
        )
    existing = (
        await db.execute(
            select(ListMember).where(
                ListMember.list_id == list_id, ListMember.user_id == invitee.id
            )
        )
    ).scalar_one_or_none()
    if existing is None:
        db.add(ListMember(list_id=list_id, user_id=invitee.id))
        await db.commit()
    return await list_members(db, owner_id, list_id)


async def list_members(
    db: AsyncSession, user_id: uuid.UUID, list_id: uuid.UUID
) -> "list[MemberOut]":
    """Everyone on a list: the owner first, then members. Any member may view."""
    shopping_list = await load_accessible_list(db, user_id, list_id)
    owner = await db.get(User, shopping_list.user_id)
    members = (
        (
            await db.execute(
                select(User)
                .join(ListMember, ListMember.user_id == User.id)
                .where(ListMember.list_id == list_id)
                .order_by(ListMember.added_at)
            )
        )
        .scalars()
        .all()
    )
    out = [MemberOut(user_id=owner.id, email=owner.email, name=owner.name, is_owner=True)]
    out += [MemberOut(user_id=u.id, email=u.email, name=u.name, is_owner=False) for u in members]
    return out


async def remove_member(
    db: AsyncSession, user_id: uuid.UUID, list_id: uuid.UUID, member_id: uuid.UUID
) -> None:
    """Remove a member. The owner may remove anyone; a member may remove themselves (leave)."""
    shopping_list = await db.get(ShoppingList, list_id)
    if shopping_list is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="List not found")
    is_owner = shopping_list.user_id == user_id
    if not is_owner and member_id != user_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Only the owner can remove other members.",
        )
    row = (
        await db.execute(
            select(ListMember).where(ListMember.list_id == list_id, ListMember.user_id == member_id)
        )
    ).scalar_one_or_none()
    if row is not None:
        await db.delete(row)
        await db.commit()


async def create_list(db: AsyncSession, user_id: uuid.UUID, req: ListCreate) -> ListOut:
    # The default is "the oldest list" — materialize it first so a named list created before
    # the user's first /default touch can't accidentally become the default.
    await get_default_list(db, user_id)
    shopping_list = ShoppingList(user_id=user_id, name=req.name)
    db.add(shopping_list)
    await db.commit()
    return ListOut.model_validate(await _reload(db, shopping_list.id))


async def get_one_list(db: AsyncSession, user_id: uuid.UUID, list_id: uuid.UUID) -> ListOut:
    shopping_list = await load_accessible_list(db, user_id, list_id)
    return ListOut.model_validate(shopping_list)


async def rename_list(
    db: AsyncSession, user_id: uuid.UUID, list_id: uuid.UUID, req: ListRename
) -> ListOut:
    shopping_list = await load_owned_list(db, user_id, list_id)
    shopping_list.name = req.name
    await db.commit()
    return ListOut.model_validate(await _reload(db, list_id))


async def delete_list(db: AsyncSession, user_id: uuid.UUID, list_id: uuid.UUID) -> None:
    """Items cascade with the list. Deleting the last list is fine — the default recreates
    itself on the next touch."""
    shopping_list = await load_owned_list(db, user_id, list_id)
    await db.delete(shopping_list)
    await db.commit()


async def add_item(
    db: AsyncSession, user_id: uuid.UUID, list_id: uuid.UUID, req: ItemCreate
) -> ListOut:
    shopping_list = await load_accessible_list(db, user_id, list_id)
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
    shopping_list = await load_accessible_list(db, user_id, list_id)
    recipe = await load_accessible_recipe(db, user_id, req.recipe_id)
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
    if not incoming:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Nothing on this recipe needs buying (water and the like are skipped).",
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
    shopping_list = await load_accessible_list(db, user_id, list_id)
    item = next((i for i in shopping_list.items if i.id == item_id), None)
    if item is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Item not found")

    if req.name is not None:
        item.name = req.name
    if req.quantity is not None:
        # An explicit edit replaces the whole aggregate — the user is overriding, not merging.
        _store_measures(
            item, [Measure(quantity=req.quantity, unit=canonical_unit(req.unit or item.unit))]
        )
    elif req.unit is not None:
        measures = _item_measures(item)
        if len(measures) == 1:
            _store_measures(
                item, [Measure(quantity=measures[0].quantity, unit=canonical_unit(req.unit))]
            )
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
    shopping_list = await load_accessible_list(db, user_id, list_id)
    item = next((i for i in shopping_list.items if i.id == item_id), None)
    if item is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Item not found")
    shopping_list.items.remove(item)  # delete-orphan removes the row
    await db.commit()
    return ListOut.model_validate(await _reload(db, list_id))


async def clear_checked(db: AsyncSession, user_id: uuid.UUID, list_id: uuid.UUID) -> ListOut:
    shopping_list = await load_accessible_list(db, user_id, list_id)
    shopping_list.items = [i for i in shopping_list.items if not i.checked]
    await db.commit()
    return ListOut.model_validate(await _reload(db, list_id))


__all__ = [
    "add_item",
    "add_member_by_email",
    "add_recipe",
    "clear_checked",
    "delete_item",
    "get_default_list",
    "get_list_out",
    "is_purchasable",
    "list_members",
    "load_accessible_list",
    "load_owned_list",
    "recall_category",
    "remove_member",
    "suggest_items",
    "update_item",
]
