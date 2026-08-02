"""Store profile schemas (v0.11).

Aisle writes are a **full replace with id preservation**: a payload row carrying an existing
``id`` updates that aisle in place (so its learned placements survive a reorder or rename), a row
without one inserts, and an aisle the payload omits is deleted along with its placements. That's
the only shape that lets the client's drag-to-reorder editor be a single idempotent PUT without
inventing per-aisle endpoints.
"""

import datetime
import uuid

from pydantic import BaseModel, Field, field_validator

from app.limits import (
    MAX_AISLE_NAME_LENGTH,
    MAX_ITEM_NAME_LENGTH,
    MAX_PLACEMENT_IMPORT_ROWS,
    MAX_RETAILER_STORE_ID_LENGTH,
    MAX_STORE_AISLES,
    MAX_STORE_NAME_LENGTH,
)
from app.schemas.recipe import _validate_category


def _validate_categories(v: list[str] | None) -> list[str]:
    """Canonical category keys, deduped, order preserved. Invalid keys are a 422 here (this is a
    deliberate write) — the AI draft parser drops them instead, because a draft is a suggestion."""
    if not v:
        return []
    out: list[str] = []
    for raw in v:
        key = _validate_category(raw)
        if key is not None and key not in out:
            out.append(key)
    return out


class AisleIn(BaseModel):
    """One aisle in the store's walk order. ``id`` present = update that row in place."""

    id: uuid.UUID | None = None
    name: str
    categories: list[str] = []

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("aisle name must not be empty")
        return v.strip()[:MAX_AISLE_NAME_LENGTH]

    @field_validator("categories")
    @classmethod
    def categories_valid(cls, v: list[str] | None) -> list[str]:
        return _validate_categories(v)


class AislesPut(BaseModel):
    aisles: list[AisleIn] = Field(max_length=MAX_STORE_AISLES)


class StoreCreate(BaseModel):
    """``aisles`` omitted seeds the canonical walk order (one aisle per category) so a store is
    usable the moment it exists — the AI suggestion and the manual editor both just replace it."""

    name: str
    label: str | None = None
    aisles: list[AisleIn] | None = Field(default=None, max_length=MAX_STORE_AISLES)

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("store name must not be empty")
        return v.strip()[:MAX_STORE_NAME_LENGTH]

    @field_validator("label")
    @classmethod
    def label_clean(cls, v: str | None) -> str | None:
        if v is None:
            return None
        return v.strip()[:MAX_STORE_NAME_LENGTH]


class StoreUpdate(BaseModel):
    """House PATCH convention: None = untouched, "" = clear (label only; a store needs a name)."""

    name: str | None = None
    label: str | None = None
    retailer: str | None = None
    retailer_store_id: str | None = None

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str | None) -> str | None:
        if v is None:
            return None
        if not v.strip():
            raise ValueError("store name must not be empty")
        return v.strip()[:MAX_STORE_NAME_LENGTH]

    @field_validator("label")
    @classmethod
    def label_clean(cls, v: str | None) -> str | None:
        if v is None or v == "":
            return v
        return v.strip()[:MAX_STORE_NAME_LENGTH]

    @field_validator("retailer")
    @classmethod
    def retailer_clean(cls, v: str | None) -> str | None:
        if v is None or v == "":
            return v
        return v.strip().lower()[:32]

    @field_validator("retailer_store_id")
    @classmethod
    def retailer_store_id_clean(cls, v: str | None) -> str | None:
        if v is None or v == "":
            return v
        return v.strip()[:MAX_RETAILER_STORE_ID_LENGTH]


class PlacementIn(BaseModel):
    """ "Put this item in that aisle here" — the store-specific exception, learned from a move."""

    name: str
    aisle_id: uuid.UUID

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("item name must not be empty")
        return v.strip()[:MAX_ITEM_NAME_LENGTH]


class AisleOut(BaseModel):
    id: uuid.UUID
    order: int
    name: str
    categories: list[str] = []

    model_config = {"from_attributes": True}

    @field_validator("categories", mode="before")
    @classmethod
    def categories_never_null(cls, v):
        return v or []


class PlacementOut(BaseModel):
    id: uuid.UUID
    aisle_id: uuid.UUID
    key: str
    name: str

    model_config = {"from_attributes": True}


