"""Magpie-awareness: reading the household's grocery spend for the current month to badge the
shopping list (federated awareness Link D, CROSS-APP.md rule 7).

Cookbook plans and shops; Magpie knows what was actually spent. This reads the one number the
shopping view can honestly show — grocery dollars spent so far this month — from Magpie's
``GET /cross-app/summary``. Read-only and best-effort: any failure, an unset integration, or
Magpie being unreachable (it is tailnet-only) yields None and the tile simply doesn't appear
(rule 3 / rule 7 — degrade to absence, never an error). Whole dollars only; no rows ever cross.
"""

import datetime
import logging
from dataclasses import dataclass

import httpx

from app.config import settings
from app.services.cross_app_token import cross_app_configured, fetch_cross_app_token

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class GrocerySpend:
    month: str  # ISO first-of-month, e.g. "2026-07-01"
    spent_dollars: int


def _month_bounds(day: datetime.date) -> tuple[datetime.date, datetime.date]:
    """First of ``day``'s month through ``day`` itself — spend *so far this month*."""
    return day.replace(day=1), day


async def fetch_month_grocery_spend(
    email: str, day: datetime.date, *, client: httpx.AsyncClient | None = None
) -> GrocerySpend | None:
    """Grocery dollars spent this month from Magpie's ``GET /cross-app/summary``. None when the
    integration is off or Magpie is unreachable (rule 7 — the tile then just doesn't render)."""
    if not settings.magpie_base_url or not cross_app_configured():
        return None
    start, end = _month_bounds(day)
    try:
        token = await fetch_cross_app_token(email, client=client)
        request = lambda c: c.get(  # noqa: E731 - mirrors the other cross-app callers
            f"{settings.magpie_base_url.rstrip('/')}/cross-app/summary",
            params={"start": start.isoformat(), "end": end.isoformat()},
            headers={"Authorization": f"Bearer {token}"},
        )
        if client is not None:
            resp = await request(client)
        else:
            async with httpx.AsyncClient(timeout=settings.magpie_timeout_seconds) as owned:
                resp = await request(owned)
        resp.raise_for_status()
        data = resp.json()
        return GrocerySpend(month=start.isoformat(), spent_dollars=int(data["grocery_spend"]))
    except Exception as exc:  # noqa: BLE001 - rule 7: degrade to absence, never propagate
        log.warning("grocery-spend lookup failed for %s (%s..%s): %s", email, start, end, exc)
        return None
