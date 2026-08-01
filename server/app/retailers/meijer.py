"""Reading Meijer's in-store location strings.

**Why the server does not fetch these pages itself.** meijer.com is a client-rendered SPA behind
Akamai Bot Manager: the product HTML is an ~11 KB shell with no aisle in it, and an automated
browser is refused outright — a headless Chromium on this host got ``403 Access Denied`` on
``/shopping/product/…``, on ``/shopping/search.html`` *and* on ``/robots.txt`` (measured
2026-08-01, from the same IP and user agent as a session that had just read those pages fine).
Getting past that means defeating a bot-detection control, which is out of scope by choice, not by
difficulty. So there is **no scraper in this repo**: observations are collected in a real browser
session and POSTed to ``/stores/{id}/placements/import``, and this module is the part that turns
"Aisle B | 16" into something the shopping list can route by.

The consequence worth internalising: an import is an **occasional, human-initiated batch**, never a
background refresh. That is fine, because an aisle is close to static — the cost is paid once per
item and cached in ``store_placements`` forever.

Shapes measured against the Maysville Rd store (Meijer store id 138) on 2026-08-01:

    bananas        Aisle A | 11   Section 10
    paper towels   Aisle B | 14   Section 31
    peanut butter  Aisle B | 16   Section 39
    milk           Aisle B | 17   Section 35

Note what that last pair implies: B|16 (peanut butter) and B|17 (milk) are adjacent codes for
departments nowhere near each other on a real Meijer floor. These are **planogram codes, not a
survey of the store**, so :func:`walk_sort_key` yields a *plausible* starting order, not a verified
route. The user drags it into shape once; that is exactly why the aisle PUT preserves ids (a
reorder must never discard learned placements).
"""

from __future__ import annotations

import re
from dataclasses import dataclass

#: The retailer key stored on ``stores.retailer``. One value today; the column exists so a second
#: chain doesn't require a migration to tell the two apart.
RETAILER_MEIJER = "meijer"

# "Aisle B | 16" — a zone letter and a run within it. Spacing around the pipe is a rendering
# detail, not a guarantee, so the separator is matched loosely.
_AISLE_RE = re.compile(r"Aisle\s+([A-Z])\s*\|\s*(\d+)", re.IGNORECASE)
_SECTION_RE = re.compile(r"Section\s+(\d+)", re.IGNORECASE)

# The site renders a "Finding Aisle Sections" placeholder while its lazy chunk resolves. Treating
# that as an answer would cache "this item has no aisle" permanently, so it is distinguished from a
# genuine no-aisle page.
_PENDING_RE = re.compile(r"Finding\s+Aisle", re.IGNORECASE)

# Product cards concatenate name + price + rating into one anchor ("Meijer Whole Milk,
# GallonOriginal price $2.26(322)4.3 out of 5"). Everything from the first price-ish or rating-ish
# run is noise.
_CARD_NOISE_RE = re.compile(
    r"(Original price|Current price|\$\d|\(\d+\)|\d+(\.\d+)? out of).*$", re.IGNORECASE | re.DOTALL
)


@dataclass(frozen=True)
class Location:
    """Where a product sits in one specific store.

    ``aisle`` is the label as the sign reads it ("B | 16") — it becomes a ``StoreAisle.name``, so
    it stays human-readable rather than normalized. ``section`` is the shelf run within the aisle;
    it is recorded for display but **never routed on**, because the aisle is the unit you walk to.
    """

    aisle: str | None
    section: str | None

    @property
    def is_empty(self) -> bool:
        return self.aisle is None and self.section is None


def parse_location(page_text: str) -> Location | None:
    """Pull aisle/section out of a product page's rendered text.

    Returns ``None`` — *"ask again later"* — while the location widget is still resolving. Returns
    an empty :class:`Location` when the page rendered fully and genuinely carries no aisle, which
    is a real and permanent answer for a service counter. The distinction matters: the first should
    be retried, the second must not be.
    """
    if _PENDING_RE.search(page_text) and not _SECTION_RE.search(page_text):
        return None
    aisle_match = _AISLE_RE.search(page_text)
    section_match = _SECTION_RE.search(page_text)
    if aisle_match is None and section_match is None:
        return Location(aisle=None, section=None)
    aisle = f"{aisle_match.group(1).upper()} | {aisle_match.group(2)}" if aisle_match else None
    return Location(aisle=aisle, section=section_match.group(1) if section_match else None)


def normalize_aisle_label(raw: str) -> str | None:
    """Canonicalize whatever the harvester sends into the stored aisle label, or ``None``.

    The harvester may post "Aisle B | 16", "B|16" or "b | 16" depending on how it scraped the DOM;
    all three must land on the *same* ``StoreAisle`` row, or one store grows three aisles for one
    physical location and the walk order becomes nonsense.
    """
    match = _AISLE_RE.search(raw) or re.fullmatch(r"\s*([A-Za-z])\s*\|\s*(\d+)\s*", raw)
    if match is None:
        return None
    return f"{match.group(1).upper()} | {int(match.group(2))}"


def aisle_display_name(aisle: str) -> str:
    """The ``StoreAisle.name`` for a parsed aisle label — what the shopper reads in the list."""
    return f"Aisle {aisle}"


def walk_sort_key(aisle_name: str) -> tuple[int, str, int]:
    """Ordering for a store's aisles: parsed aisles by zone then number, everything else last.

    The tuple is ``(rank, zone, number)``. Rank is what keeps the 13 seeded category aisles
    ("Produce", "Dairy & Eggs") in a block *after* the real aisles — they are the fallback for items
    nobody has looked up yet, and a shrinking tail is exactly what they should look like as
    coverage grows.

    Every unparseable name returns the **same** key, deliberately. Callers must break the tie on
    the aisle's existing ``order`` — see :func:`~app.services.store_service._reorder_walk`. An
    earlier version tiebroke on the name here, which quietly *alphabetized* the seeded block
    (Baby, Bakery, Beverages, …) and destroyed the canonical produce→meat→dairy walk order the
    store was seeded with. Sorting a walk order by name is never right.
    """
    match = _AISLE_RE.search(aisle_name)
    if match is None:
        return (1, "", 0)
    return (0, match.group(1).upper(), int(match.group(2)))


def clean_card_text(text: str) -> str:
    """Strip the price/rating tail a product-card anchor concatenates onto the product name."""
    return _CARD_NOISE_RE.sub("", text).strip(" -–—|")[:200]
