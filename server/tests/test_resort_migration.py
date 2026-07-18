"""The one-time category re-sort (migration 0019) — verify the safe backfill heuristic.

Loads the migration module directly and exercises its pure ``resort_category`` helper: it must
rescue items the old set had no home for, correct stale auto-guesses, and never touch a value a
person set by hand.
"""

import importlib.util
from pathlib import Path

import pytest

_MIGRATION = (
    Path(__file__).resolve().parent.parent / "alembic" / "versions" / "0019_resort_categories.py"
)
_spec = importlib.util.spec_from_file_location("_m0019", _MIGRATION)
m0019 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(m0019)


@pytest.mark.parametrize(
    ("name", "stored", "expected"),
    [
        # rescued from Other (the old 7-bucket set had no home) — stored was None
        ("Diapers", None, "baby"),
        ("Paper towels", None, "household"),
        ("Sparkling water", None, "beverages"),
        # stale auto-guess corrected (old guesser had filed these elsewhere)
        ("Iced coffee", "pantry", "beverages"),
        ("Potato chips", "produce", "snacks"),
        ("Orange juice", "produce", "beverages"),
        ("Milk collector", "dairy", "baby"),  # the original culprit
        # already correct → no change (don't churn)
        ("Chicken", "meat", None),
        ("Sourdough bread", "bakery", None),
        ("Bananas", "produce", None),
        # manual pick (stored != what the old guesser would have said) → left untouched
        ("Chicken", "pantry", None),
        ("Milk", "other", None),
        ("Potato chips", "beverages", None),
    ],
)
def test_resort_category(name, stored, expected):
    assert m0019.resort_category(name, stored) == expected
