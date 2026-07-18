"""Pasted-product-link handling (v0.5) — pure, table-tested (the merge/categorize convention).

The add bar accepts anything, including "milk collector https://www.meijer.com/…?gclid=…". The
list is a list of things you buy, so the URL is split out into ``link_url`` and the name stays
human text. When the paste is URL-only, ``name_from_url`` derives a readable fallback from the
slug so the row is never a wall of tracking parameters (the title fetch in
``link_title_service`` is best-effort on top of this).
"""

import re
from urllib.parse import unquote, urlsplit

_URL_RE = re.compile(r"https?://\S+", re.IGNORECASE)

# \S+ greedily grabs closing punctuation ("(https://x.com/y)" / "…link."). Trimmed off the
# match, never off the name.
_TRAILING_PUNCT = ".,;:)]}\"'"

# Path segments that are ids, not words: pure digits, or long hex-ish tokens ("942006020367",
# "b1946ac9…"). A segment with a hyphen/space survives — that's the human slug.
_ID_SEGMENT_RE = re.compile(r"^(?:\d+|[0-9a-f]{8,})$", re.IGNORECASE)


def split_link(text: str) -> tuple[str, str | None]:
    """Split pasted text into (typed text, first URL or None).

    The typed text is whitespace-collapsed with every URL removed — extra URLs are dropped
    entirely (they are never useful as name text). No URL ⇒ the text comes back intact.
    """
    urls = _URL_RE.findall(text)
    if not urls:
        return " ".join(text.split()), None
    url = urls[0].rstrip(_TRAILING_PUNCT)
    # Removing a URL can orphan wrapping punctuation ("look (https://…)" → "look ("); drop
    # tokens with no letters or digits left in them.
    tokens = [t for t in _URL_RE.sub(" ", text).split() if any(c.isalnum() for c in t)]
    return " ".join(tokens), url


def name_from_url(url: str) -> str:
    """Best-effort human name from the URL itself, for URL-only adds when the title fetch
    comes up empty. Last meaningful path segment (%-decoded, hyphens/underscores → spaces,
    file extension dropped, id-like segments skipped), else the hostname minus ``www.``.
    Always non-empty and comfortably under the 255-char name cap.
    """
    parts = urlsplit(url)
    segments = [s for s in parts.path.split("/") if s]
    for segment in reversed(segments):
        candidate = unquote(segment)
        candidate = re.sub(r"\.(html?|php|aspx?)$", "", candidate, flags=re.IGNORECASE)
        if _ID_SEGMENT_RE.match(candidate):
            continue
        words = " ".join(re.split(r"[-_+]+", candidate))
        words = " ".join(words.split())
        if words:
            return words[:255]
    host = (parts.hostname or "").removeprefix("www.")
    return (host or url)[:255]
