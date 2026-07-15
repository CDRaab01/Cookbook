"""Add a per-entry cooking scale to meal_plan_entries (make a planned recipe bigger/smaller).

Distinct from the per-user "servings eaten"; this scales the ingredient quantities that flow into
the shopping list. Defaults to 1.0 so every existing entry is unchanged.

Revision ID: 0013
Revises: 0012
Create Date: 2026-07-15
"""

import sqlalchemy as sa

from alembic import op

revision = "0013"
down_revision = "0012"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "meal_plan_entries",
        sa.Column("scale", sa.Float(), nullable=False, server_default="1.0"),
    )


def downgrade() -> None:
    op.drop_column("meal_plan_entries", "scale")
