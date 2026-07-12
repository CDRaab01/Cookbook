"""Cookbook's cross-app provider surface (federated awareness Link A, CROSS-APP.md rule 7).

``GET /cross-app/cooked?start=&end=`` tells a sister app how often the household actually cooked
— consumed by Magpie's budget coach, which can see dining-out spend but not the cooking that
would tame it. RS256-only (`get_cross_app_user` — Cookbook's provider surfaces post-date the
HS256 retirement plan), read-only, aggregates only (dates + recipe names, never more), and
degrade-to-absence on the consumer side: Magpie treats any failure here as "Cookbook didn't say".
"""

import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, Query, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.limiter import limiter
from app.models.user import User
from app.security import get_cross_app_user
from app.services.cook_event_service import get_cooked_range
from app.services.plan_service import get_plan

router = APIRouter(prefix="/cross-app", tags=["cross-app"])


@router.get("/cooked")
@limiter.limit("60/minute")
async def cooked_range(
    request: Request,
    current_user: Annotated[User, Depends(get_cross_app_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    start: datetime.date = Query(..., description="Range start (inclusive)"),
    end: datetime.date = Query(..., description="Range end (inclusive)"),
):
    return await get_cooked_range(db, current_user.id, start, end)


@router.get("/plan")
@limiter.limit("60/minute")
async def planned_meals(
    request: Request,
    current_user: Annotated[User, Depends(get_cross_app_user)],
    db: Annotated[AsyncSession, Depends(get_db)],
    date: datetime.date = Query(..., description="The day to report planned meals for"),
):
    """Tonight's plan for Plate's coach (federated awareness Link E) — slot + recipe name only,
    reusing `plan_service.get_plan` (single day = start==end). No macros: Plate does nutrition,
    and CROSS-APP.md #2's "planned dinner ≈ N kcal" belongs to Plate reasoning over the name, not
    Cookbook guessing here. Free-text notes ride along as the entry's name."""
    entries = await get_plan(db, current_user.id, date, date)
    return {
        "date": date.isoformat(),
        "entries": [
            {"slot": e.slot, "recipe_name": e.recipe_name or e.note} for e in entries
        ],
    }
