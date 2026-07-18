"""Shared guard for URLs the server is about to fetch on a user's behalf.

Extracted from the recipe URL-import path (v0.2) so the shopping-link title fetch reuses the
exact same rules: http(s) only, no localhost/private-address literals. Deliberately does not
resolve DNS — a public hostname pointing at a private IP passes (pre-existing accepted
exposure for this single-household deployment).
"""

import ipaddress
from urllib.parse import urlparse


def validate_public_http_url(url: str) -> str | None:
    """The normalized URL when it is safe to fetch, else None."""
    parsed = urlparse(url.strip())
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        return None
    host = parsed.hostname.casefold()
    if host in ("localhost",) or host.endswith((".local", ".internal")):
        return None
    try:
        if ipaddress.ip_address(host).is_private or ipaddress.ip_address(host).is_loopback:
            return None
    except ValueError:
        pass  # a hostname, not an IP literal
    return parsed.geturl()
