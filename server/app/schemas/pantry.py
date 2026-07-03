import datetime
import uuid

from pydantic import BaseModel, Field, field_validator

from app.schemas.recipe import _validate_category

# Bulk-confirm cap: a fridge scan tops out around 40 detected items; 60 leaves room for
# manual additions on the confirm screen without letting a runaway client flood the table.
MAX_CONFIRM_ITEMS = 60
MAX_STAPLES = 100


class PantryScanItem(BaseModel):
    """One food candidate the vision model spotted. Draft data — never persisted; the user
    reviews the list on the confirmation screen before anything is written."""

    name: str
    category: str | None = None
    confidence: str = "high"  # "high" | "low" (partially visible / uncertain)


class PantryScanDraftOut(BaseModel):
    """POST /pantry/scan response: candidates plus how much to trust them."""

    items: list[PantryScanItem] = []
    low_confidence: bool = False
    note: str | None = None


class PantryItemIn(BaseModel):
    name: str
    category: str | None = None

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("item name must not be empty")
        return v.strip()[:255]

    @field_validator("category")
    @classmethod
    def category_valid(cls, v: str | None) -> str | None:
        return _validate_category(v)


class PantryItemUpdate(BaseModel):
    name: str | None = None
    category: str | None = None

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str | None) -> str | None:
        if v is None:
            return None
        if not v.strip():
            raise ValueError("item name must not be empty")
        return v.strip()[:255]

    @field_validator("category")
    @classmethod
    def category_valid(cls, v: str | None) -> str | None:
        return _validate_category(v)


class PantryItemOut(BaseModel):
    model_config = {"from_attributes": True}

    id: uuid.UUID
    name: str
    category: str | None
    source: str
    created_at: datetime.datetime


class PantryConfirmRequest(BaseModel):
    """The confirmation screen's bulk write. ``replace`` swaps the whole pantry for this
    batch (a fresh full-fridge scan); the default merges into what's already there."""

    items: list[PantryItemIn] = Field(max_length=MAX_CONFIRM_ITEMS)
    replace: bool = False


class StaplesOut(BaseModel):
    """``confirmed`` False means the user is still on the seeded defaults and the client
    should show the one-time review sheet."""

    confirmed: bool
    staples: list[str]


class StaplesPut(BaseModel):
    staples: list[str] = Field(max_length=MAX_STAPLES)

    @field_validator("staples")
    @classmethod
    def names_nonempty(cls, v: list[str]) -> list[str]:
        cleaned = [s.strip()[:255] for s in v if s.strip()]
        return cleaned


class CookbookSuggestion(BaseModel):
    """A saved recipe the pantry (plus staples) can mostly or fully cover."""

    recipe_id: uuid.UUID
    name: str
    image_url: str | None = None
    total: int
    matched: int
    missing: list[str] = []


class ExternalSuggestion(BaseModel):
    """A Spoonacular findByIngredients hit; ``source_id`` feeds the existing
    GET /recipes/discover/{source_id} preview and POST /recipes/import."""

    source_id: str
    title: str
    image: str | None = None
    used_count: int
    missed_count: int
    missing: list[str] = []


class PantrySuggestionsOut(BaseModel):
    """``external_available`` False means Spoonacular is unconfigured or was unreachable —
    the external section is absent, not an error; local matches always survive."""

    cookbook: list[CookbookSuggestion] = []
    external: list[ExternalSuggestion] = []
    external_available: bool = False
