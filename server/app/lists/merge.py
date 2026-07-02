"""Shopping-list merge math (CLAUDE.md §4) — pure functions, exhaustively table-tested.

The one rule clients rely on: **same normalized name + same unit ⇒ one line item, quantities
summed**. Unit mismatch (or a missing unit on one side) is a different key and stays a separate
line — "2 lb chicken" and "1 breast chicken" don't pretend to be addable. Clients never merge
independently; they render what the server returns.

Normalization is deliberately "singularize-lite": lowercase, whitespace-collapsed, and common
English plurals folded (tomatoes→tomato, berries→berry, eggs→egg) without dragging in a language
library. Words that only *look* plural (hummus, swiss, molasses) are protected by suffix rules.
"""

from dataclasses import dataclass

# Uncountables the suffix rules would mangle ("molasses" ends in plain "es", so the "ss" guard
# misses it). Extend as real groceries surface; both sides of a comparison normalize the same
# way, so a miss here only risks an over-eager merge, never data loss.
_PROTECTED_WORDS = frozenset({"molasses"})


def normalize_name(name: str) -> str:
    """Casefold, trim, collapse whitespace, and fold simple English plurals."""
    key = " ".join(name.casefold().split())
    if key in _PROTECTED_WORDS:
        return key
    if len(key) > 4 and key.endswith("ies"):
        return key[:-3] + "y"  # berries -> berry
    if len(key) > 4 and key.endswith("oes"):
        return key[:-2]  # tomatoes -> tomato
    if (
        len(key) > 3
        and key.endswith("s")
        and not key.endswith(("ss", "us", "is"))  # hummus, swiss, couscous... stay put
    ):
        return key[:-1]  # eggs -> egg
    return key


def merge_key(name: str, unit: str | None) -> tuple[str, str | None]:
    """The identity a line item merges on. Units are stored normalized lowercase already."""
    return (normalize_name(name), unit.strip().casefold() if unit else None)


@dataclass
class IncomingItem:
    """One row arriving at the list (a recipe ingredient or a manual add)."""

    name: str
    quantity: float | None = None
    unit: str | None = None
    category: str | None = None
    note: str | None = None


def scale_quantity(quantity: float | None, scale: float) -> float | None:
    """Servings multiplier for "add recipe to list". None (unspecified) stays None."""
    if quantity is None:
        return None
    return round(quantity * scale, 4)


def combine_quantities(a: float | None, b: float | None) -> float | None:
    """Sum two same-key quantities.

    Either side being None means "unspecified amount"; the sum of a number and "some" is still
    "some", so the result collapses to None rather than pretending precision.
    """
    if a is None or b is None:
        return None
    return round(a + b, 4)


def merge_incoming(incoming: list[IncomingItem]) -> list[IncomingItem]:
    """Collapse duplicates *within* one incoming batch (a recipe listing garlic twice)."""
    merged: dict[tuple[str, str | None], IncomingItem] = {}
    order: list[tuple[str, str | None]] = []
    for item in incoming:
        key = merge_key(item.name, item.unit)
        if key in merged:
            existing = merged[key]
            merged[key] = IncomingItem(
                name=existing.name,
                quantity=combine_quantities(existing.quantity, item.quantity),
                unit=existing.unit,
                # First non-null category/note wins; a merge shouldn't drop information.
                category=existing.category or item.category,
                note=existing.note or item.note,
            )
        else:
            merged[key] = item
            order.append(key)
    return [merged[k] for k in order]
