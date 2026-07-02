"""Weekly meal planner: recipes (or free-text notes) assigned to date + slot.

Revision ID: 0006
Revises: 0005
Create Date: 2026-07-02
"""

import sqlalchemy as sa

from alembic import op

revision = "0006"
down_revision = "0005"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "meal_plan_entries",
        sa.Column("id", sa.Uuid(), primary_key=True),
        sa.Column(
            "user_id",
            sa.Uuid(),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("date", sa.Date(), nullable=False),
        sa.Column("slot", sa.String(16), nullable=False),
        # A planned recipe... or a free-text plan ("Leftovers", "Pizza night out").
        sa.Column(
            "recipe_id",
            sa.Uuid(),
            sa.ForeignKey("recipes.id", ondelete="CASCADE"),
            nullable=True,
        ),
        sa.Column("note", sa.String(255), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
    )
    op.create_index("ix_meal_plan_entries_user_id", "meal_plan_entries", ["user_id"])
    op.create_index("ix_meal_plan_entries_date", "meal_plan_entries", ["date"])


def downgrade() -> None:
    op.drop_table("meal_plan_entries")
