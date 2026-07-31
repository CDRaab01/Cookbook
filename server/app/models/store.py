"""Store profiles: a named store ("Meijer — Maysville Rd") and the order you walk its aisles.

Why this exists: the 13 :data:`~app.models.recipe.STORE_CATEGORIES` are a *portable* vocabulary —
recipes, ``item_history`` and the keyword guesser all speak it, and an item keeps its category no
matter which store you're standing in. But a real store has real aisles ("Aisle 12 — Baking"), and
two Meijers don't even agree with each other. So routing is **two layers**:

1. ``StoreAisle`` — the store's own ordered aisles. Each maps to zero or more canonical categories
   (``categories``), which is how a category-tagged item finds an aisle. A category listed by two
   aisles resolves to the first in walk order; a category no aisle claims falls to a client-side
   "Unsorted" section at the end. Nothing is ever dropped.
2. ``StorePlacement`` — the per-item exception ("peanut butter is aisle 5 at *this* Meijer"),
   keyed on ``normalize_name`` so it shares a key space with ``item_history``. Overrides the
   category mapping. Deliberately a fact about the **store**, shared with the household — unlike
   ``item_history``, which is one user's preference.

A store belongs to its creator but is reachable by their household, like shopping lists and meal
plans (``household_member_ids``). Deleting the store cascades its aisles; deleting an aisle
cascades the placements that pointed at it.
"""

import datetime
import uuid

from sqlalchemy import JSON, DateTime, ForeignKey, Integer, String, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class Store(Base):
    __tablename__ = "stores"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    # The chain ("Meijer") and which one ("Maysville Rd"). Split so the AI layout suggestion has a
    # clean chain name to reason about without the location noise.
    name: Mapped[str] = mapped_column(String(120))
    label: Mapped[str | None] = mapped_column(String(120), nullable=True)
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    aisles = relationship(
        "StoreAisle",
        back_populates="store",
        cascade="all, delete-orphan",
        order_by="StoreAisle.order",
        lazy="selectin",
    )
    placements = relationship(
        "StorePlacement",
        back_populates="store",
        cascade="all, delete-orphan",
        lazy="selectin",
    )


class StoreAisle(Base):
    """One aisle in walk order. ``name`` is whatever the sign says ("Aisle 12 — Baking",
    "Produce", "Checkout"); ``categories`` is the canonical-category list it collects."""

    __tablename__ = "store_aisles"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    store_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("stores.id", ondelete="CASCADE"), index=True
    )
    order: Mapped[int] = mapped_column(Integer, default=0)
    name: Mapped[str] = mapped_column(String(80))
    # JSON list of STORE_CATEGORIES keys. May be empty — an aisle can exist purely as a
    # placement target ("the endcap by the pharmacy") with no category claiming it.
    categories: Mapped[list | None] = mapped_column(JSON, nullable=True)

    store = relationship("Store", back_populates="aisles")


class StorePlacement(Base):
    """ "This item lives in that aisle, at this store" — learned when someone moves an item while
    the store is selected. One row per (store, normalized item name)."""

    __tablename__ = "store_placements"
    __table_args__ = (UniqueConstraint("store_id", "key", name="uq_store_placement"),)

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    store_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("stores.id", ondelete="CASCADE"), index=True
    )
    aisle_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("store_aisles.id", ondelete="CASCADE"), index=True
    )
    # normalize_name(name) — the same key space as item_history, so the client can look a row up
    # by the `key` the server already puts on every ItemOut.
    key: Mapped[str] = mapped_column(String(255), index=True)
    # The display name as last seen, for the "learned placements" list in the store editor.
    name: Mapped[str] = mapped_column(String(255))
    updated_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    store = relationship("Store", back_populates="placements")
