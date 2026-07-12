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
