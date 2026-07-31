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
