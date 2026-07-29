"""The v0.9 category re-sort (migration 0022) — verify the safe backfill heuristic.

Loads the migration module directly and exercises its pure ``resort_category`` helper. The
property that matters: it corrects a stale *auto-guess* and never touches an aisle a person
chose by hand, so running it can't undo anyone's corrections.
"""

import importlib.util
from pathlib import Path

import pytest

_MIGRATION = (
    Path(__file__).resolve().parent.parent
    / "alembic"
    / "versions"
    / "0022_resort_ingredient_categories.py"
)
_spec = importlib.util.spec_from_file_location("_m0022", _MIGRATION)
m0022 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(m0022)


@pytest.mark.parametrize(
    ("name", "stored", "expected"),
    [
        # ── stale auto-guesses the v0.9 fixes correct ──
        ("ground cumin", "meat", "pantry"),  # bare "ground" outranked the spice
        ("large poblano (ribs and seeds removed then sliced)", "meat", "produce"),
        ("red pepper flakes (or more to taste)", "produce", "pantry"),
        ("pineapple juice (no sugar added)", "produce", "beverages"),
        ("lime juice", "beverages", "produce"),
        ("ground ginger", "produce", "pantry"),
        # ── already correct: nothing to do ──
        ("ground cumin", "pantry", None),
        ("Ground beef", "meat", None),
        ("olive oil", "pantry", None),
        ("skirt or flank steak", "meat", None),
        # ── hand-picked aisles survive: the stored value isn't what the old guesser said ──
        ("ground cumin", "produce", None),
        ("chicken breast", "pantry", None),
        ("large poblano (ribs and seeds removed then sliced)", "produce", None),
        # The old guesser got this comma form right already ("pepper" outranked "rib"), so a
        # stored "meat" can only have come from a person — leave it, even though it looks odd.
        ("poblano peppers, ribs and seeds removed", "meat", None),
        # ── never guessable, before or after ──
        ("mystery widget", None, None),
        ("", None, None),
    ],
)
def test_resort_category(name, stored, expected):
    assert m0022.resort_category(name, stored) == expected


def test_downgrade_is_a_noop():
    """The re-sort is one-way by design — the prior guesses aren't recoverable."""
    assert m0022.downgrade() is None
