import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.recipe import RecipeCreate, RecipeOut, RecipeSummaryOut, RecipeUpdate
from app.security import CurrentUser
from app.services.recipe_service import (
    create_recipe,
    delete_recipe,
    get_recipe,
    list_recipes,
    update_recipe,
)

router = APIRouter(prefix="/recipes", tags=["recipes"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


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
