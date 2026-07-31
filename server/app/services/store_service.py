"""Store profiles: CRUD, the aisle-replace write, and learned item placements (v0.11).

Access mirrors shopping lists exactly — a store is reachable by its creator's household
(``household_member_ids``), because two people shopping the same store want the same floor plan.
Deleting stays creator-only, the ``is_owner`` rule the recipe share toggle established.
"""

import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.limits import MAX_STORES
from app.lists.merge import normalize_name
from app.models.recipe import STORE_CATEGORIES
from app.models.store import Store, StoreAisle, StorePlacement
from app.schemas.store import (
    AisleIn,
    PlacementIn,
    StoreCreate,
    StoreDetailOut,
    StoreOut,
    StoreUpdate,
)
from app.services.household_service import household_member_ids

# Human labels for the canonical categories, used only to name the seeded default aisles. The
# Android client has its own copy for display; these exist so a freshly created store reads like a
# store ("Dairy & Eggs") rather than a database ("dairy").
_CATEGORY_AISLE_NAMES = {
    "produce": "Produce",
    "meat": "Meat & Seafood",
    "deli": "Deli",
    "dairy": "Dairy & Eggs",
    "bakery": "Bakery",
    "frozen": "Frozen",
    "pantry": "Pantry",
    "snacks": "Snacks",
    "beverages": "Beverages",
    "household": "Household",
    "personal": "Personal care",
    "baby": "Baby",
    "other": "Other",
}


def default_aisles() -> list[AisleIn]:
    """One aisle per canonical category, in the canonical walk order.

    A new store is immediately usable with this — routing it produces exactly the category
    grouping the user already had, so selecting a store can never make the list *worse* before
    they've edited anything.
    """
    return [
        AisleIn(name=_CATEGORY_AISLE_NAMES[category], categories=[category])
        for category in STORE_CATEGORIES
    ]


async def _reload(db: AsyncSession, store_id: uuid.UUID) -> Store:
    """Re-fetch with aisles + placements eagerly loaded (both relationships are selectin).

    ``populate_existing`` is load-bearing: the session is built with ``expire_on_commit=False``,
    so without it the identity map hands back the collections as they looked *before* the write
    and the response claims a deleted aisle still exists. The shopping service gets away without
    it because it mutates ``shopping_list.items`` through the relationship; these writes go via
    ``db.add``/``db.delete`` plus a DB-level cascade from aisle to placement, which the ORM's
    in-memory collections never see.
    """
    result = await db.execute(
        select(Store).where(Store.id == store_id).execution_options(populate_existing=True)
    )
    return result.scalar_one()


async def load_accessible_store(db: AsyncSession, user_id: uuid.UUID, store_id: uuid.UUID) -> Store:
    """Read/edit access: the creator or anyone in their household."""
    store = await db.get(Store, store_id)
    if store is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Store not found")
    if store.user_id == user_id:
        return store
    if store.user_id in await household_member_ids(db, user_id):
        return store
    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Store not found")


async def load_owned_store(db: AsyncSession, user_id: uuid.UUID, store_id: uuid.UUID) -> Store:
    """Creator-only — for delete. A co-member can reshape the aisles (that's shared knowledge);
    removing someone else's store is not theirs to do."""
    store = await db.get(Store, store_id)
    if store is None or store.user_id != user_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Store not found")
    return store


def _to_detail(store: Store, *, user_id: uuid.UUID) -> StoreDetailOut:
    return StoreDetailOut(
        id=store.id,
        name=store.name,
        label=store.label,
        is_owner=store.user_id == user_id,
        created_at=store.created_at,
        aisles=sorted(store.aisles, key=lambda a: a.order),
        placements=list(store.placements),
    )


async def list_stores(db: AsyncSession, user_id: uuid.UUID) -> list[StoreOut]:
    """Every store the household can shop, oldest first (the one you added when you started)."""
    member_ids = await household_member_ids(db, user_id)
    rows = (
        (
            await db.execute(
                select(Store).where(Store.user_id.in_(member_ids)).order_by(Store.created_at)
            )
        )
        .scalars()
        .all()
    )
    return [
        StoreOut(
            id=s.id,
            name=s.name,
            label=s.label,
            is_owner=s.user_id == user_id,
            created_at=s.created_at,
        )
        for s in rows
    ]


async def get_store(db: AsyncSession, user_id: uuid.UUID, store_id: uuid.UUID) -> StoreDetailOut:
    store = await load_accessible_store(db, user_id, store_id)
    return _to_detail(await _reload(db, store.id), user_id=user_id)


