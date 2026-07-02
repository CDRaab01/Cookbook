import datetime
import uuid

from pydantic import BaseModel, Field, field_validator

from app.limits import (
    MAX_RECIPE_INGREDIENTS,
    MAX_RECIPE_STEPS,
    MINUTES_BOUNDS,
    QUANTITY_BOUNDS,
    SERVINGS_BOUNDS,
)
from app.models.recipe import RECIPE_SOURCES, STORE_CATEGORIES


def _validate_category(v: str | None) -> str | None:
    if v is None:
        return None
    key = v.strip().lower()
    if not key:
        return None
    if key not in STORE_CATEGORIES:
        raise ValueError(f"category must be one of {STORE_CATEGORIES}")
    return key


def _normalize_unit(v: str | None) -> str | None:
    """Units are canonicalized ("Cups"/"teaspoons" ⇒ cup/tsp) so merges can't be defeated by
    spelling — applied at every entry point (manual, Spoonacular, Plate migration, JSON-LD)."""
    from app.lists.merge import canonical_unit

    return canonical_unit(v)


MAX_TAGS = 10
MAX_TAG_LENGTH = 30


def _validate_tags(v: list[str] | None) -> list[str] | None:
    """Lowercase, trimmed, deduped (order kept), bounded — tags are labels, not essays."""
    if v is None:
        return None
    cleaned: list[str] = []
    for tag in v:
        key = " ".join(tag.strip().lower().split())[:MAX_TAG_LENGTH]
        if key and key not in cleaned:
            cleaned.append(key)
    if len(cleaned) > MAX_TAGS:
        raise ValueError(f"at most {MAX_TAGS} tags")
    return cleaned


class IngredientIn(BaseModel):
    name: str
    quantity: float | None = Field(default=None, gt=QUANTITY_BOUNDS[0], le=QUANTITY_BOUNDS[1])
    unit: str | None = None
    category: str | None = None
    note: str | None = None

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("ingredient name must not be empty")
        return v.strip()

    @field_validator("unit")
    @classmethod
    def unit_normalized(cls, v: str | None) -> str | None:
        return _normalize_unit(v)

    @field_validator("category")
    @classmethod
    def category_valid(cls, v: str | None) -> str | None:
        return _validate_category(v)


class RecipeCreate(BaseModel):
    name: str
    description: str | None = None
    servings: int = Field(default=1, ge=SERVINGS_BOUNDS[0], le=SERVINGS_BOUNDS[1])
    prep_minutes: int | None = Field(default=None, ge=MINUTES_BOUNDS[0], le=MINUTES_BOUNDS[1])
    cook_minutes: int | None = Field(default=None, ge=MINUTES_BOUNDS[0], le=MINUTES_BOUNDS[1])
    image_url: str | None = None
    tags: list[str] | None = None
    steps: list[str] = Field(default=[], max_length=MAX_RECIPE_STEPS)
    ingredients: list[IngredientIn] = Field(default=[], max_length=MAX_RECIPE_INGREDIENTS)

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("name must not be empty")
        return v.strip()

    @field_validator("tags")
    @classmethod
    def tags_valid(cls, v: list[str] | None) -> list[str] | None:
        return _validate_tags(v)

    @field_validator("steps")
    @classmethod
    def steps_nonempty(cls, v: list[str]) -> list[str]:
        cleaned = [s.strip() for s in v]
        if any(not s for s in cleaned):
            raise ValueError("steps must not be empty")
        return cleaned


