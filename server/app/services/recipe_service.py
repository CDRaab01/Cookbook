"""Recipe CRUD (CLAUDE.md §4, Phase 2).

A recipe is a named set of ordered steps + ordered free-text ingredients. Children are replaced
wholesale on update (delete-orphan), mirroring Spotter's program service and Plate's recipe
service (parent-flush-then-children, replace-children).
"""

import datetime
import uuid

from fastapi import HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.cook_event import CookEvent
from app.models.recipe import Recipe, RecipeIngredient, RecipeStep
from app.schemas.recipe import (
    CookedOut,
    IngredientIn,
    RecipeCreate,
    RecipeOut,
    RecipeSummaryOut,
    RecipeUpdate,
)


async def load_owned_recipe(db: AsyncSession, user_id: uuid.UUID, recipe_id: uuid.UUID) -> Recipe:
    recipe = await db.get(Recipe, recipe_id)
    if recipe is None or recipe.user_id != user_id:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Recipe not found")
    return recipe


async def _reload(db: AsyncSession, recipe_id: uuid.UUID) -> Recipe:
    """Re-fetch with steps + ingredients eagerly loaded (both relationships are selectin)."""
    result = await db.execute(select(Recipe).where(Recipe.id == recipe_id))
    return result.scalar_one()


def _build_steps(texts: list[str]) -> list[RecipeStep]:
    return [RecipeStep(order=order, text=text) for order, text in enumerate(texts)]


def _build_ingredients(items: list[IngredientIn]) -> list[RecipeIngredient]:
    return [
        RecipeIngredient(
            order=order,
            name=i.name,
            quantity=i.quantity,
            unit=i.unit,
            category=i.category,
            note=i.note,
        )
        for order, i in enumerate(items)
    ]


async def _cook_stats(
    db: AsyncSession, user_id: uuid.UUID, recipe_ids: list[uuid.UUID]
) -> dict[uuid.UUID, tuple[int, datetime.datetime, float | None]]:
    """(times_cooked, last_cooked_at, avg_rating) per recipe, one grouped query for a listing.
    avg_rating is null when no cook of that recipe carried a rating (SQL avg ignores nulls)."""
    if not recipe_ids:
        return {}
    result = await db.execute(
        select(
            CookEvent.recipe_id,
            func.count(),
            func.max(CookEvent.cooked_at),
            func.avg(CookEvent.rating),
        )
        .where(CookEvent.user_id == user_id, CookEvent.recipe_id.in_(recipe_ids))
        .group_by(CookEvent.recipe_id)
    )
    return {
        recipe_id: (count, last, round(float(avg), 1) if avg is not None else None)
        for recipe_id, count, last, avg in result.all()
    }


async def mark_cooked(
    db: AsyncSession, user_id: uuid.UUID, recipe_id: uuid.UUID, rating: int | None = None
) -> CookedOut:
    """One "I made this" tap → one history row, optionally carrying a 1–5 rating."""
    await load_owned_recipe(db, user_id, recipe_id)
    db.add(CookEvent(user_id=user_id, recipe_id=recipe_id, rating=rating))
    await db.commit()
    stats = await _cook_stats(db, user_id, [recipe_id])
    count, last, avg = stats.get(recipe_id, (0, None, None))
    return CookedOut(times_cooked=count, last_cooked_at=last, avg_rating=avg)


async def unmark_cooked(db: AsyncSession, user_id: uuid.UUID, recipe_id: uuid.UUID) -> CookedOut:
    """Undo a mis-tap: drop the most recent event (404 when there is none)."""
    await load_owned_recipe(db, user_id, recipe_id)
    result = await db.execute(
        select(CookEvent)
        .where(CookEvent.user_id == user_id, CookEvent.recipe_id == recipe_id)
        .order_by(CookEvent.cooked_at.desc())
        .limit(1)
    )
    latest = result.scalar_one_or_none()
    if latest is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Nothing to undo")
    await db.delete(latest)
    await db.commit()
    stats = await _cook_stats(db, user_id, [recipe_id])
    count, last, avg = stats.get(recipe_id, (0, None, None))
    return CookedOut(times_cooked=count, last_cooked_at=last, avg_rating=avg)