class StoreOut(BaseModel):
    """The store picker projection — no aisles, so listing stores stays one cheap read."""

    id: uuid.UUID
    name: str
    label: str | None = None
    retailer: str | None = None
    retailer_store_id: str | None = None
    is_owner: bool = True
    created_at: datetime.datetime

    model_config = {"from_attributes": True}


class StoreDetailOut(StoreOut):
    """Everything the client needs to route a list: the walk order plus the learned exceptions."""

    aisles: list[AisleOut] = []
    placements: list[PlacementOut] = []


class SuggestLayoutRequest(BaseModel):
    """Ask the local model what a given chain's aisles typically look like (Phase 4)."""

    chain: str

    @field_validator("chain")
    @classmethod
    def chain_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("store name must not be empty")
        return v.strip()[:MAX_STORE_NAME_LENGTH]


class StoreLayoutDraftOut(BaseModel):
    """A **draft** layout. Nothing is saved — the user edits it and commits via the normal
    create/aisles endpoints (the house rule: AI output is a user-confirmed draft)."""

    aisles: list[AisleIn] = []
    low_confidence: bool = False
    note: str | None = None


class UnplacedItemOut(BaseModel):
    """An item on a list that this store has no placement for — i.e. one worth looking up."""

    name: str
    key: str
    category: str | None = None
    #: What to type into the retailer's search box for this item (``lists/search_terms``). Sent by
    #: the server so a harvester never re-implements the cleaning and drifts from it — the same
    #: reason ``ItemOut.key`` is computed here. Best-effort: the harvest UI shows it editable.
    search_query: str = ""


class UnplacedOut(BaseModel):
    """The worklist for an import run.

    Exists so the harvester asks the server *what to look up* instead of deciding for itself. That
    keeps "which items still need a home here" in one place — the same place that will answer it
    again after the import, so progress is measurable rather than assumed.
    """

    store_id: uuid.UUID
    retailer: str | None = None
    retailer_store_id: str | None = None
    items: list[UnplacedItemOut] = []


class PlacementObservation(BaseModel):
    """One "I looked this up and it's in aisle X" fact, as collected from the retailer's site.

    ``aisle`` is deliberately a **free string** rather than an aisle id: the caller is reading a
    web page, not Cookbook's database, and cannot know which ``StoreAisle`` row (if any) that label
    corresponds to. Resolving label → row, creating it when it's new, is the service's job.

    ``aisle`` may be null — a service-counter item genuinely has no aisle, and recording that as a
    skip is more honest than inventing a home for it.
    """

    name: str
    aisle: str | None = None
    section: str | None = None
    #: What the retailer actually matched, when it differs from the queried name. Display only —
    #: it never becomes the item's name, because the item's name is the user's.
    matched_name: str | None = None

    @field_validator("name")
    @classmethod
    def name_nonempty(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("item name must not be empty")
        return v.strip()[:MAX_ITEM_NAME_LENGTH]

    @field_validator("aisle", "section", "matched_name")
    @classmethod
    def blank_is_absent(cls, v: str | None) -> str | None:
        """ "" and "   " mean "the page didn't say", which is the same as absent. Normalizing here
        keeps every downstream check a plain ``is None``."""
        if v is None:
            return None
        return v.strip()[:MAX_AISLE_NAME_LENGTH] or None


class PlacementImportIn(BaseModel):
    """A harvested batch. Applying it is idempotent — re-importing the same rows is a no-op."""

    observations: list[PlacementObservation] = Field(max_length=MAX_PLACEMENT_IMPORT_ROWS)
    #: Records which chain/location these came from, when the store isn't linked yet. Omitted
    #: leaves whatever the store already has.
    retailer: str | None = None
    retailer_store_id: str | None = None


class PlacementImportOut(BaseModel):
    """What the import actually did — counts of *changes*, not of input rows.

    Reporting changes rather than totals is what makes a second run readable: an unchanged
    re-import says ``placed=0``, which is the truth, instead of restating the batch size.
    """

    placed: int = 0
    aisles_created: int = 0
    #: Rows the retailer had no aisle for (service counters, items it doesn't stock). Named so the
    #: user can see *which* items still need doing by hand rather than just a count.
    skipped: list[str] = []
    store: StoreDetailOut