class RecipeUpdate(BaseModel):
    """Partial update. ``steps``/``ingredients`` replace the full child lists when provided."""

    name: str | None = None
    description: str | None = None
    servings: int | None = Field(default=None, ge=SERVINGS_BOUNDS[0], le=SERVINGS_BOUNDS[1])
    prep_minutes: int | None = Field(default=None, ge=MINUTES_BOUNDS[0], le=MINUTES_BOUNDS[1])
    cook_minutes: int | None = Field(default=None, ge=MINUTES_BOUNDS[0], le=MINUTES_BOUNDS[1])
    image_url: str | None = None
    favorite: bool | None = None
    tags: list[str] | None = None
    # Personal notes; "" clears (PATCH null = untouched, the clearing convention).
    notes: str | None = None
    steps: list[str] | None = Field(default=None, max_length=MAX_RECIPE_STEPS)
    ingredients: list[IngredientIn] | None = Field(default=None, max_length=MAX_RECIPE_INGREDIENTS)

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str | None) -> str | None:
        if v is None:
            return None
        if not v.strip():
            raise ValueError("name must not be empty")
        return v.strip()

    @field_validator("tags")
    @classmethod
    def tags_valid(cls, v: list[str] | None) -> list[str] | None:
        return _validate_tags(v)

    @field_validator("steps")
    @classmethod
    def steps_nonempty(cls, v: list[str] | None) -> list[str] | None:
        if v is None:
            return None
        cleaned = [s.strip() for s in v]
        if any(not s for s in cleaned):
            raise ValueError("steps must not be empty")
        return cleaned


class StepOut(BaseModel):
    order: int
    text: str

    model_config = {"from_attributes": True}


class IngredientOut(BaseModel):
    id: uuid.UUID
    order: int
    name: str
    quantity: float | None = None
    unit: str | None = None
    category: str | None = None
    note: str | None = None
    plate_food_id: uuid.UUID | None = None

    model_config = {"from_attributes": True}


class RecipeOut(BaseModel):
    id: uuid.UUID
    name: str
    description: str | None = None
    servings: int
    prep_minutes: int | None = None
    cook_minutes: int | None = None
    source: str
    image_url: str | None = None
    favorite: bool = False
    tags: list[str] = []
    notes: str | None = None
    times_cooked: int = 0
    last_cooked_at: datetime.datetime | None = None
    created_at: datetime.datetime
    steps: list[StepOut]
    ingredients: list[IngredientOut]

    @field_validator("tags", mode="before")
    @classmethod
    def tags_never_null(cls, v):
        return v or []

    model_config = {"from_attributes": True}

    @field_validator("source")
    @classmethod
    def source_valid(cls, v: str) -> str:
        # Defensive: rows only ever get sources from RECIPE_SOURCES; surface corruption loudly.
        if v not in RECIPE_SOURCES:
            raise ValueError(f"unexpected recipe source {v!r}")
        return v


class DiscoveredRecipe(BaseModel):
    """A recipe-discovery search hit (from the external provider)."""

    source_id: str
    title: str
    image: str | None = None
    ready_in_minutes: int | None = None
    servings: int | None = None


class RecipeImportRequest(BaseModel):
    """Import an external recipe (by its provider id) as a saved Cookbook recipe."""

    source_id: str

    @field_validator("source_id")
    @classmethod
    def source_id_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("source_id must not be empty")
        return v.strip()


class RecipeImportUrlRequest(BaseModel):
    """Import a recipe straight from a website URL (v0.2)."""

    url: str

    @field_validator("url")
    @classmethod
    def url_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("url must not be empty")
        return v.strip()


class PreviewIngredientOut(BaseModel):
    name: str
    quantity: float | None = None
    unit: str | None = None
    category: str | None = None
    note: str | None = None


class RecipePreviewOut(BaseModel):
    """Full look at a Discover hit before importing (v0.2) — nothing saved yet."""

    source_id: str
    title: str
    image: str | None = None
    servings: int | None = None
    ready_in_minutes: int | None = None
    source_url: str | None = None
    summary: str | None = None
    ingredients: list[PreviewIngredientOut]
    steps: list[str]


class RecipeSummaryOut(BaseModel):
    """List-view projection: no steps, just the counts the cards show."""

    id: uuid.UUID
    name: str
    description: str | None = None
    servings: int
    prep_minutes: int | None = None
    cook_minutes: int | None = None
    source: str
    image_url: str | None = None
    favorite: bool = False
    tags: list[str] = []
    times_cooked: int = 0
    last_cooked_at: datetime.datetime | None = None
    created_at: datetime.datetime
    ingredient_count: int
    step_count: int


class CookedOut(BaseModel):
    """Result of marking (or unmarking) a cook: the fresh aggregates."""

    times_cooked: int
    last_cooked_at: datetime.datetime | None = None
