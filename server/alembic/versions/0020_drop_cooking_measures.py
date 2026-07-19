"""One-time cleanup: drop cooking-only measures from existing shopping items (v0.8).

A shopping list is a list of things you BUY, so a cooking-volume amount ("2 tbsp", "2 cups") on a
line item is noise — you buy a bottle, a bag. v0.2.1 deliberately kept those measures ("2 tbsp +
2 tsp"); real store runs proved them unreadable, so from now on tsp/tbsp/cup/pinch/dash never land
on the list (app.lists.merge.buyable_measures, applied in shopping_service._store_measures). This
migration brings the *current* list in line: it strips cooking-only measures from every
``shopping_list_items`` row (recomputing the legacy quantity/unit columns to stay coherent) and
forgets any cooking-only unit remembered in ``item_history``. Store units (lb/oz/g/can/bag…) and
bare counts are kept; the item itself always stays on the list.

Data-only. Downgrade is a no-op (the dropped amounts aren't recoverable). No-op on a fresh DB.

Revision ID: 0020
Revises: 0019
Create Date: 2026-07-19
"""

import json

import sqlalchemy as sa

from alembic import op
from app.lists.merge import Measure, buyable_measures, canonical_unit, is_buyable_measure

revision = "0020"
down_revision = "0019"
branch_labels = None
depends_on = None


def _reconstruct(measures: list | None, quantity: float | None, unit: str | None) -> list[Measure]:
    """A row's current amounts as Measures, reading the JSON aggregate or the legacy single
    quantity/unit (pre-0003 rows) transparently — mirrors shopping_service._item_measures."""
    if measures:
        return [
            Measure(quantity=m["quantity"], unit=m.get("unit"))
            for m in measures
            if isinstance(m, dict) and isinstance(m.get("quantity"), (int, float))
        ]
    if quantity is not None:
        return [Measure(quantity=quantity, unit=canonical_unit(unit))]
    return []


def cleaned_item(measures: list | None, quantity: float | None, unit: str | None):
    """Pure helper (unit-tested): the (measures_json, quantity, unit) a row should become after
    dropping cooking-only amounts, or None when nothing changes (so we only touch affected rows)."""
    current = _reconstruct(measures, quantity, unit)
    kept = buyable_measures(current)
    if len(kept) == len(current):
        return None  # nothing cooking-only to drop
    new_json = [{"quantity": m.quantity, "unit": m.unit} for m in kept]
    if len(kept) == 1:
        return new_json, kept[0].quantity, kept[0].unit
    return new_json, None, None


def upgrade() -> None:
    bind = op.get_bind()

    # Shopping items: strip cooking-only measures, keep quantity/unit coherent. Read measures as
    # text (asyncpg hands raw json back as a string) and parse ourselves.
    rows = bind.execute(
        sa.text("SELECT id, quantity, unit, measures::text AS measures FROM shopping_list_items")
    ).fetchall()
    for row_id, quantity, unit, measures_text in rows:
        measures = json.loads(measures_text) if measures_text else None
        change = cleaned_item(measures, quantity, unit)
        if change is None:
            continue
        new_json, new_q, new_u = change
        bind.execute(
            sa.text(
                "UPDATE shopping_list_items SET measures = CAST(:m AS json), "
                "quantity = :q, unit = :u WHERE id = :i"
            ),
            {"m": json.dumps(new_json), "q": new_q, "u": new_u, "i": row_id},
        )

    # Autocomplete memory: forget a remembered cooking-only unit (it could never reach the list).
    hrows = bind.execute(
        sa.text("SELECT id, unit FROM item_history WHERE unit IS NOT NULL")
    ).fetchall()
    for row_id, unit in hrows:
        if not is_buyable_measure(unit):
            bind.execute(
                sa.text("UPDATE item_history SET unit = NULL WHERE id = :i"), {"i": row_id}
            )


def downgrade() -> None:
    pass  # dropped cooking amounts aren't recoverable; the cleanup is intentionally one-way
