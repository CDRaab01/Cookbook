"""Recipe nutrition + log-to-diary via Plate (CLAUDE.md §6, Phase 7).

Plate is the app that knows nutrition; Cookbook just ships the recipe's free-text ingredients
across the cross-app boundary and relays Plate's best-effort math:

- **Nutrition breakdown**: POST ``/cross-app/resolve-foods`` — per-ingredient macros where Plate
  found a match, flagged (not guessed) where it didn't. Matched Plate food ids are persisted on
  the ingredients (``plate_food_id``) as a bookkeeping trail.
- **Log to diary**: POST ``/cross-app/log-recipe`` — the eaten share (``servings_eaten`` of the
  recipe's servings) lands as snapshotted entries in the chosen meal.

Numbers are estimates the user sees labeled as such — the Plate photo-logging rule.
"""

import datetime
import logging
import uuid

import httpx
from fastapi import HTTPException, status
from pydantic import BaseModel, Field, field_validator
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.services.plate_migration_service import mint_cross_app_token
from app.services.recipe_service import load_owned_recipe

log = logging.getLogger(__name__)

MEALS = ("breakfast", "lunch", "dinner", "snack")

_DISABLED = HTTPException(
    status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
    detail="Plate integration is not configured (set PLATE_BASE_URL and CROSS_APP_SECRET).",
)


class IngredientNutritionOut(BaseModel):
    name: str
    matched: bool
    kcal: float | None = None
    protein_g: float | None = None
    carbs_g: float | None = None
    fat_g: float | None = None


class MacroTotals(BaseModel):
    kcal: float
    protein_g: float
    carbs_g: float
    fat_g: float


class RecipeNutritionOut(BaseModel):
    items: list[IngredientNutritionOut]
    totals: MacroTotals
    per_serving: MacroTotals
    matched_count: int
    total_count: int


class LogToPlateRequest(BaseModel):
    date: datetime.date
    meal: str
    servings_eaten: float = Field(default=1.0, gt=0, le=100)

    @field_validator("meal")
    @classmethod
    def meal_valid(cls, v: str) -> str:
        meal = v.strip().lower()
        if meal not in MEALS:
            raise ValueError(f"meal must be one of {MEALS}")
        return meal


class LogToPlateResult(BaseModel):
    logged: int
    skipped: int


def _guard_configured() -> None:
    if not settings.plate_base_url or not settings.cross_app_secret:
        raise _DISABLED


async def _plate_post(
    path: str, email: str, payload: dict, client: httpx.AsyncClient | None
) -> dict:
    url = settings.plate_base_url.rstrip("/") + path
    headers = {"Authorization": f"Bearer {mint_cross_app_token(email)}"}

    async def _do(c: httpx.AsyncClient) -> dict:
        resp = await c.post(url, json=payload, headers=headers)
        if resp.status_code == 401:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail="Plate rejected the cross-app token (secrets out of sync?).",
            )
        resp.raise_for_status()
        return resp.json()

    if client is not None:
        return await _do(client)
    async with httpx.AsyncClient(timeout=settings.plate_timeout_seconds) as owned:
        return await _do(owned)


def _items_payload(recipe, scale: float = 1.0) -> list[dict]:
    return [
        {
            "name": ing.name,
            "quantity": None if ing.quantity is None else round(ing.quantity * scale, 4),
            "unit": ing.unit,
        }
        for ing in recipe.ingredients
    ]


async def get_recipe_nutrition(
    db: AsyncSession,
    user,
    recipe_id: uuid.UUID,
    *,
    client: httpx.AsyncClient | None = None,
) -> RecipeNutritionOut:
    _guard_configured()
    recipe = await load_owned_recipe(db, user.id, recipe_id)
    if not recipe.ingredients:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Recipe has no ingredients"
        )

    body = await _plate_post(
        "/cross-app/resolve-foods", user.email, {"items": _items_payload(recipe)}, client
    )
    resolved = body.get("items") or []

    items: list[IngredientNutritionOut] = []
    totals = {"kcal": 0.0, "protein_g": 0.0, "carbs_g": 0.0, "fat_g": 0.0}
    matched = 0
    for ing, res in zip(recipe.ingredients, resolved):
        out = IngredientNutritionOut(
            name=ing.name,
            matched=bool(res.get("matched")),
            kcal=res.get("kcal"),
            protein_g=res.get("protein_g"),
            carbs_g=res.get("carbs_g"),
            fat_g=res.get("fat_g"),
        )
        items.append(out)
        if out.matched:
            matched += 1
            totals["kcal"] += out.kcal or 0.0
            totals["protein_g"] += out.protein_g or 0.0
            totals["carbs_g"] += out.carbs_g or 0.0
            totals["fat_g"] += out.fat_g or 0.0
            # Persist the match as a bookkeeping trail (CLAUDE.md §4: plate_food_id).
            food_id = res.get("food_id")
            if food_id:
                try:
                    ing.plate_food_id = uuid.UUID(str(food_id))
                except ValueError:
                    pass
    if matched:
        await db.commit()

    servings = max(recipe.servings, 1)
    return RecipeNutritionOut(
        items=items,
        totals=MacroTotals(**totals),
        per_serving=MacroTotals(**{k: v / servings for k, v in totals.items()}),
        matched_count=matched,
        total_count=len(items),
    )


async def log_recipe_to_plate(
    db: AsyncSession,
    user,
    recipe_id: uuid.UUID,
    req: LogToPlateRequest,
    *,
    client: httpx.AsyncClient | None = None,
) -> LogToPlateResult:
    _guard_configured()
    recipe = await load_owned_recipe(db, user.id, recipe_id)
    if not recipe.ingredients:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Recipe has no ingredients"
        )

    # The diary gets the share actually eaten: servings_eaten of a servings-sized recipe.
    scale = req.servings_eaten / max(recipe.servings, 1)
    body = await _plate_post(
        "/cross-app/log-recipe",
        user.email,
        {
            "date": req.date.isoformat(),
            "meal": req.meal,
            "recipe_name": recipe.name,
            "items": _items_payload(recipe, scale),
        },
        client,
    )
    return LogToPlateResult(logged=body.get("logged", 0), skipped=body.get("skipped", 0))
