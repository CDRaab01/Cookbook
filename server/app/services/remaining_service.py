"""Plate-awareness: reading today's remaining macros to badge recipes that fit (federated
awareness Link F, CROSS-APP.md rule 7).

Cookbook already computes a recipe's per-serving kcal via Plate's resolve-foods; this adds the
other half — how many kcal the household has left today — so a recipe's nutrition view can show a
deterministic "fits what's left" badge. No LLM. Best-effort and read-only: any failure, an unset
integration, or Plate returning 404 (no active goal) yields None, and the badge simply doesn't
appear (rule 3 / rule 7 — degrade to absence, never an error).
"""

import datetime
import logging
from dataclasses import dataclass

import httpx

from app.config import settings
from app.services.cross_app_token import cross_app_configured, fetch_cross_app_token

log = logging.getLogger(__name__)

# A recipe "fits" if its per-serving kcal is within this fraction over what's left — a little
# headroom so a 610-kcal serving still fits a 600-kcal remainder rather than reading as "over".
FIT_TOLERANCE = 1.05


@dataclass(frozen=True)
class Remaining:
    kcal: int
    protein_g: int
    carbs_g: int
    fat_g: int


def fits(recipe_kcal_per_serving: float, remaining: Remaining) -> bool:
    """Deterministic: does one serving fit within today's remaining kcal (+ small tolerance)?"""
    if remaining.kcal <= 0:
        return False
    return recipe_kcal_per_serving <= remaining.kcal * FIT_TOLERANCE


async def fetch_remaining(
    email: str, day: datetime.date, *, client: httpx.AsyncClient | None = None
) -> Remaining | None:
    """Today's remaining macros from Plate's ``GET /cross-app/remaining?date=``. None when the
    integration is off, Plate is unreachable, or the user has no active goal (Plate 404s)."""
    if not settings.plate_base_url or not cross_app_configured():
        return None
    try:
        token = await fetch_cross_app_token(email, client=client)
        request = lambda c: c.get(  # noqa: E731 - mirrors the other cross-app callers
            f"{settings.plate_base_url.rstrip('/')}/cross-app/remaining",
            params={"date": day.isoformat()},
            headers={"Authorization": f"Bearer {token}"},
        )
        if client is not None:
            resp = await request(client)
        else:
            async with httpx.AsyncClient(timeout=settings.plate_timeout_seconds) as owned:
                resp = await request(owned)
        if resp.status_code == 404:  # no active goal on Plate — nothing to fit against
            return None
        resp.raise_for_status()
        data = resp.json()
        return Remaining(
            kcal=int(data["kcal_remaining"]),
            protein_g=int(data["protein_g_remaining"]),
            carbs_g=int(data["carbs_g_remaining"]),
            fat_g=int(data["fat_g_remaining"]),
        )
    except Exception as exc:  # noqa: BLE001 - rule 7: degrade to absence, never propagate
        log.warning("remaining lookup failed for %s on %s: %s", email, day, exc)
        return None
