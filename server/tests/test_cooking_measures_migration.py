"""The one-time cooking-measure cleanup (migration 0020) — verify the pure backfill helper.

Loads the migration module directly and exercises ``cleaned_item``: it must strip cooking-only
amounts (tsp/tbsp/cup/pinch/dash) from a shopping row while keeping store units and bare counts,
recompute the legacy quantity/unit columns coherently, and return None (no write) when a row has
nothing cooking-only to drop.
"""

import importlib.util
from pathlib import Path

_MIGRATION = (
    Path(__file__).resolve().parent.parent
    / "alembic"
    / "versions"
    / "0020_drop_cooking_measures.py"
)
_spec = importlib.util.spec_from_file_location("_m0020", _MIGRATION)
m0020 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(m0020)


def test_all_cooking_measures_cleared_to_bare_item():
    # "2 tbsp + 2 tsp" oil ⇒ no measure survives; legacy columns null.
    assert m0020.cleaned_item(
        [{"quantity": 2.0, "unit": "tbsp"}, {"quantity": 2.0, "unit": "tsp"}], None, None
    ) == ([], None, None)


def test_single_cup_row_cleared():
    # "craisins 2 cups" (stored as a single measure + legacy columns) ⇒ bare item.
    assert m0020.cleaned_item([{"quantity": 2.0, "unit": "cup"}], 2.0, "cup") == ([], None, None)


def test_mixed_keeps_store_unit_and_recomputes_legacy():
    # "8 oz + 2 tbsp" cream cheese ⇒ the oz survives and becomes the single legacy measure.
    assert m0020.cleaned_item(
        [{"quantity": 8.0, "unit": "oz"}, {"quantity": 2.0, "unit": "tbsp"}], None, None
    ) == ([{"quantity": 8.0, "unit": "oz"}], 8.0, "oz")


def test_legacy_single_cooking_unit_row_cleared():
    # Pre-0003 row: measures NULL, only legacy quantity/unit set to a cooking unit.
    assert m0020.cleaned_item(None, 3.0, "teaspoons") == ([], None, None)


def test_store_unit_row_untouched():
    # A pure buy amount ⇒ None (the migration skips the row entirely).
    assert m0020.cleaned_item([{"quantity": 1.0, "unit": "lb"}], 1.0, "lb") is None


def test_bare_count_untouched():
    assert m0020.cleaned_item([{"quantity": 3.0, "unit": None}], 3.0, None) is None


def test_empty_row_untouched():
    assert m0020.cleaned_item(None, None, None) is None
    assert m0020.cleaned_item([], None, None) is None
