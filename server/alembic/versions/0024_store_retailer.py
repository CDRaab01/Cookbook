"""Link a store profile to a retailer's own store id, for aisle imports (v0.12).

Two nullable columns, no backfill, no data touched. A store built by hand keeps both NULL and
behaves exactly as it did — routing never reads these. They record *which* Meijer (or, later,
which other chain) an imported aisle layout describes, so a second import doesn't have to be told
again and a co-member running it gets the same store.

Revision ID: 0024
Revises: 0023
Create Date: 2026-08-01
"""

import sqlalchemy as sa

from alembic import op

revision = "0024"
down_revision = "0023"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("stores", sa.Column("retailer", sa.String(length=32), nullable=True))
    op.add_column("stores", sa.Column("retailer_store_id", sa.String(length=16), nullable=True))


def downgrade() -> None:
    op.drop_column("stores", "retailer_store_id")
    op.drop_column("stores", "retailer")
