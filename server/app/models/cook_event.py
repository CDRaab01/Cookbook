import datetime
import uuid

from sqlalchemy import DateTime, ForeignKey, Integer, func
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class CookEvent(Base):
    """One "I made this" tap (v0.3). History, not state: times-cooked and last-cooked are
    aggregates over these rows, and an accidental tap is undone by deleting the latest row."""

    __tablename__ = "cook_events"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    recipe_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("recipes.id", ondelete="CASCADE"), index=True
    )
    cooked_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    # Optional 1–5 "would make again" rating captured at cook time; null = rated nothing.
    rating: Mapped[int | None] = mapped_column(Integer, nullable=True)
