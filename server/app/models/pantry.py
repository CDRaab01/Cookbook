import datetime
import uuid

from sqlalchemy import DateTime, ForeignKey, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base

# Where a pantry item came from: a confirmed photo scan or a manual add.
PANTRY_SOURCES = ("scan", "manual")


class PantryItem(Base):
    """Something the user has on hand. Free text like recipe ingredients (CLAUDE.md §4) —
    the pantry is a checklist of foods, not a nutrition database."""

    __tablename__ = "pantry_items"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(255))
    category: Mapped[str | None] = mapped_column(String(16), nullable=True)
    source: Mapped[str] = mapped_column(String(16), default="manual")
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    # Touched on edit/re-confirm; a future "is this pantry stale?" nudge reads this.
    updated_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )


class PantryStaple(Base):
    """One always-assumed-available staple ("salt", "olive oil"). Kept apart from pantry
    items: edited in Settings, replaced wholesale via PUT, never shown in the pantry list."""

    __tablename__ = "pantry_staples"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(255))
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
