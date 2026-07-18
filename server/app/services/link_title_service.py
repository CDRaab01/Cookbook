"""Best-effort product-page title for a pasted shopping link (v0.5).

A URL-only add ("https://www.meijer.com/…/haakaa-…/942006020367.html?gclid=…") deserves a
human name. This fetches the page (same SSRF guard, browser UA, and size cap as the recipe
URL import) and extracts a title: JSON-LD ``Product.name`` first (retail pages publish it for
search engines), then ``og:title``, then ``<title>``. Every failure — unsafe URL, network
error, non-2xx, oversize page, nothing extractable — degrades to ``None``; the caller falls
back to a slug-derived name (:func:`app.lists.link_items.name_from_url`). Never raises: a
missing title must not block adding the item.
"""

import html as html_lib
import logging
import re

import httpx

from app.config import settings
from app.recipes_ext.jsonld import BROWSER_HEADERS, MAX_PAGE_BYTES, extract_jsonld_blocks
from app.services.url_guard import validate_public_http_url

log = logging.getLogger(__name__)

_OG_TITLE_RE = re.compile(
    r"<meta\s+[^>]*?(?:property\s*=\s*[\"']og:title[\"'][^>]*?content\s*=\s*[\"']([^\"']+)[\"']"
    r"|content\s*=\s*[\"']([^\"']+)[\"'][^>]*?property\s*=\s*[\"']og:title[\"'])",
    re.IGNORECASE | re.DOTALL,
)
_TITLE_RE = re.compile(r"<title[^>]*>(.*?)</title>", re.IGNORECASE | re.DOTALL)

# Site-name suffixes ("Haakaa Ladybug Milk Collectors | Meijer") get dropped: split on the
# common separators and keep the first non-empty part.
_SITE_SUFFIX_SEPARATORS = (" | ", " – ", " — ")


def _is_product_node(node) -> bool:
    if not isinstance(node, dict):
        return False
    node_type = node.get("@type")
    types = node_type if isinstance(node_type, list) else [node_type]
    return any(isinstance(t, str) and t.casefold() == "product" for t in types)


def _find_product_node(payload) -> dict | None:
    """Locate a Product node in a parsed JSON-LD document (handles @graph and lists) — the
    sibling of jsonld.find_recipe_node for retail pages."""
    if _is_product_node(payload):
        return payload
    if isinstance(payload, dict):
        return _find_product_node(payload.get("@graph") or [])
    if isinstance(payload, list):
        for node in payload:
            found = _find_product_node(node)
            if found is not None:
                return found
    return None


def _product_name(html: str) -> str | None:
    for payload in extract_jsonld_blocks(html):
        node = _find_product_node(payload)
        if node is not None:
            name = node.get("name")
            if isinstance(name, str) and name.strip():
                return name
    return None


def _og_title(html: str) -> str | None:
    match = _OG_TITLE_RE.search(html)
    if match is None:
        return None
    return match.group(1) or match.group(2)


def _html_title(html: str) -> str | None:
    match = _TITLE_RE.search(html)
    return match.group(1) if match else None


def _clean_title(raw: str) -> str | None:
    title = " ".join(html_lib.unescape(raw).split())
    for sep in _SITE_SUFFIX_SEPARATORS:
        if sep in title:
            title = title.split(sep)[0].strip()
    title = title.strip()
    return title[:255] or None


async def resolve_link_title(url: str, *, client: httpx.AsyncClient | None = None) -> str | None:
    """The page's product/OpenGraph/document title, or None on any failure. Never raises."""
    safe_url = validate_public_http_url(url)
    if safe_url is None:
        return None  # the link is still stored; we just don't fetch it
    try:
        if client is not None:
            resp = await client.get(safe_url, headers=BROWSER_HEADERS, follow_redirects=True)
        else:
            async with httpx.AsyncClient(timeout=settings.link_title_timeout_seconds) as owned:
                resp = await owned.get(safe_url, headers=BROWSER_HEADERS, follow_redirects=True)
        resp.raise_for_status()
    except httpx.HTTPError as exc:
        log.info("link-title: fetch failed for %s: %s", safe_url, exc)
        return None
    if len(resp.content) > MAX_PAGE_BYTES:
        log.info("link-title: page too large (%d bytes): %s", len(resp.content), safe_url)
        return None
    html = resp.text
    raw = _product_name(html) or _og_title(html) or _html_title(html)
    if raw is None:
        return None
    return _clean_title(raw)
