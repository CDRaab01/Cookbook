"""Aggregated measures on shopping-list items (v0.2.1 buyable-list redesign).

Revision ID: 0003
Revises: 0002
Create Date: 2026-07-02
"""

import sqlalchemy as sa

from alembic import op

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # JSON list of {"quantity": float, "unit": str|null}. Existing rows stay NULL and read
    # through their legacy quantity/unit columns until the next merge touches them.
    op.add_column("shopping_list_items", sa.Column("measures", sa.JSON(), nullable=True))


def downgrade() -> None:
    op.drop_column("shopping_list_items", "measures")
