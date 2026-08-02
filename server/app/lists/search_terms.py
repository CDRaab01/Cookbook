"""Turning a shopping-list item name into something a retailer's search box can find.

**Why this is not `categorize.clean_for_category`.** They look like the same job and are not.
Categorising wants *every* identity word, because the keyword map matches on them and a dropped
word can lose the aisle. Searching a store wants the **shortest confident product phrase**,
because a search engine given "crema or 3 tbsp sour cream + 1 tbsp milk" returns nothing at all,
and dropping a descriptor usually costs nothing ("sliced almonds" → "almonds" still finds
almonds). Opposite trade-offs, so: separate function, shared starting point.

The starting point *is* `clean_for_category`, reused rather than reimplemented, so the tested
parenthetical- and prep-clause stripping only exists once.

**This is best-effort by design, and never the last word.** The harvest that consumes it is a
human-initiated batch that shows every query in an editable field before it runs — so a bad clean
costs one edit, not a wrong aisle. That is why the rules below are allowed to be blunt: the
expensive failure (silently searching for the wrong thing) is designed out at the UI, not here.

Two hard guarantees: the result is never empty (an over-eager strip falls back to the original
name), and this module never touches merge identity or `item_history` — `normalize_name` and
`clean_for_category` are both left exactly as they are.
"""

from __future__ import annotations

import re

from app.lists.categorize import clean_for_category

#: Cap what we hand a search box. Long queries score worse, and no retailer needs more than this.
MAX_QUERY_LENGTH = 60

# " such as " / " plus " introduce an elaboration on what came before, so the head is always to
# the left: "neutral oil such as canola oil" → "neutral oil".
_ELABORATION_RE = re.compile(r"\s+(?:such as|plus)\s+.*$", re.IGNORECASE)

#: Splits "X or Y" into the alternatives a search has to choose between.
_OR_SPLIT_RE = re.compile(r"\s+or\s+", re.IGNORECASE)

_HAS_DIGIT_RE = re.compile(r"\d")

# Trailing state/purpose clauses that survive comma-stripping because they have no comma.
# "cream cheese at room temp" → "cream cheese"; "neutral oil for frying" → "neutral oil".
_TRAILING_CLAUSE_RE = re.compile(
    r"\s+(?:"
    r"at room temp(?:erature)?|room temp(?:erature)?|"
    r"for (?:frying|garnish|serving|dusting|drizzling|topping|brushing|greasing)|"
    r"to taste|as needed|if needed|optional|divided|"
    r"cut into .*|"
    r"plus more.*"
    r")\s*$",
    re.IGNORECASE,
)

# Preparation words. Stripped from either end but never from the middle, because the middle is
# where they're load-bearing ("chocolate chip cookies" must keep "chip"). Losing one of these on a
# search is cheap: "shredded nori" → "nori" and "melted unsalted butter" → "unsalted butter" both
# still find the product.
_PREP_WORDS = frozenset(
    {
        "melted",
        "cooled",
        "softened",
        "chilled",
        "warmed",
        "room",
        "chopped",
        "minced",
        "sliced",
        "diced",
        "shredded",
        "grated",
        "crushed",
        "beaten",
        "whisked",
        "peeled",
        "seeded",
        "stemmed",
        "trimmed",
        "rinsed",
        "drained",
        "thawed",
        "frozen",
        "fresh",
        "freshly",
        "finely",
        "thinly",
        "coarsely",
        "roughly",
        "thin",
        "large",
        "small",
        "medium",
        "and",
        # Form nouns, which read as prep when they trail: "green chili slices" → "green chili".
        # Dropping one only ever *broadens* the search, which is the safe direction to fail in.
        "slices",
        "pieces",
        "chunks",
        "strips",
        "halves",
        "wedges",
        "cubes",
    }
)


def _strip_prep_edges(words: list[str]) -> list[str]:
    """Drop prep words from the front and back, never the middle.

    Refuses to strip everything: a name made only of prep words ("chopped fresh") keeps its last
    word rather than becoming "", because an empty query is useless and the original at least
    stands a chance.
    """
    start, end = 0, len(words)
    while start < end - 1 and words[start].lower().strip(",") in _PREP_WORDS:
        start += 1
    while end - 1 > start and words[end - 1].lower().strip(",") in _PREP_WORDS:
        end -= 1
    return words[start:end]


def _pick_alternative(text: str) -> str:
    """Choose one side of an "X or Y", because a search box can only look for one thing.

    Taking the left side blindly is wrong as often as it's right — the head noun sits on whichever
    side the writer put it on:

        "crema or 3 tbsp sour cream + 1 tbsp milk"  → the head is left  ("crema")
        "thin red or green chili slices"            → the head is right ("green chili")

    So score instead of guessing by position. A side carrying digits is a *quantity* restatement,
    never the product name, and is dropped outright; among what's left, the side with the most
    non-prep words is the most specific, and ties go left (the order the writer chose).
    """
    parts = [p.strip() for p in _OR_SPLIT_RE.split(text) if p.strip()]
    if len(parts) < 2:
        return text
    candidates = [p for p in parts if not _HAS_DIGIT_RE.search(p)] or parts

    def specificity(part: str) -> int:
        return sum(1 for w in part.split() if w.lower().strip(",") not in _PREP_WORDS)

    return max(candidates, key=specificity)


def search_query(name: str) -> str:
    """A retailer-search-box query for a shopping-list item name.

    Best-effort and deliberately blunt — see the module docstring for why that's safe. Returns the
    original (trimmed) name unchanged when the rules would leave nothing behind.
    """
    original = " ".join(name.split())
    if not original:
        return ""

    cleaned = clean_for_category(original)
    cleaned = _ELABORATION_RE.sub("", cleaned)
    cleaned = _pick_alternative(cleaned)
    # Applied repeatedly: "neutral oil for frying to taste" has two stacked trailing clauses, and
    # one pass would leave the outer one behind.
    while True:
        stripped = _TRAILING_CLAUSE_RE.sub("", cleaned)
        if stripped == cleaned:
            break
        cleaned = stripped

    words = _strip_prep_edges(cleaned.split())
    result = " ".join(words).strip(" ,-").strip()
    return (result or original)[:MAX_QUERY_LENGTH]
