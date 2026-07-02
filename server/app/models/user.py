import uuid
from datetime import datetime

from sqlalchemy import DateTime, String, Text
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base


class User(Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    name: Mapped[str] = mapped_column(String(255))
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    hashed_password: Mapped[str] = mapped_column(String(255))
    # Free-form JSON blob (stored as text) for future preferences; no keys defined yet.
    settings: Mapped[str | None] = mapped_column(Text, nullable=True)
    reset_token: Mapped[str | None] = mapped_column(String(64), nullable=True)
    reset_token_expires_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )

    recipes = relationship("Recipe", back_populates="user", lazy="raise")
    shopping_lists = relationship("ShoppingList", back_populates="user", lazy="raise")
