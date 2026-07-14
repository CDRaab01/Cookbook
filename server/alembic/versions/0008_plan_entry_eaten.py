"""Meal-plan entries gain an `eaten` flag — surfaced to Plate's coach (cross-app) so it knows
whether a planned meal actually happened.

Revision ID: 0008
Revises: 0007
Create Date: 2026-07-14
"""

import sqlalchemy as sa

from alembic import op

revision = "0008"
down_revision = "0007"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "meal_plan_entries",
        sa.Column(
            "eaten",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
    )


def downgrade() -> None:
    op.drop_column("meal_plan_entries", "eaten")
