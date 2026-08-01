import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.limiter import limiter
from app.schemas.store import (
    AislesPut,
    PlacementImportIn,
    PlacementImportOut,
    PlacementIn,
    StoreCreate,
    StoreDetailOut,
    StoreLayoutDraftOut,
    StoreOut,
    StoreUpdate,
    SuggestLayoutRequest,
    UnplacedOut,
)
from app.security import CurrentUser
from app.services.store_service import (
    create_store,
    delete_placement,
    delete_store,
    get_store,
    import_placements,
    list_stores,
    replace_aisles,
    suggest_layout,
    unplaced_items,
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


# Fixed path, declared before /{store_id} so "suggest-layout" never parses as an id.
@router.post("/suggest-layout", response_model=StoreLayoutDraftOut)
@limiter.limit("5/minute")
async def suggest_store_layout(
    request: Request, req: SuggestLayoutRequest, current_user: CurrentUser
):
    """A starting-point layout for a named chain, from the local model. **Saves nothing** — the
    client opens it in the aisle editor and the user commits it through the normal endpoints.

    Never fails on the model: an unreachable or unreadable sidecar returns the canonical walk order
    flagged ``low_confidence`` instead, because "add a store" shouldn't depend on AI either."""
    return await suggest_layout(req.chain)


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


@router.get("/{store_id}/unplaced", response_model=UnplacedOut)
async def unplaced_for_list(
    store_id: uuid.UUID, list_id: uuid.UUID, current_user: CurrentUser, db: DbSession
):
    """Which unchecked items on ``list_id`` this store still has no aisle for.

    The worklist for an aisle import. Deduplicated by placement key and ordered like the list, so
    working through it top-to-bottom matches the order the shopper sees.
    """
    return await unplaced_items(db, current_user.id, store_id, list_id)


@router.post("/{store_id}/placements/import", response_model=PlacementImportOut)
async def import_store_placements(
    store_id: uuid.UUID, req: PlacementImportIn, current_user: CurrentUser, db: DbSession
):
    """Apply a batch of harvested aisle observations for this store.

    **The server never fetches these itself** — meijer.com is a bot-protected SPA that refuses
    automated browsers outright (``app/retailers/meijer.py`` documents the measurement). They are
    collected in a real browser session and posted here. That makes an import an occasional,
    human-initiated batch rather than a background refresh, which is fine: an aisle is close to
    static, so the cost is paid once per item and cached in ``store_placements``.

    Idempotent, and never destructive — an observation with no aisle is reported in ``skipped``
    rather than clearing a placement the user learned by actually walking the store.
    """
    return await import_placements(db, current_user.id, store_id, req)


@router.delete("/{store_id}/placements/{placement_id}", response_model=StoreDetailOut)
async def remove_placement(
    store_id: uuid.UUID, placement_id: uuid.UUID, current_user: CurrentUser, db: DbSession
):
    return await delete_placement(db, current_user.id, store_id, placement_id)
