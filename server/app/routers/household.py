"""Household (family mode) membership management — the single sharing surface (Settings → Family).

Operates on the real caller. Sharing the actual data (lists, plans, family recipes) happens in the
access checks via app.services.household_service.household_member_ids.
"""

import uuid
from typing import Annotated

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.household import AddMemberRequest, HouseholdMemberOut, HouseholdOut
from app.security import CurrentUser
from app.services.household_service import (
    add_member_by_email,
    leave_household,
    list_household,
    remove_member,
)

router = APIRouter(prefix="/household", tags=["household"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


async def _current_household(current_user, db: AsyncSession) -> HouseholdOut:
    household, members = await list_household(db, current_user.id)
    if household is None:
        return HouseholdOut(
            members=[
                HouseholdMemberOut(
                    user_id=current_user.id,
                    name=current_user.name,
                    email=current_user.email,
                    is_owner=True,
                )
            ],
            you_are_owner=True,
            shared=False,
        )
    return HouseholdOut(
        members=[
            HouseholdMemberOut(
                user_id=m.id,
                name=m.name,
                email=m.email,
                is_owner=(m.id == household.owner_user_id),
            )
            for m in members
        ],
        you_are_owner=(household.owner_user_id == current_user.id),
        shared=len(members) > 1,
    )


@router.get("", response_model=HouseholdOut)
async def get_my_household(current_user: CurrentUser, db: DbSession):
    return await _current_household(current_user, db)


@router.post("/members", response_model=HouseholdOut, status_code=status.HTTP_201_CREATED)
async def add_member(req: AddMemberRequest, current_user: CurrentUser, db: DbSession):
    """Share your cookbook + lists with another Cookbook user by email (they must have signed in)."""
    await add_member_by_email(db, current_user.id, req.email)
    return await _current_household(current_user, db)


@router.delete("/members/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
async def remove(user_id: uuid.UUID, current_user: CurrentUser, db: DbSession):
    await remove_member(db, current_user.id, user_id)


@router.post("/leave", status_code=status.HTTP_204_NO_CONTENT)
async def leave(current_user: CurrentUser, db: DbSession):
    await leave_household(db, current_user.id)
