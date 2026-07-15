import datetime
import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.plan import (
    PlanEntryCreate,
    PlanEntryOut,
    PlanEntryUpdate,
    PlanToListRequest,
    PlanToListResult,
)
from app.security import CurrentUser
from app.services.plan_service import add_entry, delete_entry, get_plan, plan_to_list, set_eaten

router = APIRouter(prefix="/plan", tags=["plan"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


@router.get("", response_model=list[PlanEntryOut])
async def read_plan(
    current_user: CurrentUser,
    db: DbSession,
    start: Annotated[datetime.date, Query()],
    end: Annotated[datetime.date, Query()],
    list_id: Annotated[
        uuid.UUID | None, Query(description="Plan for this list; omit for your own")
    ] = None,
):
    """The plan for a list — your own by default, or a shared list you're a member of."""
    return await get_plan(db, current_user.id, start, end, list_id=list_id)


@router.post("", response_model=PlanEntryOut, status_code=status.HTTP_201_CREATED)
async def create_entry(
    req: PlanEntryCreate,
    current_user: CurrentUser,
    db: DbSession,
    list_id: Annotated[uuid.UUID | None, Query()] = None,
):
    """Plan a recipe (or a free-text note) into a date+slot on a list (own or shared)."""
    return await add_entry(db, current_user.id, req, list_id=list_id)


@router.patch("/{entry_id}", response_model=PlanEntryOut)
async def update_entry(
    entry_id: uuid.UUID, req: PlanEntryUpdate, current_user: CurrentUser, db: DbSession
):
    """Confirm (or un-confirm) that you ate this meal, at a portion — per-user. A recipe
    confirmation logs to your Plate diary; Plate's coach also reads eaten via cross-app."""
    return await set_eaten(db, current_user, entry_id, req.eaten, req.servings)


@router.delete("/{entry_id}", status_code=status.HTTP_204_NO_CONTENT)
async def remove_entry(entry_id: uuid.UUID, current_user: CurrentUser, db: DbSession):
    await delete_entry(db, current_user.id, entry_id)


@router.post("/to-list", response_model=PlanToListResult)
async def send_to_list(req: PlanToListRequest, current_user: CurrentUser, db: DbSession):
    """The planner's payoff: every planned recipe in the range lands on a shopping list
    through the normal merge math — a week of dinners becomes one aggregated list."""
    return await plan_to_list(db, current_user.id, req)
