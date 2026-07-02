"""Discover external recipes and import one as a saved Cookbook recipe (CLAUDE.md §5).

Unlike Plate's importer (which minted Food rows), an import maps straight to free-text
``recipe_ingredients`` plus instruction steps — an imported recipe is an ordinary
:class:`~app.models.recipe.Recipe` flagged ``source='imported'`` and immediately editable.
"""

import logging
import uuid

import httpx
from fastapi import HTTPException, status

from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.limits import (
    MAX_RECIPE_INGREDIENTS,
    MAX_RECIPE_STEPS,
    QUANTITY_BOUNDS,
    SERVINGS_BOUNDS,
)
from app.recipes_ext.base import RecipeSource, RecipeSummary
from app.recipes_ext.spoonacular import SpoonacularSource
from app.schemas.recipe import IngredientIn, RecipeCreate, RecipeOut
from app.services.recipe_service import create_recipe

log = logging.getLogger(__name__)

_DISABLED = HTTPException(
    status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
    detail="Recipe discovery is not configured (set SPOONACULAR_API_KEY).",
)


def _build_source(client: httpx.AsyncClient) -> SpoonacularSource:
    """Construct the Spoonacular source, honoring the RapidAPI host + base-URL config."""
    host = settings.spoonacular_rapidapi_host
    base_url = f"https://{host}" if host else settings.spoonacular_base_url
    return SpoonacularSource(client, settings.spoonacular_api_key, base_url, rapidapi_host=host)


async def discover_recipes(
    query: str,
    *,
    source: RecipeSource | None = None,
) -> list[RecipeSummary]:
    """Search external recipes. ``source`` is injectable for tests; production builds Spoonacular."""
    q = query.strip()
    if not q:
        return []
    if source is not None:
        return await source.discover(q, limit=settings.recipe_discover_limit)
    if not settings.spoonacular_api_key:
        raise _DISABLED
    async with httpx.AsyncClient(timeout=settings.external_timeout_seconds) as client:
        return await _build_source(client).discover(q, limit=settings.recipe_discover_limit)


async def import_recipe(
    db: AsyncSession,
    user_id: uuid.UUID,
    source_id: str,
    *,
    source: RecipeSource | None = None,
) -> RecipeOut:
    """Fetch an external recipe and save it as a Cookbook recipe (free-text ingredients + steps)."""
    if source is not None:
        normalized = await source.fetch(source_id)
    elif settings.spoonacular_api_key:
        async with httpx.AsyncClient(timeout=settings.external_timeout_seconds) as client:
            normalized = await _build_source(client).fetch(source_id)
    else:
        raise _DISABLED

    if normalized is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Recipe not found")
    if not normalized.ingredients:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Recipe had no usable ingredients.",
        )

    servings = normalized.servings or 1
    servings = max(SERVINGS_BOUNDS[0], min(SERVINGS_BOUNDS[1], servings))
    req = RecipeCreate(
        name=normalized.title,
        description=normalized.source_url or normalized.summary,
        servings=servings,
        cook_minutes=normalized.ready_in_minutes,
        image_url=normalized.image,
        steps=normalized.steps[:MAX_RECIPE_STEPS],
        ingredients=[
            IngredientIn(
                name=ing.name[:255],
                # Clamp rather than reject: the provider's data quality is not the user's problem.
                quantity=(
                    None
                    if ing.quantity is None or ing.quantity <= QUANTITY_BOUNDS[0]
                    else min(ing.quantity, QUANTITY_BOUNDS[1])
                ),
                unit=ing.unit,
                category=ing.category,
                # The provider's original phrasing ("2 boneless chicken breasts") is worth keeping
                # when it says more than quantity+unit do.
                note=(ing.original_text or "")[:255] or None
                if ing.original_text != ing.name
                else None,
            )
            for ing in normalized.ingredients[:MAX_RECIPE_INGREDIENTS]
        ],
    )
    return await create_recipe(db, user_id, req, source="imported", source_id=normalized.source_id)
