from slowapi import Limiter
from slowapi.util import get_remote_address
from starlette.requests import Request

from app.config import settings


def client_key(request: Request) -> str:
    """Rate-limit key = the real client IP.

    Behind a trusted proxy (Cloudflare Tunnel, nginx) every request reaches the app from the
    proxy, so keying on the socket peer would lump all users together. When ``trust_proxy`` is
    enabled we instead use the forwarded client IP (Cloudflare's ``CF-Connecting-IP`` first, then
    the left-most ``X-Forwarded-For`` entry). Disabled by default so a directly-exposed server
    can't be spoofed via forged headers.
    """
    if settings.trust_proxy:
        cf_ip = request.headers.get("cf-connecting-ip")
        if cf_ip:
            return cf_ip
        forwarded = request.headers.get("x-forwarded-for")
        if forwarded:
            return forwarded.split(",")[0].strip()
    return get_remote_address(request)


limiter = Limiter(key_func=client_key)
