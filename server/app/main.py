from fastapi import FastAPI, Request, Response, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from slowapi import _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from sqlalchemy.exc import DBAPIError, IntegrityError

from app.config import settings
from app.limiter import limiter
from app.routers import auth, lists, recipes, users

# Single source for the human-facing version, reused by GET /version below.
APP_VERSION = "0.1.0"

# Interactive docs are handy locally but are an unnecessary surface on a public deployment.
app = FastAPI(
    title="Cookbook API",
    version=APP_VERSION,
    docs_url="/docs" if settings.docs_enabled else None,
    redoc_url="/redoc" if settings.docs_enabled else None,
    openapi_url="/openapi.json" if settings.docs_enabled else None,
)
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)


# Safety net for database errors that slip past request validation. Without these, a
# constraint violation (e.g. a referenced id that doesn't exist) or an un-storable value
# (e.g. a NUL byte in a text field) surfaces as an opaque 500. Map them to clean 4xx so
# clients get a structured error and the logs stay quiet. Endpoints still raise their own
# 404s first where they can; this only catches what they don't. The request session is
# rolled back by the get_db dependency teardown.
@app.exception_handler(IntegrityError)
async def integrity_error_handler(request: Request, exc: IntegrityError) -> JSONResponse:
    return JSONResponse(
        status_code=status.HTTP_409_CONFLICT,
        content={"detail": "Request conflicts with an existing or missing related record."},
    )


@app.exception_handler(DBAPIError)
async def dbapi_error_handler(request: Request, exc: DBAPIError) -> Response:
    # SQLSTATE class "22" is a *data exception* — a value the client sent that Postgres
    # can't store (e.g. a NUL byte in text). That's a 422. Anything else (operational,
    # connection, etc.) is a genuine server fault: re-raise so it 500s and is logged.
    sqlstate = getattr(getattr(exc, "orig", None), "sqlstate", None)
    if sqlstate and str(sqlstate).startswith("22"):
        return JSONResponse(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            content={"detail": "Request contains a value that cannot be stored."},
        )
    raise exc


# CORS: credentials=True is incompatible with wildcard origins (browser spec) and
# unnecessary for the Android client which uses Authorization Bearer headers.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def security_headers(request: Request, call_next) -> Response:
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["X-XSS-Protection"] = "1; mode=block"
    response.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"
    if settings.hsts_enabled:
        response.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
    return response


app.include_router(auth.router)
app.include_router(users.router)
app.include_router(recipes.router)
app.include_router(lists.router)


@app.get("/health", tags=["health"])
async def health() -> dict:
    return {"status": "ok"}


@app.get("/version", tags=["version"])
async def version() -> dict:
    # Unauthenticated (like /health) so the app can show what's running before/after login.
    return {
        "name": app.title,
        "version": APP_VERSION,
        "commit": settings.git_sha,
        "built_at": settings.built_at,
    }
