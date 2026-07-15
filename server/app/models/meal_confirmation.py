import datetime
import uuid

from sqlalchemy import (
    Boolean,
    DateTime,
    Float,
    ForeignKey,
    String,
    UniqueConstraint,
    func,
    true,
)
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class MealConfirmation(Base):
    """One person's confirmation that they ate a planned meal — per-user, so a shared household
    plan tracks each member's own eating (and their own Plate diary) independently.

    A single plan entry (shared across a list) has at most one confirmation per user. "Eaten" is
    per-person here, not a flag on the shared :class:`~app.models.meal_plan.MealPlanEntry` (that
    column is retired for reads). Confirming a recipe entry also logs it to *this* user's Plate
    diary at ``servings``; ``plate_ref`` records the correlation ref we sent so a portion change
    (re-log) or un-check (retract) can target exactly those diary entries.
    """

    __tablename__ = "meal_confirmations"
    __table_args__ = (UniqueConstraint("entry_id", "user_id", name="uq_meal_confirm_entry_user"),)

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    entry_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("meal_plan_entries.id", ondelete="CASCADE"), index=True
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    # True when this user has confirmed the meal; kept (with servings) when un-eaten so the last
    # portion is remembered — un-eating sets this False rather than deleting the row.
    eaten: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=true())
    # Servings this user ate (portion), scaled against the recipe's own servings when logging.
    servings: Mapped[float] = mapped_column(Float, nullable=False, server_default="1")
    # The source_ref sent to Plate's /cross-app/log-recipe for this confirmation; NULL when not
    # logged (Plate off, unreachable, note-only entry, or after a retract).
    plate_ref: Mapped[str | None] = mapped_column(String(128), nullable=True)
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    updated_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )
