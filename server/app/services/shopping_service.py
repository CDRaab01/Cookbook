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
from sqlalchemy import func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.limits import MAX_ITEM_NAME_LENGTH, MAX_LIST_ITEMS
from app.lists.categorize import guess_category
from app.lists.link_items import name_from_url, split_link
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
    shopping_measure,
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
from app.services.link_title_service import resolve_link_preview
from app.services.recipe_service import load_accessible_recipe

DEFAULT_LIST_NAME = "Groceries"
MAX_SUGGESTIONS = 8
# pg_trgm's default similarity() cutoff; a name scoring above this is a "similar spelling" hit.
# Catches near-spellings of longer words ("bananna" -> "banana").
SIMILARITY_THRESHOLD = 0.3
# Max edit distance for the levenshtein fallback, which catches short-word typos/transpositions
# that trigram similarity misses ("mlik" -> "milk" is only 0.11 similar but distance 2).
MAX_EDIT_DISTANCE = 2


async def _reload(db: AsyncSession, list_id: uuid.UUID) -> ShoppingList:
    """Re-fetch with items eagerly loaded (the relationship is selectin)."""
    result = await db.execute(select(ShoppingList).where(ShoppingList.id == list_id))
    return result.scalar_one()


async def get_default_list(db: AsyncSession, user_id: uuid.UUID) -> ShoppingList:
    """The default list, created on first touch. In a household this resolves to the **owner's**
    default list, so both members land on one shared list (and its plan) rather than each editing
    their own private default."""
    from app.services.household_service import household_owner_id

    owner_id = await household_owner_id(db, user_id)
    result = await db.execute(
        select(ShoppingList)
        .where(ShoppingList.user_id == owner_id)
        .order_by(ShoppingList.created_at)
        .limit(1)
    )
    existing = result.scalar_one_or_none()
    if existing is not None:
        return existing
    created = ShoppingList(user_id=owner_id, name=DEFAULT_LIST_NAME)
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
            # First link wins — a merge shouldn't drop or churn an existing product link.
            if target.link_url is None:
                target.link_url = inc.link_url
            if target.image_url is None:
                target.image_url = inc.image_url
            # Multi-recipe provenance collapses to "manual/mixed" (NULL) rather than lying.
            if recipe_id is not None and target.recipe_id != recipe_id:
                target.recipe_id = None
        else:
            new_item = ShoppingListItem(
                name=inc.name,
                category=inc.category,
                link_url=inc.link_url,
                image_url=inc.image_url,
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
            # "Buy again": remember the latest link/thumbnail, but never clobber a remembered
            # one with None (a later plain add of the same name shouldn't forget the link).
            if item.link_url is not None:
                row.link_url = item.link_url
                row.image_url = item.image_url
        else:
            db.add(
                ItemHistory(
                    user_id=user_id,
                    key=key,
                    name=item.name,
                    unit=unit,
                    category=item.category,
                    link_url=item.link_url,
                    image_url=item.image_url,
                    last_used=now,
                )
            )


async def recall_link(
    db: AsyncSession, user_id: uuid.UUID, name: str
) -> tuple[str, str | None] | None:
    """The (link_url, image_url) last used for this item name, or None — "buy again"."""
    result = await db.execute(
        select(ItemHistory.link_url, ItemHistory.image_url).where(
            ItemHistory.user_id == user_id, ItemHistory.key == normalize_name(name)
        )
    )
    row = result.first()
    if row is None or row.link_url is None:
        return None
    return row.link_url, row.image_url


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
    """Autocomplete for the add dialog: your own items matching the query.

    Substring matches ("mil" → "milk") come first, ordered by most-used; typo-tolerant fuzzy
    matches fill in below them, closest first. Two distance measures cover different misspellings:
    trigram similarity for longer near-spellings ("bananna" → "banana") and levenshtein edit
    distance for short-word typos/transpositions trigrams miss ("mlik" → "milk"). Both only ever
    match against the user's own history, so a loose fuzzy match can't surface a stranger's item.
    """
    text = q.strip()
    if not text:
        return []
    substring = ItemHistory.name.ilike(f"%{text}%")
    similarity = func.similarity(ItemHistory.name, text)
    edit_distance = func.levenshtein(func.lower(ItemHistory.name), text.lower())
    result = await db.execute(
        select(ItemHistory)
        .where(
            ItemHistory.user_id == user_id,
            or_(
                substring,
                similarity > SIMILARITY_THRESHOLD,
                edit_distance <= MAX_EDIT_DISTANCE,
            ),
        )
        .order_by(
            substring.desc(),  # real substring hits first
            ItemHistory.use_count.desc(),  # then most-used
            similarity.desc(),  # then closest spelling
            edit_distance.asc(),  # then fewest edits
            ItemHistory.last_used.desc(),
        )
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
    # Lists shared with me: legacy per-list ListMember shares PLUS every list owned by a household
    # co-member (family mode). Without the household half, a member never sees the shared list.
    from app.services.household_service import household_member_ids

    member_ids = await household_member_ids(db, user_id)
    co_member_ids = member_ids - {user_id}
    owned_ids = {sl.id for sl in owned}
    shared_by_id: dict[uuid.UUID, ShoppingList] = {}
    legacy = (
        (
            await db.execute(
                select(ShoppingList)
                .join(ListMember, ListMember.list_id == ShoppingList.id)
                .where(ListMember.user_id == user_id)
            )
        )
        .scalars()
        .all()
    )
    household_lists = []
    if co_member_ids:
        household_lists = (
            (await db.execute(select(ShoppingList).where(ShoppingList.user_id.in_(co_member_ids))))
            .scalars()
            .all()
        )
    for sl in [*legacy, *household_lists]:
        if sl.id not in owned_ids:
            shared_by_id[sl.id] = sl
    shared_with_me = sorted(shared_by_id.values(), key=lambda s: s.created_at)

    # My own lists read as "shared" if they have members OR I'm in a household (co-members see them).
    shared_owned_ids: set[uuid.UUID] = set()
    if owned_ids:
        shared_owned_ids = set(
            (await db.execute(select(ListMember.list_id).where(ListMember.list_id.in_(owned_ids))))
            .scalars()
            .all()
        )
    if co_member_ids:
        shared_owned_ids |= owned_ids
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
    # A pasted product URL becomes a link item (v0.5): the URL splits into link_url and the
    # name stays human — typed text when there is any, else the page's title, else a name
    # derived from the URL slug. The list shows things you buy, not tracking parameters.
    typed, url = split_link(req.name)
    link_url, image_url = url, None
    if url is None:
        if len(req.name) > MAX_ITEM_NAME_LENGTH:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail=f"item name is limited to {MAX_ITEM_NAME_LENGTH} characters",
            )
        name = req.name
        # "Buy again" (v0.6): a plain typed add re-attaches the link + thumbnail last used for
        # this name, so "milk collector" comes back linked without re-pasting the URL.
        recalled = await recall_link(db, user_id, name)
        if recalled is not None:
            link_url, image_url = recalled
    else:
        # One fetch gets both the title and the thumbnail (v0.6) — used even when the user typed
        # their own name, so a typed+URL add still gets a picture.
        preview = await resolve_link_preview(url)
        image_url = preview.image_url
        name = (typed or preview.title or name_from_url(url))[:MAX_ITEM_NAME_LENGTH]
    # Uncategorized adds get auto-sorted: the category you used last time, else a keyword guess
    # — always from the cleaned name, so a URL slug can't keyword-match a random aisle.
    category = req.category or await recall_category(db, user_id, name)
    incoming = IncomingItem(
        name=name,
        quantity=req.quantity,
        unit=req.unit,
        category=category,
        link_url=link_url,
        image_url=image_url,
    )
    _merge_into_list(shopping_list, [incoming], recipe_id=None)
    # History feeds autocomplete with *your* vocabulary: typed text is worth remembering, a
    # fetched/slug-derived product title (one-off SKU string) is not.
    if url is None or typed:
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

    def _recipe_incoming(ing) -> IncomingItem:
        # A recipe's cooking measure (0.25 cup, 1 tbsp) means nothing on a shopping list — you buy
        # a bottle, not a quarter cup — so drop it here; weights and counts pass through.
        quantity, unit = shopping_measure(scale_quantity(ing.quantity, req.scale), ing.unit)
        return IncomingItem(
            name=ing.name, quantity=quantity, unit=unit, category=ing.category, note=ing.note
        )

    incoming = merge_incoming([_recipe_incoming(ing) for ing in recipe.ingredients])
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
    if req.link_url is not None:
        item.link_url = req.link_url or None  # "" is the clearing sentinel
        if not req.link_url:
            item.image_url = None  # removing the link drops its thumbnail too
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
