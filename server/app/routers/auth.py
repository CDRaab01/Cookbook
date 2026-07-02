from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request, status
from jose import JWTError, jwt
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.database import get_db
from app.limiter import limiter
from app.schemas.auth import (
    ForgotPasswordRequest,
    LoginRequest,
    RefreshRequest,
    RegisterRequest,
    ResetPasswordRequest,
    TokenResponse,
)
from app.security import create_access_token, create_refresh_token
from app.services.auth_service import (
    forgot_password,
    login_user,
    register_user,
    reset_password,
)

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/register", response_model=TokenResponse, status_code=201)
@limiter.limit("5/minute")
async def register(
    request: Request,
    req: RegisterRequest,
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await register_user(db, req)


@router.post("/login", response_model=TokenResponse)
@limiter.limit("10/minute")
async def login(
    request: Request,
    req: LoginRequest,
    db: Annotated[AsyncSession, Depends(get_db)],
):
    return await login_user(db, req)


@router.post("/refresh", response_model=TokenResponse)
@limiter.limit("10/minute")
async def refresh(request: Request, req: RefreshRequest):
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid refresh token"
    )
    try:
        payload = jwt.decode(
            req.refresh_token, settings.secret_key, algorithms=[settings.algorithm]
        )
        if payload.get("type") != "refresh":
            raise credentials_exception
        user_id: str | None = payload.get("sub")
        if not user_id:
            raise credentials_exception
    except JWTError:
        raise credentials_exception
    return TokenResponse(
        access_token=create_access_token(user_id),
        refresh_token=create_refresh_token(user_id),
    )


@router.post("/forgot-password", status_code=200)
@limiter.limit("5/minute")
async def forgot_password_endpoint(
    request: Request,
    req: ForgotPasswordRequest,
    db: Annotated[AsyncSession, Depends(get_db)],
):
    await forgot_password(db, req)
    return {"detail": "If an account with that email exists, a reset code has been sent."}


@router.post("/reset-password", status_code=200)
@limiter.limit("5/minute")
async def reset_password_endpoint(
    request: Request,
    req: ResetPasswordRequest,
    db: Annotated[AsyncSession, Depends(get_db)],
):
    await reset_password(db, req)
    return {"detail": "Password updated successfully."}
