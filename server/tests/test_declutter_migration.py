"""The one-time grocery-list declutter (migration 0020) — verify the pure measure-stripper.

Loads the migration module directly and exercises ``declutter_measures``: cooking-only units
(cups/tbsp/tsp/…) are dropped, while weights, counts, and package units survive.
"""

import importlib.util
from pathlib import Path

import pytest

_MIGRATION = (
    Path(__file__).resolve().parent.parent
    / "alembic"
    / "versions"
    / "0020_declutter_grocery_list.py"
)
_spec = importlib.util.spec_from_file_location("_m0020", _MIGRATION)
m0020 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(m0020)


@pytest.mark.parametrize(
    ("measures", "kept", "changed"),
    [
        # cooking-only measures are dropped entirely
        ([{"quantity": 0.25, "unit": "cup"}], [], True),
        ([{"quantity": 2.0, "unit": "cups"}], [], True),
        ([{"quantity": 1.0, "unit": "tbsp"}], [], True),
        ([{"quantity": 3.0, "unit": "clove"}], [], True),
        # weights / counts / packages survive untouched
        ([{"quantity": 2.0, "unit": "lb"}], [{"quantity": 2.0, "unit": "lb"}], False),
        ([{"quantity": 1.0, "unit": "can"}], [{"quantity": 1.0, "unit": "can"}], False),
        ([{"quantity": 3.0, "unit": None}], [{"quantity": 3.0, "unit": None}], False),  # bare count
        # mixed: strip the cooking measure, keep the shoppable one
        (
            [{"quantity": 2.0, "unit": "tbsp"}, {"quantity": 1.0, "unit": "bottle"}],
            [{"quantity": 1.0, "unit": "bottle"}],
            True,
        ),
        # nothing to do
        ([], [], False),
        (None, [], False),
    ],
)
def test_declutter_measures(measures, kept, changed):
    assert m0020.declutter_measures(measures) == (kept, changed)
