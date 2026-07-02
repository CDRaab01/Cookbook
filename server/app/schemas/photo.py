from pydantic import BaseModel, Field

from app.schemas.recipe import PreviewIngredientOut


class RecipePhotoDraft(BaseModel):
    """What the vision model returns for one recipe photo. Never persisted directly — the
    client feeds this into the normal recipe editor for the user to review and save."""

    name: str = "Untitled recipe"
    servings: int | None = Field(default=None, ge=1, le=1000)
    prep_minutes: int | None = Field(default=None, ge=0, le=10000)
    cook_minutes: int | None = Field(default=None, ge=0, le=10000)
    ingredients: list[PreviewIngredientOut] = []
    steps: list[str] = []


class RecipePhotoDraftOut(RecipePhotoDraft):
    """The endpoint's response: the draft plus how much to trust it."""

    low_confidence: bool = False
    note: str | None = None
