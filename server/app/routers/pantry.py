import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, File, HTTPException, Query, Request, UploadFile, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.limiter import limiter
from app.schemas.pantry import (
    PantryConfirmRequest,
    PantryItemIn,
    PantryItemOut,
    PantryItemUpdate,
    PantryScanDraftOut,
    PantrySuggestionsOut,
    StaplesOut,
    StaplesPut,
)
from app.security import CurrentUser
from app.services.ai.vision import estimate_pantry_photo
from app.services.pantry_service import (
    add_item,
    confirm_items,
    delete_item,
    get_staples,
    get_suggestions,
    list_pantry,
    put_staples,
    update_item,
)

router = APIRouter(prefix="/pantry", tags=["pantry"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


# Fixed-path routes are declared before /items/{item_id} so they never parse as ids.
@router.post("/scan", response_model=PantryScanDraftOut)
@limiter.limit("10/minute")
async def scan(
    request: Request,
    current_user: CurrentUser,
    photo: Annotated[UploadFile, File()],
):
    """Read a fridge/pantry photo into a list of food candidates via the local LM Studio
    vision model. Nothing is saved — the client shows the confirmation screen and the
    user's reviewed list lands via POST /pantry/confirm."""
    if not (photo.content_type or "").startswith("image/"):
        raise HTTPException(status_code=422, detail="File must be an image.")
    image_bytes = await photo.read()
    if len(image_bytes) > settings.photo_max_bytes:
        raise HTTPException(status_code=413, detail="Photo is too large.")
    return await estimate_pantry_photo(image_bytes, photo.content_type)


@router.get("/staples", response_model=StaplesOut)
async def staples(current_user: CurrentUser, db: DbSession):
    """The always-assumed-available list. Before first confirmation this returns the
    seeded defaults with confirmed=false so the client shows the one-time review sheet."""
    return await get_staples(db, current_user)


@router.put("/staples", response_model=StaplesOut)
async def replace_staples(req: StaplesPut, current_user: CurrentUser, db: DbSession):
    return await put_staples(db, current_user, req)


@router.get("/suggestions", response_model=PantrySuggestionsOut)
@limiter.limit("30/minute")
async def suggestions(
    request: Request,
    current_user: CurrentUser,
    db: DbSession,
    max_missing: Annotated[int, Query(ge=0, le=5)] = 2,
):
    """ "What can I make?" — saved recipes coverable by pantry+staples, plus Spoonacular
    find-by-ingredients ideas when configured (absent, not an error, when it isn't)."""
    return await get_suggestions(db, current_user, max_missing=max_missing)


@router.post("/confirm", response_model=list[PantryItemOut])
async def confirm(req: PantryConfirmRequest, current_user: CurrentUser, db: DbSession):
    """Bulk write from the scan confirmation screen; merges by default, replaces on request."""
    return await confirm_items(db, current_user.id, req)


@router.get("", response_model=list[PantryItemOut])
async def all_items(current_user: CurrentUser, db: DbSession):
    return await list_pantry(db, current_user.id)


@router.post("/items", response_model=PantryItemOut, status_code=status.HTTP_201_CREATED)
async def create_item(req: PantryItemIn, current_user: CurrentUser, db: DbSession):
    """Manual add. Re-adding an existing item (by normalized name) returns that row."""
    return await add_item(db, current_user.id, req)


@router.patch("/items/{item_id}", response_model=PantryItemOut)
async def patch_item(
    item_id: uuid.UUID, req: PantryItemUpdate, current_user: CurrentUser, db: DbSession
):
    return await update_item(db, current_user.id, item_id, req)


@router.delete("/items/{item_id}", status_code=status.HTTP_204_NO_CONTENT)
async def remove_item(item_id: uuid.UUID, current_user: CurrentUser, db: DbSession):
    await delete_item(db, current_user.id, item_id)
