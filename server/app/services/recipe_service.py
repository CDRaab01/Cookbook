"""Recipe CRUD (CLAUDE.md §4, Phase 2).

A recipe is a named set of ordered steps + ordered free-text ingredients. Children are replaced
wholesale on update (delete-orphan), mirroring Spotter's program service and Plate's recipe
service (parent-flush-then-children, replace-children).
"""

import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.recipe import Recipe, RecipeIngredient, RecipeStep
from app.schemas.recipe import (
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
    return [_summary(r) for r in result.scalars().all()]


async def get_recipe(db: AsyncSession, user_id: uuid.UUID, recipe_id: uuid.UUID) -> RecipeOut:
    recipe = await load_owned_recipe(db, user_id, recipe_id)
    return RecipeOut.model_validate(recipe)


async def update_recipe(
    db: AsyncSession, user_id: uuid.UUID, recipe_id: uuid.UUID, req: RecipeUpdate
) -> RecipeOut:
    recipe = await load_owned_recipe(db, user_id, recipe_id)
    if req.name is not None:
        recipe.name = req.name
    if req.description is not None:
        recipe.description = req.description
    if req.servings is not None:
        recipe.servings = req.servings
    if req.prep_minutes is not None:
        recipe.prep_minutes = req.prep_minutes
    if req.cook_minutes is not None:
        recipe.cook_minutes = req.cook_minutes
    if req.image_url is not None:
        recipe.image_url = req.image_url
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
