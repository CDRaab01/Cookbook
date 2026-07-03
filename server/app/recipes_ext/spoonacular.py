"""Spoonacular recipe source (server-side key, never shipped in the APK).

Ported from Plate and retargeted at Cookbook's needs: ``complexSearch`` → discovery rows, and
``/recipes/{id}/information`` (WITHOUT ``includeNutrition`` — Cookbook doesn't track macros) →
a :class:`NormalizedRecipe` built from ``extendedIngredients`` (name/amount/unit/aisle) and
``analyzedInstructions`` steps.
"""

import logging
import re

import httpx

from app.recipes_ext.base import (
    IngredientSearchHit,
    NormalizedIngredient,
    NormalizedRecipe,
    RecipeSource,
    RecipeSummary,
)

# findByIngredients caps: Spoonacular charges per searched ingredient, and past ~20 names
# extra pantry items stop changing the ranking. Missing-ingredient lists are display-only.
MAX_SEARCH_INGREDIENTS = 20
MAX_MISSING_SHOWN = 10

log = logging.getLogger(__name__)

# Spoonacular aisle → Cookbook store category (app.models.recipe.STORE_CATEGORIES). Substring
# match, first hit wins; anything unmapped falls through to None (client shows "Other").
_AISLE_MAP: list[tuple[str, str]] = [
    ("produce", "produce"),
    ("meat", "meat"),
    ("seafood", "meat"),
    ("dairy", "dairy"),
    ("cheese", "dairy"),
    ("milk", "dairy"),
    ("egg", "dairy"),
    ("bakery", "bakery"),
    ("bread", "bakery"),
    ("frozen", "frozen"),
    ("spices", "pantry"),
    ("seasoning", "pantry"),
    ("canned", "pantry"),
    ("jarred", "pantry"),
    ("pasta", "pantry"),
    ("rice", "pantry"),
    ("baking", "pantry"),
    ("oil", "pantry"),
    ("vinegar", "pantry"),
    ("condiment", "pantry"),
    ("cereal", "pantry"),
    ("nut", "pantry"),
    ("beverage", "pantry"),
]

_TAG_RE = re.compile(r"<[^>]+>")


def map_aisle(aisle: str | None) -> str | None:
    if not aisle:
        return None
    key = aisle.casefold()
    for needle, category in _AISLE_MAP:
        if needle in key:
            return category
    return None


def _amount_text(amount, unit) -> str | None:
    if amount is None:
        return None
    qty = f"{amount:g}" if isinstance(amount, (int, float)) else str(amount)
    unit = (unit or "").strip()
    return f"{qty} {unit}".strip()


def _steps(data: dict) -> list[str]:
    """analyzedInstructions steps, falling back to the flat instructions text as one step."""
    blocks = data.get("analyzedInstructions") or []
    steps: list[str] = []
    for block in blocks:
        for step in block.get("steps", []) or []:
            text = (step.get("step") or "").strip()
            if text:
                steps.append(text)
    if steps:
        return steps
    flat = _TAG_RE.sub("", data.get("instructions") or "").strip()
    return [flat] if flat else []


def normalize_information(data: dict) -> NormalizedRecipe:
    """Map a Spoonacular recipe-information payload to a NormalizedRecipe."""
    ingredients: list[NormalizedIngredient] = []
    for ing in data.get("extendedIngredients", []) or []:
        name = (ing.get("name") or "").strip()
        if not name:
            continue
        amount = ing.get("amount")
        unit = (ing.get("unit") or "").strip().lower() or None
        ingredients.append(
            NormalizedIngredient(
                name=name,
                quantity=float(amount) if isinstance(amount, (int, float)) and amount > 0 else None,
                unit=unit,
                category=map_aisle(ing.get("aisle")),
                original_text=(ing.get("original") or _amount_text(amount, unit) or "").strip()
                or None,
            )
        )
    summary = _TAG_RE.sub("", data.get("summary") or "").strip() or None
    return NormalizedRecipe(
        source_id=str(data.get("id")),
        title=(data.get("title") or "Recipe").strip(),
        ingredients=ingredients,
        steps=_steps(data),
        image=data.get("image"),
        servings=data.get("servings"),
        ready_in_minutes=data.get("readyInMinutes"),
        source_url=data.get("sourceUrl"),
        summary=summary,
    )


