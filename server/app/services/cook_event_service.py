"""Cooked-meal history reads (federated awareness Link A — consumed by Magpie's budget coach).

"How often did the household actually cook?" is the lever behind dining-out spend, and Magpie can
see the spend but not the cooking. This aggregates `CookEvent` rows (written by
`recipe_service.mark_cooked`) into a small, stable range summary: dates and recipe names only —
never quantities, notes, or anything else Cookbook knows.
"""

import datetime
import uuid

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.cook_event import CookEvent
from app.models.recipe import Recipe

# Same discipline as plan_service.MAX_RANGE_DAYS and Spotter's range form: a consumer asking for
# more than a month is a different feature (export), not an awareness summary.
MAX_RANGE_DAYS = 31


async def get_cooked_range(
    db: AsyncSession, user_id: uuid.UUID, start: datetime.date, end: datetime.date
) -> dict:
    """Cook events in [start, end] (by the event's local date), oldest first."""
    if end < start:
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, "end must be on or after start")
    if (end - start).days + 1 > MAX_RANGE_DAYS:
        raise HTTPException(
            status.HTTP_422_UNPROCESSABLE_ENTITY, f"range is capped at {MAX_RANGE_DAYS} days"
        )

    window_start = datetime.datetime(
        start.year, start.month, start.day, tzinfo=datetime.timezone.utc
    )
    window_end = datetime.datetime(
        end.year, end.month, end.day, tzinfo=datetime.timezone.utc
    ) + datetime.timedelta(days=1)

    rows = (
        await db.execute(
            select(CookEvent.cooked_at, Recipe.name)
            .join(Recipe, CookEvent.recipe_id == Recipe.id)
            .where(
                CookEvent.user_id == user_id,
                CookEvent.cooked_at >= window_start,
                CookEvent.cooked_at < window_end,
            )
            .order_by(CookEvent.cooked_at)
        )
    ).all()

    events = [
        {"date": cooked_at.date().isoformat(), "recipe_name": name} for cooked_at, name in rows
    ]
    return {
        "start": start.isoformat(),
        "end": end.isoformat(),
        "count": len(events),
        "distinct_recipes": len({e["recipe_name"] for e in events}),
        "events": events,
    }
