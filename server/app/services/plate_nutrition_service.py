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
from app.services.cross_app_token import cross_app_configured, fetch_cross_app_token
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
    # Federated awareness Link F (reported by Plate): does one serving fit today's remaining kcal?
    # None means "Plate didn't say" (integration off / no active goal / unreachable) — no badge.
    fits_today: bool | None = None


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


def plate_enabled() -> bool:
    """Whether the Plate integration is wired (base URL + cross-app auth). Callers that treat the
    diary sync as best-effort (meal confirmations) check this instead of catching _DISABLED."""
    return bool(settings.plate_base_url) and cross_app_configured()


def _guard_configured() -> None:
    if not plate_enabled():
        raise _DISABLED


async def _plate_delete(
    path: str, email: str, params: dict, client: httpx.AsyncClient | None
) -> dict:
    url = settings.plate_base_url.rstrip("/") + path
    headers = {"Authorization": f"Bearer {await fetch_cross_app_token(email)}"}

    async def _do(c: httpx.AsyncClient) -> dict:
        resp = await c.delete(url, params=params, headers=headers)
        resp.raise_for_status()
        return resp.json()

    if client is not None:
        return await _do(client)
    async with httpx.AsyncClient(timeout=settings.plate_timeout_seconds) as owned:
        return await _do(owned)


async def _plate_post(
    path: str, email: str, payload: dict, client: httpx.AsyncClient | None
) -> dict:
    url = settings.plate_base_url.rstrip("/") + path
    headers = {"Authorization": f"Bearer {await fetch_cross_app_token(email)}"}

    async def _do(c: httpx.AsyncClient) -> dict:
        resp = await c.post(url, json=payload, headers=headers)
        if resp.status_code == 401:
            # Plate 401s both for a bad secret AND for an email it has no account for — the
            # latter is far more common in practice (the email is the only identity bridge).
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail=(
                    "Plate couldn't match your account. Register on Plate with this same "
                    "email address (or check that CROSS_APP_SECRET matches on both servers)."
                ),
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
    per_serving = {k: v / servings for k, v in totals.items()}

    # Link F: does one serving fit today's remaining kcal (reported by Plate)? Best-effort — None
    # when Plate is off / no goal / unreachable, and only meaningful once we've matched some
    # nutrition to fit against.
    from app.services.remaining_service import fetch_remaining, fits

    fits_today: bool | None = None
    if matched:
        remaining = await fetch_remaining(user.email, datetime.date.today(), client=client)
        if remaining is not None:
            fits_today = fits(per_serving["kcal"], remaining)

    return RecipeNutritionOut(
        items=items,
        totals=MacroTotals(**totals),
        per_serving=MacroTotals(**per_serving),
        matched_count=matched,
        total_count=len(items),
        fits_today=fits_today,
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


async def log_recipe_for_confirmation(
    email: str,
    recipe,
    *,
    servings_eaten: float,
    date: datetime.date,
    meal: str,
    client_ref: str,
    client: httpx.AsyncClient | None = None,
) -> None:
    """Log an already-loaded recipe into ``email``'s Plate diary under ``client_ref`` — the diary
    half of a per-user meal confirmation. Unlike :func:`log_recipe_to_plate` there is no ownership
    gate (the caller has already gated on plan-list membership), so a household member can log the
    planner's recipe into their own diary. Caller decides whether to swallow failures."""
    if not recipe.ingredients:
        return
    scale = servings_eaten / max(recipe.servings, 1)
    await _plate_post(
        "/cross-app/log-recipe",
        email,
        {
            "date": date.isoformat(),
            "meal": meal,
            "recipe_name": recipe.name,
            "client_ref": client_ref,
            "items": _items_payload(recipe, scale),
        },
        client,
    )


async def retract_confirmation_log(
    email: str, client_ref: str, *, client: httpx.AsyncClient | None = None
) -> None:
    """Remove the diary entries a prior confirmation created under ``client_ref`` (portion change =
    retract + re-log; un-eat = retract). Idempotent on Plate's side."""
    await _plate_delete("/cross-app/logged", email, {"client_ref": client_ref}, client)
