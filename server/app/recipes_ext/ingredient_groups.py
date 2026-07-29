"""Recover a recipe's own ingredient groupings ("Steak Marinade", "Fajitas") on import (v0.9).

schema.org has no vocabulary for ingredient groups: ``recipeIngredient`` is a flat list of
strings by spec. But recipes are *written* in groups, and the instructions lean on them —
"Combine the ingredients for the marinade" is unreadable without knowing which ingredients
those are. Two recovery routes, cheapest first:

1. :func:`split_inline_sections` — some sites emit the heading as a ``recipeIngredient`` entry
   of its own ("For the marinade:"). Free, no page markup needed.
2. :func:`extract_ingredient_groups` + :func:`align_sections` — scrape the headings out of the
   rendered HTML and map them back onto the JSON-LD lines by text.

**Degradation is the contract.** Every function here returns "no sections" rather than a guess
it isn't sure of, and the caller can't distinguish a rejected scrape from a page that never had
groups: the import is then byte-for-byte what it was before this module existed. A wrong
heading is worse than no heading, and a heading mistaken for an ingredient silently *deletes*
an ingredient — so the thresholds below are deliberately conservative.

Stdlib-only (regex, like the sibling JSON-LD extractor — there is no HTML parser in the venv),
and free of ``httpx`` so the parsing can be exercised without the network stack.
"""

import html as html_lib
import re

from app.limits import MAX_SECTION_LENGTH
from app.lists.categorize import guess_category

_TAG_RE = re.compile(r"<[^>]+>")

# Unicode fractions as recipe sites write amounts; a heading never contains one.
_FRACTION_CHARS = "½⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞"

# "For the marinade", "For a quick slaw" — the other way English writes an ingredient heading.
_FOR_THE_RE = re.compile(r"^for\s+(?:the\s+|a\s+|an\s+)?\S", re.IGNORECASE)
_LEADING_FOR_RE = re.compile(r"^for\s+(?:the\s+|a\s+|an\s+)?", re.IGNORECASE)

# A lone "Ingredients" heading is the page's own furniture, not a section of the recipe.
_GENERIC_LABELS = {"ingredient", "ingredients", "you will need", "what you need"}

# Past this many, the lines being classified aren't an ingredient list.
MAX_INLINE_HEADINGS = 10


def clean_section_label(text: str) -> str | None:
    """Normalize a scraped heading into a stored section name, or None if it says nothing."""
    label = html_lib.unescape(_TAG_RE.sub(" ", text))
    label = " ".join(label.split()).strip().rstrip(":").strip()
    if not label:
        return None
    # ALL-CAPS headings are a styling choice on the page, not how anyone writes a name.
    if label == label.upper() and any(c.isalpha() for c in label):
        label = label.title()
    stripped = _LEADING_FOR_RE.sub("", label).strip()
    if stripped != label and stripped[:1].islower():
        # "For the sauce" → "Sauce": it's a title once the preposition is gone.
        stripped = stripped[0].upper() + stripped[1:]
    label = stripped
    if not label or label.casefold() in _GENERIC_LABELS:
        return None
    return label[:MAX_SECTION_LENGTH]


def is_section_heading(line: str) -> bool:
    """Whether a ``recipeIngredient`` entry is really a heading rather than an ingredient.

    A false positive deletes a real ingredient from the recipe, so every branch below is
    narrow: no amount anywhere in the line (digits or unicode fractions), short, and written
    the way headings are written — trailing colon, "For the ...", or shouted in caps.
    """
    text = " ".join(line.split())
    if not text or len(text) > 60:
        return False
    if any(c.isdigit() for c in text) or any(c in _FRACTION_CHARS for c in text):
        return False

    if text.endswith(":"):
        return True
    if _FOR_THE_RE.match(text):
        return True
    # ALL-CAPS: real one- and two-word ingredients get shouted too ("SALT", "OLIVE OIL"), so a
    # short caps line that names a recognizable food is treated as an ingredient. This guard is
    # deliberately NOT applied to the branches above — "Steak Marinade:" names a food word and
    # is still a heading.
    if text == text.upper() and any(c.isalpha() for c in text) and len(text.split()) <= 5:
        return not (len(text.split()) <= 2 and guess_category(text) is not None)
    return False


def split_inline_sections(lines: list[str]) -> list[tuple[str | None, str]]:
    """Pair each ingredient line with the heading that precedes it, dropping the heading lines.

    Returns ``(section, line)`` in order. All-None when the page has no inline headings, or
    when so many lines look like headings that the classification is clearly wrong.
    """
    flags = [is_section_heading(line) for line in lines]
    headings = sum(flags)
    # Every heading needs at least one ingredient under it to be worth anything, so more
    # headings than ingredients means the heuristic is misfiring on this page's phrasing
    # rather than that the recipe really has that many groups.
    if headings and (headings * 2 > len(lines) or headings > MAX_INLINE_HEADINGS):
        return [(None, line) for line in lines]

    out: list[tuple[str | None, str]] = []
    current: str | None = None
    for line, is_heading in zip(lines, flags):
        if is_heading:
            current = clean_section_label(line)
            continue
        out.append((current, line))
    # A heading with nothing under it contributed nothing; that falls out naturally above.
    return out


# ── HTML scrape ──────────────────────────────────────────────────────────────

