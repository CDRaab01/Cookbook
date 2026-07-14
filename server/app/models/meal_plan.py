import datetime
import uuid

from sqlalchemy import Boolean, Date, DateTime, ForeignKey, String, false, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base

MEAL_SLOTS = ("breakfast", "lunch", "dinner", "snack")


class MealPlanEntry(Base):
    """One planned meal (v0.3): a recipe — or a free-text note ("Leftovers") — on a date+slot.

    Deleting a recipe cascades its plan entries: a plan pointing at a recipe that no longer
    exists is noise, and the note-only form covers everything else.
    """

    __tablename__ = "meal_plan_entries"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    date: Mapped[datetime.date] = mapped_column(Date, index=True)
    slot: Mapped[str] = mapped_column(String(16))
    recipe_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("recipes.id", ondelete="CASCADE"), nullable=True
    )
    note: Mapped[str | None] = mapped_column(String(255), nullable=True)
    # Marked once the meal was actually eaten — surfaced to Plate's coach (cross-app) so it knows
    # whether a planned meal happened, not just that it was planned. Defaults to not-yet-eaten.
    eaten: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=false())
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    recipe = relationship("Recipe", lazy="selectin")
