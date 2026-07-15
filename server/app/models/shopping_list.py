import datetime
import uuid

from sqlalchemy import (
    JSON,
    Boolean,
    DateTime,
    Float,
    ForeignKey,
    Integer,
    String,
    UniqueConstraint,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class ShoppingList(Base):
    """A persistent checklist. v1 UI shows one default list per user ("Groceries") but the
    schema supports several from the start (CLAUDE.md §4)."""

    __tablename__ = "shopping_lists"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(255))
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    user = relationship("User", back_populates="shopping_lists")
    items = relationship(
        "ShoppingListItem",
        back_populates="shopping_list",
        cascade="all, delete-orphan",
        order_by="ShoppingListItem.order",
        lazy="selectin",
    )


class ShoppingListItem(Base):
    __tablename__ = "shopping_list_items"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    list_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("shopping_lists.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(255))
    # Legacy single measure, kept in sync when `measures` holds exactly one entry (client edit
    # dialogs and older rows read these); NULL when measures are mixed.
    quantity: Mapped[float | None] = mapped_column(Float, nullable=True)
    unit: Mapped[str | None] = mapped_column(String(32), nullable=True)
    # Aggregated amounts across merges: JSON list of {"quantity", "unit"} (v0.2.1). An item you
    # buy once may be measured many ways across recipes — "2 tbsp + 2 tsp" stays honest instead
    # of pretending to sum across units.
    measures: Mapped[list | None] = mapped_column(JSON, nullable=True)
    category: Mapped[str | None] = mapped_column(String(16), nullable=True)
    checked: Mapped[bool] = mapped_column(Boolean, default=False)
    checked_at: Mapped[datetime.datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    # Provenance: which recipe put this item on the list (NULL for manual one-offs). SET NULL so
    # deleting a recipe never deletes groceries someone still needs to buy.
    recipe_id: Mapped[uuid.UUID | None] = mapped_column(
        ForeignKey("recipes.id", ondelete="SET NULL"), nullable=True
    )
    order: Mapped[int] = mapped_column(Integer, default=0)
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    shopping_list = relationship("ShoppingList", back_populates="items")


class ListMember(Base):
    """Household sharing: a user (besides the owner) who can view and edit a shopping list.

    The list's `user_id` remains the owner (rename/delete/manage-members stay owner-only); members
    get full access to the shopping actions. Membership is by suite user id, resolved from an
    invite by email (SSO links accounts by email). Deleting the list or the user cascades.
    """

    __tablename__ = "shopping_list_members"
    __table_args__ = (UniqueConstraint("list_id", "user_id", name="uq_list_member"),)

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    list_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("shopping_lists.id", ondelete="CASCADE"), index=True
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    added_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
