"""Best-effort product-page preview for a pasted shopping link (v0.5 title, v0.6 image).

A pasted product URL ("https://www.meijer.com/…/haakaa-…/942006020367.html?gclid=…") deserves
a human name and a thumbnail. This fetches the page once (same SSRF guard, browser UA, and size
cap as the recipe URL import) and extracts:

- a **title**: JSON-LD ``Product.name`` first (retail pages publish it for search engines),
  then ``og:title``, then ``<title>``;
- an **image**: JSON-LD ``Product.image`` (string / list / ImageObject), then ``og:image``.

Every failure — unsafe URL, network error, non-2xx, oversize page, nothing extractable —
degrades to an empty :class:`LinkPreview`; the caller falls back to a slug-derived name
(:func:`app.lists.link_items.name_from_url`). Never raises: a missing preview must not block
adding the item.
"""

import html as html_lib
import logging
import re
from dataclasses import dataclass

import httpx

from app.config import settings
from app.recipes_ext.jsonld import BROWSER_HEADERS, MAX_PAGE_BYTES, extract_jsonld_blocks
from app.services.url_guard import validate_public_http_url

log = logging.getLogger(__name__)

MAX_IMAGE_URL_LENGTH = 2048

_OG_TITLE_RE = re.compile(
    r"<meta\s+[^>]*?(?:property\s*=\s*[\"']og:title[\"'][^>]*?content\s*=\s*[\"']([^\"']+)[\"']"
    r"|content\s*=\s*[\"']([^\"']+)[\"'][^>]*?property\s*=\s*[\"']og:title[\"'])",
    re.IGNORECASE | re.DOTALL,
)
_OG_IMAGE_RE = re.compile(
    r"<meta\s+[^>]*?(?:property\s*=\s*[\"']og:image[\"'][^>]*?content\s*=\s*[\"']([^\"']+)[\"']"
    r"|content\s*=\s*[\"']([^\"']+)[\"'][^>]*?property\s*=\s*[\"']og:image[\"'])",
    re.IGNORECASE | re.DOTALL,
)
_TITLE_RE = re.compile(r"<title[^>]*>(.*?)</title>", re.IGNORECASE | re.DOTALL)

# Site-name suffixes ("Haakaa Ladybug Milk Collectors | Meijer") get dropped: split on the
# common separators and keep the first non-empty part.
_SITE_SUFFIX_SEPARATORS = (" | ", " – ", " — ")


@dataclass
class LinkPreview:
    """What a product page yielded. Either field may be None (nothing usable found)."""

    title: str | None = None
    image_url: str | None = None


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


def _first_image_url(value) -> str | None:
    """JSON-LD ``image`` is a string, a list, or an ImageObject ({"url": …}) — pull the first
    usable URL out of any of those shapes."""
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        url = value.get("url")
        return url if isinstance(url, str) else None
    if isinstance(value, list):
        for entry in value:
            url = _first_image_url(entry)
            if url is not None:
                return url
    return None


def _product_image(html: str) -> str | None:
    for payload in extract_jsonld_blocks(html):
        node = _find_product_node(payload)
        if node is not None:
            url = _first_image_url(node.get("image"))
            if url:
                return url
    return None


def _og_image(html: str) -> str | None:
    match = _OG_IMAGE_RE.search(html)
    if match is None:
        return None
    return match.group(1) or match.group(2)


def _clean_image_url(raw: str | None) -> str | None:
    """Only a plain public http(s) image URL survives — a thumbnail the client will fetch."""
    if not raw:
        return None
    url = html_lib.unescape(raw.strip())
    if not url.startswith(("http://", "https://")) or len(url) > MAX_IMAGE_URL_LENGTH:
        return None
    return url


async def resolve_link_preview(url: str, *, client: httpx.AsyncClient | None = None) -> LinkPreview:
    """The page's title + thumbnail (either may be None). Never raises; empty on any failure."""
    safe_url = validate_public_http_url(url)
    if safe_url is None:
        return LinkPreview()  # the link is still stored; we just don't fetch it
    try:
        if client is not None:
            resp = await client.get(safe_url, headers=BROWSER_HEADERS, follow_redirects=True)
        else:
            async with httpx.AsyncClient(timeout=settings.link_title_timeout_seconds) as owned:
                resp = await owned.get(safe_url, headers=BROWSER_HEADERS, follow_redirects=True)
        resp.raise_for_status()
    except httpx.HTTPError as exc:
        log.info("link-preview: fetch failed for %s: %s", safe_url, exc)
        return LinkPreview()
    if len(resp.content) > MAX_PAGE_BYTES:
        log.info("link-preview: page too large (%d bytes): %s", len(resp.content), safe_url)
        return LinkPreview()
    html = resp.text
    raw_title = _product_name(html) or _og_title(html) or _html_title(html)
    title = _clean_title(raw_title) if raw_title else None
    image_url = _clean_image_url(_product_image(html) or _og_image(html))
    return LinkPreview(title=title, image_url=image_url)
