import datetime
import uuid

from pydantic import BaseModel, Field, field_validator

from app.limits import QUANTITY_BOUNDS, SCALE_BOUNDS
from app.schemas.recipe import _normalize_unit, _validate_category


class ItemCreate(BaseModel):
    """A manual one-off add ("paper towels")."""

    name: str
    quantity: float | None = Field(default=None, gt=QUANTITY_BOUNDS[0], le=QUANTITY_BOUNDS[1])
    unit: str | None = None
    category: str | None = None

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("item name must not be empty")
        return v.strip()

    @field_validator("unit")
    @classmethod
    def unit_normalized(cls, v: str | None) -> str | None:
        return _normalize_unit(v)

    @field_validator("category")
    @classmethod
    def category_valid(cls, v: str | None) -> str | None:
        return _validate_category(v)


class ItemUpdate(BaseModel):
    """Partial edit; the common path is toggling ``checked`` in the store."""

    name: str | None = None
    quantity: float | None = Field(default=None, gt=QUANTITY_BOUNDS[0], le=QUANTITY_BOUNDS[1])
    unit: str | None = None
    category: str | None = None
    checked: bool | None = None

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str | None) -> str | None:
        if v is None:
            return None
        if not v.strip():
            raise ValueError("item name must not be empty")
        return v.strip()

    @field_validator("unit")
    @classmethod
    def unit_normalized(cls, v: str | None) -> str | None:
        return _normalize_unit(v)

    @field_validator("category")
    @classmethod
    def category_valid(cls, v: str | None) -> str | None:
        return _validate_category(v)


class AddRecipeRequest(BaseModel):
    """Autofill the list from a recipe's ingredients, optionally scaled by servings multiplier.

    ``force`` re-adds even when unchecked items from this recipe are already on the list (the
    client warns first — CLAUDE.md §6).
    """

    recipe_id: uuid.UUID
    scale: float = Field(default=1.0, ge=SCALE_BOUNDS[0], le=SCALE_BOUNDS[1])
    force: bool = False


class ItemOut(BaseModel):
    id: uuid.UUID
    name: str
    quantity: float | None = None
    unit: str | None = None
    category: str | None = None
    checked: bool
    checked_at: datetime.datetime | None = None
    recipe_id: uuid.UUID | None = None
    order: int
    created_at: datetime.datetime

    model_config = {"from_attributes": True}


class ListOut(BaseModel):
    id: uuid.UUID
    name: str
    items: list[ItemOut]

    model_config = {"from_attributes": True}
