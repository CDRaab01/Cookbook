"""Personal notes on recipes ("half the sugar next time").

Revision ID: 0004
Revises: 0003
Create Date: 2026-07-02
"""

import sqlalchemy as sa

from alembic import op

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("recipes", sa.Column("notes", sa.Text(), nullable=True))


def downgrade() -> None:
    op.drop_column("recipes", "notes")