class SpoonacularSource(RecipeSource):
    """Spoonacular, direct or via RapidAPI.

    Direct (``api.spoonacular.com``) authenticates with an ``apiKey`` query param. When
    ``rapidapi_host`` is set the same endpoints are called on the RapidAPI host with header auth
    (``X-RapidAPI-Key`` / ``X-RapidAPI-Host``) and no ``apiKey`` param.
    """

    source_tag = "spoonacular"

    def __init__(
        self,
        client: httpx.AsyncClient,
        api_key: str,
        base_url: str,
        rapidapi_host: str | None = None,
    ):
        self._client = client
        self._api_key = api_key
        self._base_url = base_url.rstrip("/")
        self._rapidapi_host = rapidapi_host

    @property
    def _headers(self) -> dict[str, str]:
        if self._rapidapi_host:
            return {"X-RapidAPI-Key": self._api_key, "X-RapidAPI-Host": self._rapidapi_host}
        return {}

    def _params(self, extra: dict) -> dict:
        # RapidAPI authenticates via headers; direct Spoonacular via the apiKey query param.
        return extra if self._rapidapi_host else {"apiKey": self._api_key, **extra}

    async def discover(self, query: str, *, limit: int) -> list[RecipeSummary]:
        resp = await self._client.get(
            f"{self._base_url}/recipes/complexSearch",
            params=self._params({"query": query, "number": limit, "addRecipeInformation": "true"}),
            headers=self._headers,
        )
        resp.raise_for_status()
        out: list[RecipeSummary] = []
        for r in resp.json().get("results", []) or []:
            rid = r.get("id")
            if rid is None:
                continue
            out.append(
                RecipeSummary(
                    source_id=str(rid),
                    title=(r.get("title") or "").strip(),
                    image=r.get("image"),
                    ready_in_minutes=r.get("readyInMinutes"),
                    servings=r.get("servings"),
                )
            )
        return out

    async def find_by_ingredients(
        self, ingredients: list[str], *, limit: int
    ) -> list[IngredientSearchHit]:
        """Recipes makeable from a set of ingredient names. ``ranking=2`` minimizes missing
        ingredients (vs. maximizing used ones); ``ignorePantry`` keeps Spoonacular from
        double-assuming its own staples list — Cookbook sends the user's staples itself."""
        resp = await self._client.get(
            f"{self._base_url}/recipes/findByIngredients",
            params=self._params(
                {
                    "ingredients": ",".join(ingredients[:MAX_SEARCH_INGREDIENTS]),
                    "number": limit,
                    "ranking": 2,
                    "ignorePantry": "true",
                }
            ),
            headers=self._headers,
        )
        resp.raise_for_status()
        out: list[IngredientSearchHit] = []
        for r in resp.json() or []:
            rid = r.get("id")
            if rid is None:
                continue
            missing = [
                (m.get("name") or "").strip()
                for m in (r.get("missedIngredients") or [])[:MAX_MISSING_SHOWN]
                if (m.get("name") or "").strip()
            ]
            out.append(
                IngredientSearchHit(
                    source_id=str(rid),
                    title=(r.get("title") or "").strip(),
                    image=r.get("image"),
                    used_count=r.get("usedIngredientCount") or 0,
                    missed_count=r.get("missedIngredientCount") or 0,
                    missing=missing,
                )
            )
        return out

    async def fetch(self, source_id: str) -> NormalizedRecipe | None:
        resp = await self._client.get(
            f"{self._base_url}/recipes/{source_id}/information",
            params=self._params({}),
            headers=self._headers,
        )
        if resp.status_code == 404:
            return None
        resp.raise_for_status()
        return normalize_information(resp.json())
