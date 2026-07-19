"""One-time grocery-list declutter: strip cooking units + re-sort mis-filed beverages.

Two data-only cleanups for lists that predate the "things you buy, not cooking measures" fixes:

1. **Drop cooking-only measures** from existing ``shopping_list_items``. A recipe used to copy its
   measure verbatim onto the list, so lines read "pineapple juice · 0.25 cup" / "minced garlic ·
   1 tbsp" — amounts you can't shop by. We remove volume/spoon units (cup, tbsp, tsp, pinch,
   dash, clove, ml, l); weights, bare counts, and package units (can/jar/bag…) stay.

2. **Re-sort categories** with the improved guesser via migration 0019's ``resort_category`` — it
   only rewrites values that look auto-assigned (never a hand-picked aisle). With "<fruit> juice"
   now guessing beverages, this lifts a juice mis-filed under produce into the beverages aisle.

Idempotent and safe to re-run; a fresh DB has no rows, so it's a no-op there. Downgrade can't
recover the stripped amounts or prior guesses, so it's intentionally a no-op.

Revision ID: 0020
Revises: 0019
Create Date: 2026-07-19
"""

import importlib.util
import json
from pathlib import Path

import sqlalchemy as sa

from alembic import op
from app.lists.merge import _COOKING_ONLY_UNITS, canonical_unit

revision = "0020"
down_revision = "0019"
branch_labels = None
depends_on = None

# Reuse 0019's category-resort heuristic verbatim (it only touches auto-looking values). Loaded by
# path because migration modules aren't importable as a package.
_M0019_PATH = Path(__file__).resolve().parent / "0019_resort_categories.py"
_spec = importlib.util.spec_from_file_location("_m0019_resort", _M0019_PATH)
_m0019 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_m0019)
resort_category = _m0019.resort_category


def declutter_measures(measures: list | None) -> tuple[list, bool]:
    """Drop cooking-only measures (cups/tbsp/…) from a stored ``measures`` list.

    Returns ``(kept, changed)``. ``changed`` is False when nothing was cooking-only, so the caller
    can skip a no-op write. A weight/count/package measure is always kept.
    """
    if not measures:
        return measures or [], False
    kept = [
        m
        for m in measures
        if isinstance(m, dict) and canonical_unit(m.get("unit")) not in _COOKING_ONLY_UNITS
    ]
    return kept, len(kept) != len(measures)


def _as_list(raw) -> list | None:
    """The measures column comes back as a JSON string under some drivers, a list under others."""
    if isinstance(raw, str):
        try:
            return json.loads(raw)
        except (ValueError, TypeError):
            return None
    return raw


def upgrade() -> None:
    bind = op.get_bind()

    # 1. Strip cooking-only measures, keeping the legacy quantity/unit columns coherent.
    rows = bind.execute(sa.text("SELECT id, measures FROM shopping_list_items")).fetchall()
    for row_id, raw_measures in rows:
        measures = _as_list(raw_measures)
        kept, changed = declutter_measures(measures)
        if not changed:
            continue
        quantity = kept[0]["quantity"] if len(kept) == 1 else None
        unit = kept[0].get("unit") if len(kept) == 1 else None
        bind.execute(
            sa.text(
                "UPDATE shopping_list_items "
                "SET measures = CAST(:m AS JSON), quantity = :q, unit = :u WHERE id = :i"
            ),
            {"m": json.dumps(kept), "q": quantity, "u": unit, "i": row_id},
        )

    # 2. Re-sort mis-filed categories (juice/produce → beverages, etc.) on both tables.
    for table in ("shopping_list_items", "item_history"):
        cat_rows = bind.execute(sa.text(f"SELECT id, name, category FROM {table}")).fetchall()
        for row_id, name, category in cat_rows:
            new = resort_category(name, category)
            if new is not None:
                bind.execute(
                    sa.text(f"UPDATE {table} SET category = :c WHERE id = :i"),
                    {"c": new, "i": row_id},
                )


def downgrade() -> None:
    pass  # stripped amounts and prior guesses aren't recoverable; the cleanup is one-way
