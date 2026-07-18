"""Discover external recipes and import one as a saved Cookbook recipe (CLAUDE.md §5).

Unlike Plate's importer (which minted Food rows), an import maps straight to free-text
``recipe_ingredients`` plus instruction steps — an imported recipe is an ordinary
:class:`~app.models.recipe.Recipe` flagged ``source='imported'`` and immediately editable.

v0.2 additions: preview-before-import (fetch the full normalized recipe without saving) and
URL import — the native JSON-LD parser first, Spoonacular's extractor as the fallback for
sites without usable markup.
"""

import hashlib
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
from app.recipes_ext.base import NormalizedRecipe, RecipeSource, RecipeSummary
from app.recipes_ext.jsonld import fetch_recipe_from_url
from app.recipes_ext.spoonacular import SpoonacularSource, normalize_information
from app.schemas.recipe import IngredientIn, RecipeCreate, RecipeOut
from app.services.recipe_service import create_recipe
from app.services.url_guard import validate_public_http_url

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


def _normalized_to_create(normalized: NormalizedRecipe) -> RecipeCreate:
    servings = normalized.servings or 1
    servings = max(SERVINGS_BOUNDS[0], min(SERVINGS_BOUNDS[1], servings))
    return RecipeCreate(
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


async def _fetch_normalized(source_id: str, source: RecipeSource | None) -> NormalizedRecipe | None:
    if source is not None:
        return await source.fetch(source_id)
    if not settings.spoonacular_api_key:
        raise _DISABLED
    async with httpx.AsyncClient(timeout=settings.external_timeout_seconds) as client:
        return await _build_source(client).fetch(source_id)


async def preview_recipe(source_id: str, *, source: RecipeSource | None = None) -> NormalizedRecipe:
    """The full recipe (ingredients + steps) for the tap-before-import preview — nothing saved."""
    normalized = await _fetch_normalized(source_id, source)
    if normalized is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Recipe not found")
    return normalized


async def import_recipe(
    db: AsyncSession,
    user_id: uuid.UUID,
    source_id: str,
    *,
    source: RecipeSource | None = None,
) -> RecipeOut:
    """Fetch an external recipe and save it as a Cookbook recipe (free-text ingredients + steps)."""
    normalized = await _fetch_normalized(source_id, source)
    if normalized is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Recipe not found")
    if not normalized.ingredients:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Recipe had no usable ingredients.",
        )
    req = _normalized_to_create(normalized)
    return await create_recipe(db, user_id, req, source="imported", source_id=normalized.source_id)


def _validate_import_url(url: str) -> str:
    """http(s) only, no localhost/private-address literals — the server fetches this URL."""
    validated = validate_public_http_url(url)
    if validated is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="That doesn't look like a public recipe URL.",
        )
    return validated


async def import_recipe_from_url(
    db: AsyncSession,
    user_id: uuid.UUID,
    url: str,
    *,
    client: httpx.AsyncClient | None = None,
    fallback_source: RecipeSource | None = None,
) -> RecipeOut:
    """Import any recipe page by URL: JSON-LD first, Spoonacular's extractor as fallback.

    ``client``/``fallback_source`` are injectable for tests. Unlike search-based import this
    works with no Spoonacular key at all — the fallback is simply skipped.
    """
    url = _validate_import_url(url)

    async def _try_jsonld(c: httpx.AsyncClient) -> NormalizedRecipe | None:
        try:
            return await fetch_recipe_from_url(url, c)
        except httpx.HTTPError as exc:
            log.info("import-url: fetch failed for %s: %s", url, exc)
            return None

    if client is not None:
        normalized = await _try_jsonld(client)
    else:
        async with httpx.AsyncClient(timeout=settings.external_timeout_seconds) as owned:
            normalized = await _try_jsonld(owned)

    if normalized is None and fallback_source is not None:
        normalized = await fallback_source.fetch(url)
    elif normalized is None and settings.spoonacular_api_key:
        async with httpx.AsyncClient(timeout=settings.external_timeout_seconds) as owned:
            source = _build_source(owned)
            try:
                resp = await owned.get(
                    f"{source._base_url}/recipes/extract",
                    params=source._params({"url": url}),
                    headers=source._headers,
                )
                resp.raise_for_status()
                normalized = normalize_information(resp.json())
                if normalized and not normalized.ingredients:
                    normalized = None
            except httpx.HTTPError as exc:
                log.info("import-url: spoonacular extract failed for %s: %s", url, exc)

    if normalized is None or not normalized.ingredients:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Couldn't find a recipe at that URL. Try the site's original recipe page.",
        )

    req = _normalized_to_create(normalized)
    # The recipe page URL is the useful provenance; keep it as the description like other imports.
    if req.description is None or not req.description.startswith("http"):
        req = req.model_copy(update={"description": url})
    source_id = "url:" + hashlib.sha1(url.encode()).hexdigest()[:40]
    return await create_recipe(db, user_id, req, source="imported", source_id=source_id)
