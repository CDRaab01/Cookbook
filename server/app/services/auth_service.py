import asyncio
import logging
import secrets
import smtplib
from datetime import datetime, timedelta, timezone
from email.mime.multipart import MIMEMultipart
from email.mime.text import MIMEText

from fastapi import HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import settings
from app.models.user import User
from app.schemas.auth import (
    ForgotPasswordRequest,
    LoginRequest,
    RegisterRequest,
    ResetPasswordRequest,
    TokenResponse,
)
from app.security import (
    create_access_token,
    create_refresh_token,
    hash_password,
    verify_password,
)

log = logging.getLogger(__name__)

RESET_TOKEN_EXPIRY_MINUTES = 60


async def register_user(db: AsyncSession, req: RegisterRequest) -> TokenResponse:
    # Gate registration behind an invite code on public deployments. Checked before any DB
    # lookup so an un-invited caller can't probe which emails are already registered.
    required_code = settings.registration_invite_code
    if required_code:
        provided = req.invite_code or ""
        if not secrets.compare_digest(provided, required_code):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="A valid invite code is required",
            )

    result = await db.execute(select(User).where(User.email == req.email))
    if result.scalar_one_or_none():
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="Email already registered")
    user = User(name=req.name, email=req.email, hashed_password=hash_password(req.password))
    db.add(user)
    await db.commit()
    await db.refresh(user)
    return TokenResponse(
        access_token=create_access_token(str(user.id)),
        refresh_token=create_refresh_token(str(user.id)),
    )


async def login_user(db: AsyncSession, req: LoginRequest) -> TokenResponse:
    result = await db.execute(select(User).where(User.email == req.email))
    user = result.scalar_one_or_none()
    if not user or not verify_password(req.password, user.hashed_password):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid credentials")
    return TokenResponse(
        access_token=create_access_token(str(user.id)),
        refresh_token=create_refresh_token(str(user.id)),
    )


async def forgot_password(db: AsyncSession, req: ForgotPasswordRequest) -> None:
    result = await db.execute(select(User).where(User.email == req.email))
    user = result.scalar_one_or_none()
    # Always return success to avoid leaking whether an email is registered
    if user is None:
        return

    token = secrets.token_urlsafe(32)  # 256-bit URL-safe token
    user.reset_token = token
    user.reset_token_expires_at = datetime.now(timezone.utc) + timedelta(
        minutes=RESET_TOKEN_EXPIRY_MINUTES
    )
    await db.commit()

    await _deliver_reset_token(req.email, token)


async def reset_password(db: AsyncSession, req: ResetPasswordRequest) -> None:
    result = await db.execute(select(User).where(User.reset_token == req.token))
    user = result.scalar_one_or_none()
    if user is None or user.reset_token_expires_at is None:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid or expired reset code"
        )

    expires = user.reset_token_expires_at
    if expires.tzinfo is None:
        expires = expires.replace(tzinfo=timezone.utc)
    if datetime.now(timezone.utc) > expires:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid or expired reset code"
        )

    user.hashed_password = hash_password(req.new_password)
    user.reset_token = None
    user.reset_token_expires_at = None
    await db.commit()


async def _deliver_reset_token(email: str, token: str) -> None:
    if settings.smtp_host and settings.smtp_user and settings.smtp_password:
        try:
            await asyncio.to_thread(_send_email_blocking, email, token)
        except Exception as exc:
            log.error("Failed to send reset email to %s: %s", email, exc)
    else:
        log.warning(
            "SMTP not configured — password reset code for %s: %s (valid %d min)",
            email,
            token,
            RESET_TOKEN_EXPIRY_MINUTES,
        )


def _send_email_blocking(to: str, token: str) -> None:
    plain = (
        f"Your Cookbook password reset code is:\n\n"
        f"  {token}\n\n"
        f"Open the Cookbook app, tap 'Forgot password?', then enter the code above.\n"
        f"It expires in {RESET_TOKEN_EXPIRY_MINUTES} minutes.\n\n"
        "If you didn't request this, you can safely ignore this email."
    )
    html = f"""\
<html><body style="font-family:sans-serif;max-width:480px;margin:0 auto;padding:24px">
  <h2 style="margin-bottom:4px">Reset your Cookbook password</h2>
  <p>Enter the code below in the Cookbook app to set a new password.</p>
  <div style="background:#f4f4f5;border-radius:8px;padding:20px 24px;margin:20px 0;text-align:center">
    <code style="font-size:1.4rem;letter-spacing:.05em;word-break:break-all">{token}</code>
  </div>
  <p style="color:#6b7280;font-size:.9rem">
    This code expires in <strong>{RESET_TOKEN_EXPIRY_MINUTES} minutes</strong>.<br>
    If you didn't request a password reset you can safely ignore this email.
  </p>
</body></html>"""

    msg = MIMEMultipart("alternative")
    msg["Subject"] = "Cookbook — password reset code"
    msg["From"] = settings.smtp_from
    msg["To"] = to
    msg.attach(MIMEText(plain, "plain"))
    msg.attach(MIMEText(html, "html"))

    if settings.smtp_use_ssl:
        ctx = smtplib.SMTP_SSL(settings.smtp_host, settings.smtp_port)
        with ctx as smtp:
            smtp.login(settings.smtp_user, settings.smtp_password)
            smtp.sendmail(settings.smtp_from, [to], msg.as_string())
    else:
        with smtplib.SMTP(settings.smtp_host, settings.smtp_port) as smtp:
            smtp.starttls()
            smtp.login(settings.smtp_user, settings.smtp_password)
            smtp.sendmail(settings.smtp_from, [to], msg.as_string())
