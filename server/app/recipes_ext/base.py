"""External recipe sources — discover real recipes and normalize them for import.

Ported from Plate's ``recipes_ext`` (this is now its canonical home, CLAUDE.md §5) and slimmed:
Cookbook stores free-text ingredients, so no per-ingredient nutrition crosses this boundary —
just names, amounts, store-aisle categories, and instruction steps. A provider-agnostic
interface plus normalized dataclasses keeps the concrete provider (Spoonacular) swappable and
the service/tests depending only on the normalized shapes.
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass, field


@dataclass(frozen=True)
class RecipeSummary:
    """A discovery hit — enough to show a result row and import on tap."""

    source_id: str
    title: str
    image: str | None = None
    ready_in_minutes: int | None = None
    servings: int | None = None


@dataclass(frozen=True)
class IngredientSearchHit:
    """A find-by-ingredients hit: a discovery row plus how well the searched ingredients
    cover it. ``source_id`` works with the same preview/import endpoints as discovery.
    Not part of the :class:`RecipeSource` ABC — only Spoonacular supports this search."""

    source_id: str
    title: str
    image: str | None = None
    used_count: int = 0
    missed_count: int = 0
    missing: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class NormalizedIngredient:
    """One recipe ingredient as free text: name + amount + optional store category."""

    name: str
    quantity: float | None = None
    unit: str | None = None
    # One of app.models.recipe.STORE_CATEGORIES, mapped from the provider's aisle when possible.
    category: str | None = None
    # The amount as written in the recipe ("2 breasts"), kept as the ingredient note.
    original_text: str | None = None


@dataclass(frozen=True)
class NormalizedRecipe:
    """A full recipe ready to import: metadata + ingredients + instruction steps."""

    source_id: str
    title: str
    ingredients: list[NormalizedIngredient]
    steps: list[str] = field(default_factory=list)
    image: str | None = None
    servings: int | None = None
    ready_in_minutes: int | None = None
    source_url: str | None = None
    summary: str | None = None


class RecipeSource(ABC):
    """A provider of external recipes (search + fetch-one)."""

    source_tag: str = "external"

    @abstractmethod
    async def discover(self, query: str, *, limit: int) -> list[RecipeSummary]:
        """Search recipes by free-text query."""

    @abstractmethod
    async def fetch(self, source_id: str) -> NormalizedRecipe | None:
        """Fetch one recipe (with ingredients + steps) by its provider id."""
