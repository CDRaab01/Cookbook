"""Household (family mode) membership + co-membership resolution.

``household_member_ids`` is the linchpin the access checks use: a user's shopping lists, meal plans,
and *family* recipes are reachable by anyone whose id it returns. Membership management (invite /
list / remove / leave) is owner-gated where it should be; a user belongs to at most one household.
"""

import uuid

from fastapi import HTTPException, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.household import Household, HouseholdMember
from app.models.user import User


async def household_member_ids(db: AsyncSession, user_id: uuid.UUID) -> set[uuid.UUID]:
    """Every user in ``user_id``'s household (including themselves). Just ``{user_id}`` when solo —
    so callers can always use it as the set of ids whose shared data this user may reach."""
    household_id = (
        await db.execute(
            select(HouseholdMember.household_id).where(HouseholdMember.user_id == user_id)
        )
    ).scalar_one_or_none()
    if household_id is None:
        return {user_id}
    rows = (
        (
            await db.execute(
                select(HouseholdMember.user_id).where(HouseholdMember.household_id == household_id)
            )
        )
        .scalars()
        .all()
    )
    return set(rows) | {user_id}


async def household_owner_id(db: AsyncSession, user_id: uuid.UUID) -> uuid.UUID:
    """The household owner's id for a member (the shared default list/plan lives under the owner),
    or the user themselves when solo."""
    owner = (
        await db.execute(
            select(Household.owner_user_id)
            .join(HouseholdMember, HouseholdMember.household_id == Household.id)
            .where(HouseholdMember.user_id == user_id)
        )
    ).scalar_one_or_none()
    return owner or user_id


async def _household_of(db: AsyncSession, user_id: uuid.UUID) -> Household | None:
    return (
        await db.execute(
            select(Household)
            .join(HouseholdMember, HouseholdMember.household_id == Household.id)
            .where(HouseholdMember.user_id == user_id)
        )
    ).scalar_one_or_none()


async def get_or_create_household(db: AsyncSession, user_id: uuid.UUID) -> Household:
    existing = await _household_of(db, user_id)
    if existing is not None:
        return existing
    household = Household(owner_user_id=user_id)
    db.add(household)
    await db.flush()
    db.add(HouseholdMember(household_id=household.id, user_id=user_id))
    await db.commit()
    await db.refresh(household)
    return household


async def add_member_by_email(db: AsyncSession, requester_id: uuid.UUID, email: str) -> User:
    """Owner shares the household with another Cookbook user by email (they must have signed in
    once — accounts link by email, no pending-invite state)."""
    household = await get_or_create_household(db, requester_id)
    if household.owner_user_id != requester_id:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Only the household owner can add members")
    target = (
        await db.execute(select(User).where(func.lower(User.email) == email.strip().lower()))
    ).scalar_one_or_none()
    if target is None:
        raise HTTPException(
            status.HTTP_404_NOT_FOUND,
            "No Cookbook account for that email — they need to sign in to Cookbook once first",
        )
    if target.id == requester_id:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "That's your own account")
    membership = (
        await db.execute(select(HouseholdMember).where(HouseholdMember.user_id == target.id))
    ).scalar_one_or_none()
    if membership is not None:
        if membership.household_id == household.id:
            return target
        raise HTTPException(
            status.HTTP_409_CONFLICT, "That person already shares another household"
        )
    db.add(HouseholdMember(household_id=household.id, user_id=target.id))
    await db.commit()
    return target


async def list_household(
    db: AsyncSession, user_id: uuid.UUID
) -> tuple[Household | None, list[User]]:
    household = await _household_of(db, user_id)
    if household is None:
        return None, []
    members = (
        (
            await db.execute(
                select(User)
                .join(HouseholdMember, HouseholdMember.user_id == User.id)
                .where(HouseholdMember.household_id == household.id)
            )
        )
        .scalars()
        .all()
    )
    members = sorted(members, key=lambda u: u.id != household.owner_user_id)  # owner first
    return household, list(members)


async def remove_member(db: AsyncSession, requester_id: uuid.UUID, target_id: uuid.UUID) -> None:
    household = await _household_of(db, requester_id)
    if household is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "You aren't in a household")
    if requester_id != household.owner_user_id and requester_id != target_id:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Only the owner can remove another member")
    if target_id == household.owner_user_id:
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST, "The owner can't be removed — leave to disband instead"
        )
    membership = (
        await db.execute(
            select(HouseholdMember).where(
                HouseholdMember.user_id == target_id,
                HouseholdMember.household_id == household.id,
            )
        )
    ).scalar_one_or_none()
    if membership is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Not a member of your household")
    await db.delete(membership)
    await db.commit()


async def leave_household(db: AsyncSession, user_id: uuid.UUID) -> None:
    """A member leaves; the owner leaving disbands the household. Recipes/lists are untouched —
    they were always owned by real users; they simply stop being reachable by the others."""
    household = await _household_of(db, user_id)
    if household is None:
        return
    if household.owner_user_id == user_id:
        await db.delete(household)  # cascade removes member rows
    else:
        membership = (
            await db.execute(
                select(HouseholdMember).where(
                    HouseholdMember.user_id == user_id,
                    HouseholdMember.household_id == household.id,
                )
            )
        ).scalar_one_or_none()
        if membership is not None:
            await db.delete(membership)
    await db.commit()
