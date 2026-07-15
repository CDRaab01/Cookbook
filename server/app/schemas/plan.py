import datetime
import uuid

from pydantic import BaseModel, Field, field_validator

from app.limits import SCALE_BOUNDS
from app.models.meal_plan import MEAL_SLOTS


def _validate_slot(v: str) -> str:
    slot = v.strip().lower()
    if slot not in MEAL_SLOTS:
        raise ValueError(f"slot must be one of {MEAL_SLOTS}")
    return slot


class PlanEntryCreate(BaseModel):
    """Assign a recipe OR a free-text note ("Leftovers") to a date+slot — exactly one of them."""

    date: datetime.date
    slot: str
    recipe_id: uuid.UUID | None = None
    note: str | None = None
    # How much of the recipe to make this night (1.0 = as written); scales its list contribution.
    scale: float = Field(default=1.0, ge=SCALE_BOUNDS[0], le=SCALE_BOUNDS[1])

    @field_validator("slot")
    @classmethod
    def slot_valid(cls, v: str) -> str:
        return _validate_slot(v)

    @field_validator("note")
    @classmethod
    def note_trimmed(cls, v: str | None) -> str | None:
        if v is None:
            return None
        return v.strip()[:255] or None


class PlanEntryOut(BaseModel):
    id: uuid.UUID
    date: datetime.date
    slot: str
    recipe_id: uuid.UUID | None = None
    recipe_name: str | None = None
    recipe_image_url: str | None = None
    note: str | None = None
    # Cooking scale for this planned night (1.0 = as written); scales its shopping-list contribution.
    scale: float = 1.0
    # Per-user (the requesting caller's): whether they've confirmed eating this, and the portion.
    eaten: bool = False
    servings: float = 1.0


class PlanEntryUpdate(BaseModel):
    """Confirm (or un-confirm) that *you* ate this planned meal, at a portion. Per-user: on a
    shared plan each member confirms their own, and a recipe confirmation logs to their Plate diary.
    """

    eaten: bool
    # Portion eaten, scaled against the recipe's servings when logging to Plate. Ignored when
    # un-eating. Defaults to one serving.
    servings: float = Field(default=1.0, gt=0, le=100)


class PlanToListRequest(BaseModel):
    """Send every planned recipe in [start, end] to a shopping list, merged like any add."""

    start: datetime.date
    end: datetime.date
    list_id: uuid.UUID | None = None  # default list when omitted
    scale: float = Field(default=1.0, ge=SCALE_BOUNDS[0], le=SCALE_BOUNDS[1])


class PlanToListResult(BaseModel):
    recipes_added: int
    items_on_list: int
    list_id: uuid.UUID
