import datetime
import uuid

from sqlalchemy import JSON, Boolean, DateTime, Float, ForeignKey, Integer, String, Text, func
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database import Base

# Where a recipe came from. `plate` marks rows migrated from Plate's retired recipe feature.
RECIPE_SOURCES = ("manual", "imported", "plate")

# Store-aisle buckets for ingredients and list items (CLAUDE.md §4), in default store-walk
# order. Kept as a plain string column (validated at the schema layer) rather than a DB enum so
# adding a bucket is not a migration. v0.7 widened this from 7 food-only buckets to the aisles a
# big-box store (Meijer/Walmart) is actually walked — the shopping list carries household, baby,
# etc., not just recipe ingredients. The Android DEFAULT_AISLE_ORDER must mirror this list.
STORE_CATEGORIES = (
    "produce",
    "meat",
    "deli",
    "dairy",
    "bakery",
    "frozen",
    "pantry",
    "snacks",
    "beverages",
    "household",
    "personal",
    "baby",
    "other",
)


class Recipe(Base):
    """A recipe: named, ordered steps + ordered free-text ingredients (CLAUDE.md §4).

    Ingredients are deliberately free text — no foods-table coupling (that's the coupling being
    escaped from Plate). ``plate_food_id`` on ingredients stays NULL until the Plate integration
    phase resolves matches. Parent-with-ordered-children mirrors Spotter's
    ``WorkoutProgram``/``ProgramDay`` pattern.
    """

    __tablename__ = "recipes"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    name: Mapped[str] = mapped_column(String(255))
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    servings: Mapped[int] = mapped_column(Integer, default=1)
    prep_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    cook_minutes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    source: Mapped[str] = mapped_column(String(16), default="manual")
    source_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    image_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    favorite: Mapped[bool] = mapped_column(Boolean, default=False)
    # Family mode: a shared ("family") recipe is visible + editable to the creator's household;
    # False = private to the creator. The creator alone toggles this and deletes.
    shared: Mapped[bool] = mapped_column(Boolean, default=False, server_default="false")
    # Free-text labels ("weeknight", "grill"), lowercase, stored as a JSON list.
    tags: Mapped[list | None] = mapped_column(JSON, nullable=True)
    # Personal cooking notes ("half the sugar next time") — the recipe's margin scribbles,
    # kept apart from the description so imports never overwrite them.
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime.datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    user = relationship("User", back_populates="recipes")
    steps = relationship(
        "RecipeStep",
        back_populates="recipe",
        cascade="all, delete-orphan",
        order_by="RecipeStep.order",
        lazy="selectin",
    )
    ingredients = relationship(
        "RecipeIngredient",
        back_populates="recipe",
        cascade="all, delete-orphan",
        order_by="RecipeIngredient.order",
        lazy="selectin",
    )


class RecipeStep(Base):
    __tablename__ = "recipe_steps"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    recipe_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("recipes.id", ondelete="CASCADE"), index=True
    )
    order: Mapped[int] = mapped_column(Integer)
    text: Mapped[str] = mapped_column(Text)

    recipe = relationship("Recipe", back_populates="steps")


class RecipeIngredient(Base):
    __tablename__ = "recipe_ingredients"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    recipe_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("recipes.id", ondelete="CASCADE"), index=True
    )
    order: Mapped[int] = mapped_column(Integer)
    name: Mapped[str] = mapped_column(String(255))
    quantity: Mapped[float | None] = mapped_column(Float, nullable=True)
    # Normalized lowercase ("cup", "g", "tbsp"); NULL for count-less items ("salt, to taste").
    unit: Mapped[str | None] = mapped_column(String(32), nullable=True)
    category: Mapped[str | None] = mapped_column(String(16), nullable=True)
    note: Mapped[str | None] = mapped_column(String(255), nullable=True)
    # Set by the Plate integration phase (ingredient → Plate food match); NULL until then.
    plate_food_id: Mapped[uuid.UUID | None] = mapped_column(nullable=True)

    recipe = relationship("Recipe", back_populates="ingredients")
