from typing import Annotated

from fastapi import APIRouter, Depends, Request
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.limiter import limiter
from app.security import CurrentUser
from app.services.plate_migration_service import PlateMigrationResult, migrate_from_plate

router = APIRouter(prefix="/migrate", tags=["migrate"])

DbSession = Annotated[AsyncSession, Depends(get_db)]


@router.post("/plate", response_model=PlateMigrationResult)
@limiter.limit("5/minute")
async def migrate_plate(request: Request, current_user: CurrentUser, db: DbSession):
    """Pull the signed-in user's recipes from Plate (idempotent; re-runs skip existing).
    503 until PLATE_BASE_URL + CROSS_APP_SECRET are configured."""
    return await migrate_from_plate(db, current_user)
