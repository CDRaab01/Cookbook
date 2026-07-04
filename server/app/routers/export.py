"""GET /export — download all of the signed-in user's data as a JSON document (ROADMAP T3 #6)."""

import datetime
from typing import Annotated

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.limiter import limiter
from app.security import CurrentUser
from app.services.export_service import build_export

router = APIRouter(tags=["export"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


@router.get("/export")
@limiter.limit("5/minute")
async def export(request: Request, current_user: CurrentUser, db: DbSession) -> JSONResponse:
    """The user's full data export (own-session auth). Returned as a downloadable JSON file."""
    data = await build_export(db, current_user)
    filename = f"cookbook-export-{datetime.date.today().isoformat()}.json"
    return JSONResponse(
        data, headers={"Content-Disposition": f'attachment; filename="{filename}"'}
    )