async def create_store(db: AsyncSession, user_id: uuid.UUID, req: StoreCreate) -> StoreDetailOut:
    owned = (await db.execute(select(Store.id).where(Store.user_id == user_id))).scalars().all()
    if len(owned) >= MAX_STORES:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"You can keep up to {MAX_STORES} stores",
        )
    store = Store(user_id=user_id, name=req.name, label=req.label)
    db.add(store)
    await db.flush()
    for order, aisle in enumerate(req.aisles if req.aisles is not None else default_aisles()):
        db.add(
            StoreAisle(store_id=store.id, order=order, name=aisle.name, categories=aisle.categories)
        )
    await db.commit()
    return _to_detail(await _reload(db, store.id), user_id=user_id)


async def update_store(
    db: AsyncSession, user_id: uuid.UUID, store_id: uuid.UUID, req: StoreUpdate
) -> StoreDetailOut:
    store = await load_accessible_store(db, user_id, store_id)
    if req.name is not None:
        store.name = req.name
    if req.label is not None:
        store.label = req.label or None  # "" clears
    await db.commit()
    return _to_detail(await _reload(db, store.id), user_id=user_id)


async def delete_store(db: AsyncSession, user_id: uuid.UUID, store_id: uuid.UUID) -> None:
    store = await load_owned_store(db, user_id, store_id)
    await db.delete(store)
    await db.commit()


async def replace_aisles(
    db: AsyncSession, user_id: uuid.UUID, store_id: uuid.UUID, aisles: list[AisleIn]
) -> StoreDetailOut:
    """Full replace, **preserving rows the payload identifies by id**.

    The id preservation is the point: reordering or renaming an aisle must not throw away the
    placements someone learned by walking the store. Only an aisle actually removed from the
    layout loses its placements (they cascade), which is the honest outcome — the aisle is gone.
    """
    store = await load_accessible_store(db, user_id, store_id)
    existing = {
        a.id: a
        for a in (
            (await db.execute(select(StoreAisle).where(StoreAisle.store_id == store.id)))
            .scalars()
            .all()
        )
    }
    kept: set[uuid.UUID] = set()
    for order, incoming in enumerate(aisles):
        row = existing.get(incoming.id) if incoming.id is not None else None
        if row is not None:
            row.order = order
            row.name = incoming.name
            row.categories = incoming.categories
            kept.add(row.id)
        else:
            # An unknown id means the client is racing another device's edit; treat it as new
            # rather than 404ing the whole save and losing the user's reordering.
            db.add(
                StoreAisle(
                    store_id=store.id,
                    order=order,
                    name=incoming.name,
                    categories=incoming.categories,
                )
            )
    for aisle_id, row in existing.items():
        if aisle_id not in kept:
            await db.delete(row)
    await db.commit()
    return _to_detail(await _reload(db, store.id), user_id=user_id)


async def upsert_placement(
    db: AsyncSession, user_id: uuid.UUID, store_id: uuid.UUID, req: PlacementIn
) -> StoreDetailOut:
    """ "I found it in aisle 5" — one row per (store, normalized name), last write wins.

    Keyed on ``normalize_name`` so it matches however the item is spelled next time, and shared
    with the household because it describes the store, not a preference. Deliberately does **not**
    touch the item's canonical category or ``item_history``: where a thing sits in *this* store
    says nothing about where it sits in the next one.
    """
    store = await load_accessible_store(db, user_id, store_id)
    aisle = await db.get(StoreAisle, req.aisle_id)
    if aisle is None or aisle.store_id != store.id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Aisle not found")
    key = normalize_name(req.name)
    if not key:
        raise HTTPException(status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail="Bad name")
    row = (
        await db.execute(
            select(StorePlacement).where(
                StorePlacement.store_id == store.id, StorePlacement.key == key
            )
        )
    ).scalar_one_or_none()
    if row is None:
        db.add(StorePlacement(store_id=store.id, aisle_id=aisle.id, key=key, name=req.name.strip()))
    else:
        row.aisle_id = aisle.id
        row.name = req.name.strip()
    await db.commit()
    return _to_detail(await _reload(db, store.id), user_id=user_id)


async def delete_placement(
    db: AsyncSession, user_id: uuid.UUID, store_id: uuid.UUID, placement_id: uuid.UUID
) -> StoreDetailOut:
    """Forget an exception — the item falls back to its category's aisle."""
    store = await load_accessible_store(db, user_id, store_id)
    row = await db.get(StorePlacement, placement_id)
    if row is None or row.store_id != store.id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Placement not found")
    await db.delete(row)
    await db.commit()
    return _to_detail(await _reload(db, store.id), user_id=user_id)