# Where a page's ingredient block starts. Class-name families across the common recipe plugins
# (WP Recipe Maker, Tasty Recipes, Mediavine Create, WP Ultimate Recipe, Dotdash) plus generic
# hand-rolled markup.
_START_RE = re.compile(
    r'class="[^"]*(?:wprm-recipe-ingredient|tasty-recipes-ingredients|mv-create-ingredients'
    r"|wpurp-recipe-ingredient|structured-ingredients|recipe-ingredients|ingredients-section)",
    re.IGNORECASE,
)
# ...and where it stops. Regex can't balance tags; this bound is what keeps the comment section
# and the instruction list out of the scrape.
_STOP_RE = re.compile(
    r'class="[^"]*(?:instruction|direction|method|recipe-notes|wprm-recipe-notes|comment)',
    re.IGNORECASE,
)
_REGION_LIMIT = 80_000

# One shape-based scan rather than a parser per plugin: they all render "heading, then <li>s"
# and differ only in class names, so matching the shape covers more sites with less code.
_SCAN_RE = re.compile(
    r"<li\b[^>]*>(?P<li>.*?)</li>"
    r"|<(?P<h>h[2-6])\b[^>]*>(?P<ht>.*?)</(?P=h)>"
    r"|<p\b[^>]*>\s*<strong\b[^>]*>(?P<st>.*?)</strong>\s*</p>"
    # A heading rendered as a styled div/span rather than an <hN>. The captured text may not
    # span another element that starts a list or a block: WPRM's *container* also carries a
    # "…-group" class, and a greedy match there swallows the whole group it was meant to label.
    r"|<(?P<d>div|span|p)\b[^>]*class=\"[^\"]*(?:group-name|list-heading|partHeading"
    r"|ingredients-header)[^\"]*\"[^>]*>(?P<dt>(?:(?!<li|<ul|<ol|<div).)*?)</(?P=d)>",
    re.IGNORECASE | re.DOTALL,
)

MAX_SCRAPED_ITEMS = 200


def _text_of(raw: str) -> str:
    return " ".join(html_lib.unescape(_TAG_RE.sub(" ", raw)).split())


def extract_ingredient_groups(html: str) -> list[tuple[str | None, str]]:
    """``(section, ingredient text)`` in document order, scraped from the page's ingredient block.

    Empty when the page has no recognizable ingredient markup — which is most of the web, and
    is fine: the caller falls back to a flat import.
    """
    start = _START_RE.search(html)
    if start is None:
        return []
    stop = _STOP_RE.search(html, start.end())
    end = stop.start() if stop else start.start() + _REGION_LIMIT
    region = html[start.start() : end]

    out: list[tuple[str | None, str]] = []
    current: str | None = None
    for m in _SCAN_RE.finditer(region):
        if m.group("li") is not None:
            text = _text_of(m.group("li"))
            if text:
                out.append((current, text))
            if len(out) > MAX_SCRAPED_ITEMS:
                return []  # not an ingredient list — we've wandered into the page
        else:
            heading = m.group("ht") or m.group("st") or m.group("dt") or ""
            current = clean_section_label(heading)
    return out


def _match_key(text: str) -> str:
    """Whitespace/entity/punctuation-insensitive key — the JSON-LD string and the rendered
    ``<li>`` come from the same generator but rarely agree character for character."""
    return re.sub(r"[^a-z0-9]", "", text.casefold())


_LOOKAHEAD = 5
_MIN_MATCH_RATIO = 0.7
_MIN_CONTAINMENT_KEY = 6


def align_sections(lines: list[str], scraped: list[tuple[str | None, str]]) -> list[str | None]:
    """A section per JSON-LD ingredient line, matched by text against the scraped list.

    Returns all-None unless the alignment is convincing (see the gate at the end) — a drifted
    alignment would file ingredients under the wrong heading, which is worse than no headings.
    """
    if not lines or not scraped:
        return [None] * len(lines)

    keys = [_match_key(text) for _, text in scraped]
    assigned: list[str | None] = []
    matched = [False] * len(lines)
    cursor = 0
    for i, line in enumerate(lines):
        key = _match_key(line)
        hit: int | None = None
        for k in range(cursor, min(cursor + _LOOKAHEAD, len(scraped))):
            other = keys[k]
            if not key or not other:
                continue
            if key == other or (
                len(key) >= _MIN_CONTAINMENT_KEY
                and len(other) >= _MIN_CONTAINMENT_KEY
                # WPRM appends its own notes span to the rendered line, so containment either
                # direction is a legitimate match.
                and (key in other or other in key)
            ):
                hit = k
                break
        if hit is None:
            assigned.append(None)
        else:
            assigned.append(scraped[hit][0])
            matched[i] = True
            cursor = hit + 1

    # A line the scrape didn't cover, sandwiched between two lines of the same section, belongs
    # to that section — the page rendered something the JSON-LD omitted, not a new group.
    for i in range(1, len(assigned) - 1):
        if not matched[i] and assigned[i - 1] is not None and assigned[i - 1] == assigned[i + 1]:
            assigned[i] = assigned[i - 1]

    if sum(matched) < _MIN_MATCH_RATIO * len(lines):
        return [None] * len(lines)
    if not any(s is not None for s in assigned):
        return [None] * len(lines)
    if not _is_contiguous(assigned):
        return [None] * len(lines)
    return assigned


def _is_contiguous(sections: list[str | None]) -> bool:
    """Sections must form runs. A repeat after an interruption ("A, B, A") means the alignment
    drifted rather than that the recipe really returns to an earlier group."""
    seen: set[str] = set()
    previous: str | None = None
    for section in sections:
        if section != previous and section is not None:
            if section in seen:
                return False
            seen.add(section)
        previous = section
    return True
