"""Shopping-list merge math (CLAUDE.md §4) — pure functions, exhaustively table-tested.

v0.2.1 redesign, after the first real store run showed the flaw: a shopping list is a list of
**things you buy**, not a dump of cooking measures. The rules clients rely on:

- **Identity is the normalized name.** "2 tbsp oil" and "2 tsp oil" are one line item — you buy
  one bottle. (v0.2 keyed on name+unit and produced duplicate lines.)
- **Measures aggregate instead of pretending to sum.** Same canonical unit ⇒ quantities add;
  different units ⇒ both measures are kept and shown side by side ("2 tbsp + 2 tsp"). No unit
  conversion guessing.
- **Units are canonical** ("cups"/"cup" ⇒ cup, "teaspoons"/"tsp" ⇒ tsp) so spelling can't defeat
  a merge.
- **Non-purchasables never reach the list** — water is the canonical example.

Normalization stays "singularize-lite": lowercase, whitespace-collapsed, common English plurals
folded without a language library. Clients never merge independently; they render what the
server returns.
"""

from dataclasses import dataclass, field

# Uncountables the suffix rules would mangle ("molasses" ends in plain "es", so the "ss" guard
# misses it). Extend as real groceries surface; both sides of a comparison normalize the same
# way, so a miss here only risks an over-eager merge, never data loss.
_PROTECTED_WORDS = frozenset({"molasses"})

# Recipe "ingredients" nobody buys. Exact normalized keys plus the "<anything> water" family
# (warm water, ice water…). Word-suffix match, so watermelon is safe.
_NON_PURCHASABLE = frozenset({"water", "ice", "ice cube", "tap water"})

# Canonical unit spellings — one short form per unit so merges can't be defeated by plurals or
# synonyms. Anything unrecognized passes through lowercased (still merge-consistent with itself).
_CANONICAL_UNITS: dict[str, str] = {
    "cup": "cup",
    "cups": "cup",
    "c": "cup",
    "tablespoon": "tbsp",
    "tablespoons": "tbsp",
    "tbsp": "tbsp",
    "tbs": "tbsp",
    "tbl": "tbsp",
    "teaspoon": "tsp",
    "teaspoons": "tsp",
    "tsp": "tsp",
    "ounce": "oz",
    "ounces": "oz",
    "oz": "oz",
    "pound": "lb",
    "pounds": "lb",
    "lb": "lb",
    "lbs": "lb",
    "gram": "g",
    "grams": "g",
    "g": "g",
    "kilogram": "kg",
    "kilograms": "kg",
    "kg": "kg",
    "milliliter": "ml",
    "milliliters": "ml",
    "ml": "ml",
    "liter": "l",
    "liters": "l",
    "l": "l",
    "can": "can",
    "cans": "can",
    "clove": "clove",
    "cloves": "clove",
    "slice": "slice",
    "slices": "slice",
    "stick": "stick",
    "sticks": "stick",
    "package": "package",
    "packages": "package",
    "pkg": "package",
    "bunch": "bunch",
    "bunches": "bunch",
    "head": "head",
    "heads": "head",
    "piece": "piece",
    "pieces": "piece",
    "pinch": "pinch",
    "pinches": "pinch",
    "dash": "dash",
    "dashes": "dash",
    "serving": "serving",
    "servings": "serving",
    "tub": "tub",
    "tubs": "tub",
    "bottle": "bottle",
    "bottles": "bottle",
    "jar": "jar",
    "jars": "jar",
    "box": "box",
    "boxes": "box",
    "bag": "bag",
    "bags": "bag",
}


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


def canonical_unit(unit: str | None) -> str | None:
    """One spelling per unit ("cups"/"cup" ⇒ cup); unknown units lowercase-passthrough."""
    if unit is None:
        return None
    key = " ".join(unit.casefold().split()).rstrip(".")
    if not key:
        return None
    return _CANONICAL_UNITS.get(key, key)


def merge_key(name: str) -> str:
    """The identity a line item merges on — the normalized name, nothing else. You buy one
    "oil" whether the recipes measured it in tablespoons or teaspoons."""
    return normalize_name(name)


def is_purchasable(name: str) -> bool:
    key = normalize_name(name)
    if key in _NON_PURCHASABLE:
        return False
    return not key.endswith(" water")


@dataclass
class Measure:
    """One aggregated amount on a line item. ``unit`` None means a bare count ("3 eggs")."""

    quantity: float
    unit: str | None = None


def add_measure(measures: list[Measure], quantity: float | None, unit: str | None) -> list[Measure]:
    """Fold one (quantity, unit) into an aggregate: same canonical unit sums; a new unit gets
    its own entry; an unquantified amount adds nothing (the item's presence already says
    "buy some")."""
    if quantity is None or quantity <= 0:
        return measures
    canon = canonical_unit(unit)
    for m in measures:
        if m.unit == canon:
            m.quantity = round(m.quantity + quantity, 4)
            return measures
    measures.append(Measure(quantity=round(quantity, 4), unit=canon))
    return measures


def scale_quantity(quantity: float | None, scale: float) -> float | None:
    """Servings multiplier for "add recipe to list". None (unspecified) stays None."""
    if quantity is None:
        return None
    return round(quantity * scale, 4)


@dataclass
class IncomingItem:
    """One row arriving at the list (a recipe ingredient or a manual add)."""

    name: str
    quantity: float | None = None
    unit: str | None = None
    category: str | None = None
    note: str | None = None
    measures: list[Measure] = field(default_factory=list)

    def __post_init__(self):
        if not self.measures:
            self.measures = add_measure([], self.quantity, self.unit)


def merge_incoming(incoming: list[IncomingItem]) -> list[IncomingItem]:
    """Collapse duplicates *within* one incoming batch (a recipe listing oil twice, in any
    units) and drop non-purchasables."""
    merged: dict[str, IncomingItem] = {}
    order: list[str] = []
    for item in incoming:
        if not is_purchasable(item.name):
            continue
        key = merge_key(item.name)
        if key in merged:
            existing = merged[key]
            for m in item.measures:
                add_measure(existing.measures, m.quantity, m.unit)
            # First non-null metadata wins; a merge shouldn't drop information.
            existing.category = existing.category or item.category
            existing.note = existing.note or item.note
        else:
            merged[key] = item
            order.append(key)
    return [merged[k] for k in order]
