"""One-time re-sort: apply the v0.9 category-guesser fixes to rows already in the DB.

The guesser had two landmines and a blind spot, all of which are baked into existing rows:

- a bare ``"ground" -> meat`` keyword outranked the spice it modified, so **ground cumin** sat
  in Meat & Seafood;
- prep clauses were matched as identity, so **"large poblano (ribs and seeds removed then
  sliced)"** matched *rib* and also landed in Meat & Seafood;
- red pepper flakes read as fresh *pepper* -> Produce, and the juice family was decided by
  which keyword happened to be the longer string.

Fixing the guesser only helps future adds, and the recipe that prompted this round is already
imported — hence this sweep. It covers ``recipe_ingredients`` (never backfilled by any
migration; it is what feeds the shopping list), plus ``shopping_list_items`` and
``item_history``, which carry the same wrong values from before the fix.

Like 0019, it rewrites a row **only when the stored value equals what the old guesser would
have produced** for that name, so an aisle a human chose by hand survives untouched.

Unlike 0019 — which had to freeze the whole keyword map because the *matching algorithm*
changed — this round changes the map by a small, enumerable diff plus a name-cleaning step, so
the old guesser is reconstructed from that diff instead of a 390-line copy. The coupling that
buys: a *future* keyword edit would shift this migration's notion of "old" for a database that
has not run it yet. Bounded (the worst case is one mis-scoped aisle label on an old row) and
worth the 350 lines not written — but it is why `_REMOVED`/`_ADDED` below are frozen literals
and must not be "kept in sync" with the live map.

Data-only. Downgrade is a no-op (prior guesses aren't recoverable). No-ops on a fresh DB.

Revision ID: 0022
Revises: 0021
Create Date: 2026-07-29
"""

import re

import sqlalchemy as sa

from alembic import op
from app.lists.categorize import _KEYWORDS, guess_category
from app.lists.merge import normalize_name

revision = "0022"
down_revision = "0021"
branch_labels = None
depends_on = None


# The exact keyword diff v0.9 applied, frozen. Do NOT "improve" these to match the live map —
# their whole job is to describe the map as it was *before* the fix.
_REMOVED = {"ground": "meat"}
_ADDED = frozenset(
    {
        "ground pork",
        "ground chicken",
        "ground lamb",
        "ground sausage",
        "ground chuck",
        "ground round",
        "ground sirloin",
        "ground bison",
        "ground venison",
        "ground meat",
        "ground ginger",
        "poblano",
        "serrano",
        "habanero",
        "anaheim",
        "tomatillo",
        "lime juice",
        "lemon juice",
        "red pepper flake",
        "pepper flake",
        "crushed red pepper",
        "chili flake",
        "chile flake",
        "salt and pepper",
        "pineapple juice",
        "cranberry juice",
        "grape juice",
        "tomato juice",
    }
)

_OLD_KEYWORDS = {k: v for k, v in _KEYWORDS.items() if k not in _ADDED} | _REMOVED
_OLD_ORDERED = sorted(_OLD_KEYWORDS.items(), key=lambda kv: len(kv[0]), reverse=True)
_OLD_PATTERNS = [
    (re.compile(rf"\b{re.escape(keyword)}(?:e?s)?\b"), category)
    for keyword, category in _OLD_ORDERED
]


def _old_guess(name: str) -> str | None:
    """The pre-v0.9 guess: same longest-wins matching, but on the raw name (no prep-clause
    cleaning) and against the pre-fix keyword map."""
    raw = " ".join(name.casefold().split())
    norm = normalize_name(name)
    for pattern, category in _OLD_PATTERNS:
        if pattern.search(raw) or pattern.search(norm):
            return category
    return None


def resort_category(name: str, category: str | None) -> str | None:
    """The category to set for a re-sort, or None to leave the row unchanged.

    Only rewrites a value that matches the old auto-guess (so manual picks survive), and only
    when the new guesser produces a different, non-null aisle.
    """
    if category != _old_guess(name or ""):
        return None  # looks manual — leave it
    new = guess_category(name or "")
    if new is not None and new != category:
        return new
    return None


def upgrade() -> None:
    bind = op.get_bind()
    for table in ("recipe_ingredients", "shopping_list_items", "item_history"):
        rows = bind.execute(sa.text(f"SELECT id, name, category FROM {table}")).fetchall()
        for row_id, name, category in rows:
            new = resort_category(name, category)
            if new is not None:
                bind.execute(
                    sa.text(f"UPDATE {table} SET category = :c WHERE id = :i"),
                    {"c": new, "i": row_id},
                )


def downgrade() -> None:
    pass  # prior guesses aren't recoverable; the re-sort is intentionally one-way
