import datetime
import uuid

from pydantic import BaseModel, Field, field_validator

from app.limits import (
    MAX_ITEM_NAME_LENGTH,
    MAX_ITEM_RAW_INPUT_LENGTH,
    MAX_LINK_URL_LENGTH,
    QUANTITY_BOUNDS,
    SCALE_BOUNDS,
)
from app.lists.merge import canonical_unit
from app.schemas.recipe import _validate_category


class ItemCreate(BaseModel):
    """A manual one-off add ("paper towels"), possibly carrying a pasted product URL.

    The raw text may exceed the stored-name cap because the service splits any URL out into
    ``link_url`` first; the 255 cap on the *cleaned* name is enforced in the service.
    """

    name: str
    quantity: float | None = Field(default=None, gt=QUANTITY_BOUNDS[0], le=QUANTITY_BOUNDS[1])
    unit: str | None = None
    category: str | None = None

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("item name must not be empty")
        if len(v.strip()) > MAX_ITEM_RAW_INPUT_LENGTH:
            raise ValueError("item name is too long")
        return v.strip()

    @field_validator("unit")
    @classmethod
    def unit_normalized(cls, v: str | None) -> str | None:
        return canonical_unit(v)

    @field_validator("category")
    @classmethod
    def category_valid(cls, v: str | None) -> str | None:
        return _validate_category(v)


class ItemUpdate(BaseModel):
    """Partial edit; the common path is toggling ``checked`` in the store.

    ``link_url`` follows the house PATCH convention (None = untouched, "" = clear). PATCH does
    no URL splitting — a URL pasted into the *name* here stays literal name text.
    """

    name: str | None = None
    quantity: float | None = Field(default=None, gt=QUANTITY_BOUNDS[0], le=QUANTITY_BOUNDS[1])
    unit: str | None = None
    category: str | None = None
    checked: bool | None = None
    link_url: str | None = None

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str | None) -> str | None:
        if v is None:
            return None
        if not v.strip():
            raise ValueError("item name must not be empty")
        if len(v.strip()) > MAX_ITEM_NAME_LENGTH:
            raise ValueError(f"item name is limited to {MAX_ITEM_NAME_LENGTH} characters")
        return v.strip()

    @field_validator("link_url")
    @classmethod
    def link_url_valid(cls, v: str | None) -> str | None:
        if v is None or v == "":
            return v  # None = untouched, "" = clear
        v = v.strip()
        if not v.startswith(("http://", "https://")):
            raise ValueError("link must be an http(s) URL")
        if len(v) > MAX_LINK_URL_LENGTH:
            raise ValueError("link URL is too long")
        return v

    @field_validator("unit")
    @classmethod
    def unit_normalized(cls, v: str | None) -> str | None:
        return canonical_unit(v)

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


class ListCreate(BaseModel):
    """A named list beyond the default ("Costco", "Party")."""

    name: str

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("list name must not be empty")
        return v.strip()[:255]


class ListRename(BaseModel):
    name: str

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("list name must not be empty")
        return v.strip()[:255]


class ListSummaryOut(BaseModel):
    """List-picker projection: name + how much is left to buy on it."""

    id: uuid.UUID
    name: str
    unchecked_count: int
    total_count: int
    # Household sharing: whether this list is shared (owner or member can see others on it), and
    # whether the current user owns it (only the owner manages members / renames / deletes).
    shared: bool = False
    is_owner: bool = True


class MemberOut(BaseModel):
    """A person on a shared list — the owner plus invited members."""

    user_id: uuid.UUID
    email: str
    name: str
    is_owner: bool


class ShareRequest(BaseModel):
    """Invite a suite user to a list by the email their account is linked by (SSO)."""

    email: str


class SuggestionOut(BaseModel):
    """One autocomplete hit for the add dialog (v0.2), from the user's item history."""

    name: str
    unit: str | None = None
    category: str | None = None


class GrocerySpendOut(BaseModel):
    """Grocery dollars spent so far this month, reported by Magpie (federated awareness Link D).

    Whole dollars, never a transaction. Absent (endpoint returns null) when the integration is off."""

    month: str  # ISO first-of-month, e.g. "2026-07-01"
    spent_dollars: int


class MeasureOut(BaseModel):
    """One aggregated amount ("2 tbsp"); unit None is a bare count ("3")."""

    quantity: float
    unit: str | None = None


class ItemOut(BaseModel):
    id: uuid.UUID
    name: str
    # Legacy single measure (populated when the aggregate has exactly one entry).
    quantity: float | None = None
    unit: str | None = None
    # The full aggregate across merges — what the row displays ("2 tbsp + 2 tsp").
    measures: list[MeasureOut] = []
    category: str | None = None
    # Product-page URL for a pasted-link item; the name stays a clean human title.
    link_url: str | None = None
    # Product thumbnail for a link item (v0.6); the client fetches it directly.
    image_url: str | None = None
    checked: bool
    checked_at: datetime.datetime | None = None
    recipe_id: uuid.UUID | None = None
    order: int
    created_at: datetime.datetime

    model_config = {"from_attributes": True}

    @field_validator("measures", mode="before")
    @classmethod
    def measures_never_null(cls, v):
        return v or []


class ListOut(BaseModel):
    id: uuid.UUID
    name: str
    items: list[ItemOut]

    model_config = {"from_attributes": True}
