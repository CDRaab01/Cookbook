import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, File, HTTPException, Query, Request, UploadFile, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.limiter import limiter
from app.schemas.photo import RecipePhotoDraftOut
from app.schemas.recipe import (
    CookedOut,
    DiscoveredRecipe,
    PreviewIngredientOut,
    RecipeCreate,
    RecipeImportRequest,
    RecipeImportUrlRequest,
    RecipeOut,
    RecipePreviewOut,
    RecipeSummaryOut,
    RecipeUpdate,
)
from app.security import CurrentUser
from app.services.ai.vision import estimate_recipe_photo
from app.services.plate_nutrition_service import (
    LogToPlateRequest,
    LogToPlateResult,
    RecipeNutritionOut,
    get_recipe_nutrition,
    log_recipe_to_plate,
)
from app.services.recipe_discovery_service import (
    discover_recipes,
    import_recipe,
    import_recipe_from_url,
    preview_recipe,
)
from app.services.recipe_service import (
    create_recipe,
    delete_recipe,
    get_recipe,
    list_recipes,
    mark_cooked,
    unmark_cooked,
    update_recipe,
)

router = APIRouter(prefix="/recipes", tags=["recipes"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


# Fixed-path routes are declared before /{recipe_id} so "discover"/"import" never parse as ids.
@router.get("/discover", response_model=list[DiscoveredRecipe])
@limiter.limit("30/minute")
async def discover(
    request: Request,
    current_user: CurrentUser,
    q: Annotated[str, Query(description="Recipe search text")],
):
    """Search external recipes (Spoonacular). 503 until SPOONACULAR_API_KEY is configured."""
    hits = await discover_recipes(q)
    return [
        DiscoveredRecipe(
            source_id=h.source_id,
            title=h.title,
            image=h.image,
            ready_in_minutes=h.ready_in_minutes,
            servings=h.servings,
        )
        for h in hits
    ]


@router.get("/discover/{source_id}", response_model=RecipePreviewOut)
@limiter.limit("30/minute")
async def discover_preview(
    request: Request,
    source_id: str,
    current_user: CurrentUser,
):
    """Full look at a Discover hit (ingredients + steps) before importing — nothing saved."""
    normalized = await preview_recipe(source_id)
    return RecipePreviewOut(
        source_id=normalized.source_id,
        title=normalized.title,
        image=normalized.image,
        servings=normalized.servings,
        ready_in_minutes=normalized.ready_in_minutes,
        source_url=normalized.source_url,
        summary=normalized.summary,
        ingredients=[
            PreviewIngredientOut(
                name=i.name,
                quantity=i.quantity,
                unit=i.unit,
                category=i.category,
                note=i.original_text if i.original_text != i.name else None,
            )
            for i in normalized.ingredients
        ],
        steps=normalized.steps,
    )


@router.post("/import", response_model=RecipeOut, status_code=status.HTTP_201_CREATED)
@limiter.limit("30/minute")
async def import_external(
    request: Request,
    req: RecipeImportRequest,
    current_user: CurrentUser,
    db: DbSession,
):
    """Import an external recipe as an editable Cookbook recipe (free-text ingredients + steps)."""
    return await import_recipe(db, current_user.id, req.source_id)


@router.post("/import-url", response_model=RecipeOut, status_code=status.HTTP_201_CREATED)
@limiter.limit("15/minute")
async def import_from_url(
    request: Request,
    req: RecipeImportUrlRequest,
    current_user: CurrentUser,
    db: DbSession,
):
    """Import any recipe page by URL (v0.2): the site's JSON-LD markup first, Spoonacular's
    extractor as fallback. Works without a Spoonacular key when the site has clean markup."""
    return await import_recipe_from_url(db, current_user.id, req.url)


@router.post("/import-photo", response_model=RecipePhotoDraftOut)
@limiter.limit("10/minute")
async def import_photo(
    request: Request,
    current_user: CurrentUser,
    photo: Annotated[UploadFile, File()],
):
    """Read a recipe photo (card, cookbook page) into a draft via a local LM Studio vision
    model. Nothing is saved — the client opens the normal recipe editor pre-filled with the
    draft, and the user reviews/edits before the usual POST /recipes commits it."""
    if not (photo.content_type or "").startswith("image/"):
        raise HTTPException(status_code=422, detail="File must be an image.")
    image_bytes = await photo.read()
    if len(image_bytes) > settings.photo_max_bytes:
        raise HTTPException(status_code=413, detail="Photo is too large.")
    return await estimate_recipe_photo(image_bytes, photo.content_type)


@router.post("", response_model=RecipeOut, status_code=status.HTTP_201_CREATED)
async def create(req: RecipeCreate, current_user: CurrentUser, db: DbSession):
    return await create_recipe(db, current_user.id, req)


@router.get("", response_model=list[RecipeSummaryOut])
async def list_all(current_user: CurrentUser, db: DbSession):
    return await list_recipes(db, current_user.id)


@router.get("/{recipe_id}", response_model=RecipeOut)
async def read(recipe_id: uuid.UUID, current_user: CurrentUser, db: DbSession):
    return await get_recipe(db, current_user.id, recipe_id)


@router.patch("/{recipe_id}", response_model=RecipeOut)
async def update(
    recipe_id: uuid.UUID,
    req: RecipeUpdate,
    current_user: CurrentUser,
    db: DbSession,
):
    return await update_recipe(db, current_user.id, recipe_id, req)


@router.delete("/{recipe_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete(recipe_id: uuid.UUID, current_user: CurrentUser, db: DbSession):
    await delete_recipe(db, current_user.id, recipe_id)


@router.post("/{recipe_id}/cooked", response_model=CookedOut, status_code=status.HTTP_201_CREATED)
async def cooked(recipe_id: uuid.UUID, current_user: CurrentUser, db: DbSession):
    """ "I made this" — appends one cook event and returns the fresh aggregates."""
    return await mark_cooked(db, current_user.id, recipe_id)


@router.delete("/{recipe_id}/cooked/last", response_model=CookedOut)
async def uncooked(recipe_id: uuid.UUID, current_user: CurrentUser, db: DbSession):
    """Undo a mis-tap: removes the most recent cook event."""
    return await unmark_cooked(db, current_user.id, recipe_id)


@router.get("/{recipe_id}/nutrition", response_model=RecipeNutritionOut)
@limiter.limit("30/minute")
async def nutrition(
    request: Request, recipe_id: uuid.UUID, current_user: CurrentUser, db: DbSession
):
    """Best-effort macro estimate via Plate (per-ingredient + totals + per-serving).
    503 until PLATE_BASE_URL + CROSS_APP_SECRET are configured."""
    return await get_recipe_nutrition(db, current_user, recipe_id)


@router.post("/{recipe_id}/log-to-plate", response_model=LogToPlateResult)
@limiter.limit("30/minute")
async def log_to_plate(
    request: Request,
    recipe_id: uuid.UUID,
    req: LogToPlateRequest,
    current_user: CurrentUser,
    db: DbSession,
):
    """Send the eaten share of this recipe to Plate's diary (date + meal + servings eaten)."""
    return await log_recipe_to_plate(db, current_user, recipe_id, req)
