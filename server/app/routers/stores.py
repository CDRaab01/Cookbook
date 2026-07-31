import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.store import (
    AislesPut,
    PlacementIn,
    StoreCreate,
    StoreDetailOut,
    StoreOut,
    StoreUpdate,
)
from app.security import CurrentUser
from app.services.store_service import (
    create_store,
    delete_placement,
    delete_store,
    get_store,
    list_stores,
    replace_aisles,
    update_store,
    upsert_placement,
)

router = APIRouter(prefix="/stores", tags=["stores"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


@router.get("", response_model=list[StoreOut])
async def all_stores(current_user: CurrentUser, db: DbSession):
    """Every store the household shops, oldest first. No aisles — the picker doesn't need them."""
    return await list_stores(db, current_user.id)


@router.post("", response_model=StoreDetailOut, status_code=status.HTTP_201_CREATED)
async def create_new_store(req: StoreCreate, current_user: CurrentUser, db: DbSession):
    """Omitting ``aisles`` seeds the canonical walk order, so a new store routes exactly like the
    plain category grouping until someone edits it."""
    return await create_store(db, current_user.id, req)


@router.get("/{store_id}", response_model=StoreDetailOut)
async def one_store(store_id: uuid.UUID, current_user: CurrentUser, db: DbSession):
    return await get_store(db, current_user.id, store_id)


@router.patch("/{store_id}", response_model=StoreDetailOut)
async def patch_store(
    store_id: uuid.UUID, req: StoreUpdate, current_user: CurrentUser, db: DbSession
):
    return await update_store(db, current_user.id, store_id, req)


@router.delete("/{store_id}", status_code=status.HTTP_204_NO_CONTENT)
async def remove_store(store_id: uuid.UUID, current_user: CurrentUser, db: DbSession):
    """Creator-only; aisles and placements cascade."""
    await delete_store(db, current_user.id, store_id)


@router.put("/{store_id}/aisles", response_model=StoreDetailOut)
async def put_aisles(store_id: uuid.UUID, req: AislesPut, current_user: CurrentUser, db: DbSession):
    """Replace the walk order. Aisles carrying an existing ``id`` are updated in place so their
    learned placements survive; omitted aisles are deleted and their placements cascade."""
    return await replace_aisles(db, current_user.id, store_id, req.aisles)


@router.post(
    "/{store_id}/placements", response_model=StoreDetailOut, status_code=status.HTTP_201_CREATED
)
async def add_placement(
    store_id: uuid.UUID, req: PlacementIn, current_user: CurrentUser, db: DbSession
):
    """Remember that this item lives in that aisle *here*. Upsert by normalized name."""
    return await upsert_placement(db, current_user.id, store_id, req)


@router.delete("/{store_id}/placements/{placement_id}", response_model=StoreDetailOut)
async def remove_placement(
    store_id: uuid.UUID, placement_id: uuid.UUID, current_user: CurrentUser, db: DbSession
):
    return await delete_placement(db, current_user.id, store_id, placement_id)