def _summary(recipe: Recipe) -> RecipeSummaryOut:
    return RecipeSummaryOut(
        id=recipe.id,
        name=recipe.name,
        description=recipe.description,
        servings=recipe.servings,
        prep_minutes=recipe.prep_minutes,
        cook_minutes=recipe.cook_minutes,
        source=recipe.source,
        image_url=recipe.image_url,
        favorite=recipe.favorite,
        tags=recipe.tags or [],
        created_at=recipe.created_at,
        ingredient_count=len(recipe.ingredients),
        step_count=len(recipe.steps),
    )


async def create_recipe(
    db: AsyncSession,
    user_id: uuid.UUID,
    req: RecipeCreate,
    source: str = "manual",
    source_id: str | None = None,
) -> RecipeOut:
    recipe = Recipe(
        user_id=user_id,
        name=req.name,
        description=req.description,
        servings=req.servings,
        prep_minutes=req.prep_minutes,
        cook_minutes=req.cook_minutes,
        image_url=req.image_url,
        tags=req.tags,
        source=source,
        source_id=source_id,
    )
    recipe.steps = _build_steps(req.steps)
    recipe.ingredients = _build_ingredients(req.ingredients)
    db.add(recipe)
    await db.commit()
    return RecipeOut.model_validate(await _reload(db, recipe.id))


async def list_recipes(db: AsyncSession, user_id: uuid.UUID) -> list[RecipeSummaryOut]:
    result = await db.execute(select(Recipe).where(Recipe.user_id == user_id).order_by(Recipe.name))
    recipes = list(result.scalars().all())
    stats = await _cook_stats(db, user_id, [r.id for r in recipes])
    out = []
    for r in recipes:
        count, last, avg = stats.get(r.id, (0, None, None))
        summary = _summary(r)
        out.append(
            summary.model_copy(
                update={"times_cooked": count, "last_cooked_at": last, "avg_rating": avg}
            )
        )
    return out


async def get_recipe(db: AsyncSession, user_id: uuid.UUID, recipe_id: uuid.UUID) -> RecipeOut:
    recipe = await load_owned_recipe(db, user_id, recipe_id)
    stats = await _cook_stats(db, user_id, [recipe_id])
    count, last, avg = stats.get(recipe_id, (0, None, None))
    return RecipeOut.model_validate(recipe).model_copy(
        update={"times_cooked": count, "last_cooked_at": last, "avg_rating": avg}
    )


async def update_recipe(
    db: AsyncSession, user_id: uuid.UUID, recipe_id: uuid.UUID, req: RecipeUpdate
) -> RecipeOut:
    recipe = await load_owned_recipe(db, user_id, recipe_id)
    if req.name is not None:
        recipe.name = req.name
    if req.description is not None:
        # Clearing semantics: PATCH null means "leave untouched", so an emptied field must be
        # expressible — "" clears text, 0 clears minutes (a 0-minute prep is meaningless).
        recipe.description = req.description.strip() or None
    if req.servings is not None:
        recipe.servings = req.servings
    if req.prep_minutes is not None:
        recipe.prep_minutes = req.prep_minutes or None
    if req.cook_minutes is not None:
        recipe.cook_minutes = req.cook_minutes or None
    if req.image_url is not None:
        # Sentinel: an explicit empty string clears the image (None means "leave untouched").
        recipe.image_url = req.image_url.strip() or None
    if req.favorite is not None:
        recipe.favorite = req.favorite
    if req.tags is not None:
        recipe.tags = req.tags
    if req.notes is not None:
        recipe.notes = req.notes.strip() or None
    if req.steps is not None:
        recipe.steps = _build_steps(req.steps)  # delete-orphan clears the old rows
    if req.ingredients is not None:
        recipe.ingredients = _build_ingredients(req.ingredients)
    await db.commit()
    return RecipeOut.model_validate(await _reload(db, recipe.id))


async def delete_recipe(db: AsyncSession, user_id: uuid.UUID, recipe_id: uuid.UUID) -> None:
    recipe = await load_owned_recipe(db, user_id, recipe_id)
    await db.delete(recipe)
    await db.commit()
