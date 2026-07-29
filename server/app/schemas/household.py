import uuid

from pydantic import BaseModel


class HouseholdMemberOut(BaseModel):
    user_id: uuid.UUID
    name: str
    email: str
    is_owner: bool
    status: str = "active"  # "active" | "pending" (invited, not yet accepted)


class HouseholdOut(BaseModel):
    members: list[HouseholdMemberOut]
    you_are_owner: bool
    shared: bool  # more than one ACTIVE member — the cookbook + lists are actually being shared
    # How many of YOUR recipes are still private. Drives the "share all my recipes" nudge, which
    # only makes sense when `shared` is true: joining a household shares the lists and plans, but
    # recipes stay private until their creator opts each one in, and nothing said so.
    unshared_recipe_count: int = 0


class AddMemberRequest(BaseModel):
    email: str


class InviteOut(BaseModel):
    """A household invite awaiting the caller's response (null when there is none)."""

    household_id: uuid.UUID
    owner_name: str
    owner_email: str
