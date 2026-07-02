import datetime
import uuid

from sqlalchemy import DateTime, ForeignKey, Integer, String, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class ItemHistory(Base):
    """One row per distinct grocery item a user has ever put on a list (v0.2).

    ``key`` is the merge-normalized name (app.lists.merge.normalize_name), so "Eggs" and "egg"
    share a row. Powers the add-dialog autocomplete and remembers each item's unit + category so
    a future add of "milk" lands back in dairy without asking.
    """

    __tablename__ = "item_history"
    __table_args__ = (UniqueConstraint("user_id", "key", name="ux_item_history_user_key"),)

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    key: Mapped[str] = mapped_column(String(255))
    # The most recent spelling the user actually typed/imported — what suggestions display.
    name: Mapped[str] = mapped_column(String(255))
    unit: Mapped[str | None] = mapped_column(String(32), nullable=True)
    category: Mapped[str | None] = mapped_column(String(16), nullable=True)
    use_count: Mapped[int] = mapped_column(Integer, default=1)
    last_used: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
